/*
 * Copyright 2015-2018 Snowflake Computing
 * Copyright 2015 TouchType Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.snowflake.spark.snowflake

import java.net.URI

import net.snowflake.client.jdbc.internal.amazonaws.services.s3.AmazonS3Client
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.sql._
import org.slf4j.LoggerFactory
import net.snowflake.spark.snowflake.Parameters.MergedParameters
import net.snowflake.spark.snowflake.io.SupportedFormat.SupportedFormat
import net.snowflake.spark.snowflake.io.{SupportedFormat, SupportedSource}
import net.snowflake.spark.snowflake.io.SupportedSource.SupportedSource
import net.snowflake.spark.snowflake.DefaultJDBCWrapper.DataBaseOperations
import scala.reflect.ClassTag

/** Data Source API implementation for Amazon Snowflake database tables */
private[snowflake] case class SnowflakeRelation(
                                                 jdbcWrapper: JDBCWrapper,
                                                 params: MergedParameters,
                                                 userSchema: Option[StructType])(@transient val sqlContext: SQLContext)
  extends BaseRelation
    with PrunedFilteredScan
    with InsertableRelation {

  import SnowflakeRelation._

  override def toString: String = {
    "SnowflakeRelation"
  }

  val log = LoggerFactory.getLogger(getClass) // Create a temporary stage

  override lazy val schema: StructType = {
    userSchema.getOrElse {
      val tableNameOrSubquery =
        params.query.map(q => s"($q)").orElse(params.table.map(_.toString)).get
      val conn = jdbcWrapper.getConnector(params)
      try {
        jdbcWrapper.resolveTable(conn, tableNameOrSubquery, params)
      } finally {
        conn.close()
      }
    }
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    val saveMode = if (overwrite) {
      SaveMode.Overwrite
    } else {
      SaveMode.Append
    }
    val writer = new SnowflakeWriter(jdbcWrapper)
    writer.save(sqlContext, data, saveMode, params)
  }

  override def unhandledFilters(filters: Array[Filter]): Array[Filter] = {
    filters.filterNot(filter =>
      FilterPushdown.buildFilterStatement(schema, filter).isDefined)
  }

  // Build the RDD from a query string, generated by SnowflakeStrategy. Type can be InternalRow to comply with
  // SparkPlan's doExecute().
  def buildScanFromSQL[T: ClassTag](
                                     statement: SnowflakeSQLStatement,
                                     schema: Option[StructType]
                                   ): RDD[T] = {
    log.debug(Utils.sanitizeQueryText(statement.statementString))

    val conn = jdbcWrapper.getConnector(params)
    val resultSchema = schema.getOrElse(try {
      conn.tableSchema(statement, params)
    } finally {
      conn.close()
    })
    getRDD[T](statement, resultSchema)
  }

  // Build RDD result from PrunedFilteredScan interface. Maintain this here for backwards compatibility and for
  // when extra pushdowns are disabled.
  override def buildScan(requiredColumns: Array[String],
                         filters: Array[Filter]): RDD[Row] = {
    if (requiredColumns.isEmpty) {
      // In the special case where no columns were requested, issue a `count(*)` against Snowflake
      // rather than unloading data.
      val whereClause = FilterPushdown.buildWhereStatement(schema, filters)
      val tableNameOrSubquery: SnowflakeSQLStatement =
        params.query.map(ConstantString("(") + _ + ")").getOrElse(params.table.get.toStatement !)
      val countQuery =
        ConstantString("SELECT count(*) FROM") + tableNameOrSubquery + whereClause
      log.debug(Utils.sanitizeQueryText(countQuery.statementString))
      val conn = jdbcWrapper.getConnector(params)
      try {
        val results = countQuery.execute(conn)
        if (results.next()) {
          val numRows = results.getLong(1)
          val parallelism =
            sqlContext.getConf("spark.sql.shuffle.partitions", "200").toInt
          val emptyRow = Row.empty
          sqlContext.sparkContext
            .parallelize(1L to numRows, parallelism)
            .map(_ => emptyRow)
        } else {
          throw new IllegalStateException(
            "Could not read count from Snowflake")
        }
      } finally {
        conn.close()
      }
    } else {
      // Unload data from Snowflake into a temporary directory in S3:
      val prunedSchema = pruneSchema(schema, requiredColumns)

      getRDD[Row](standardStatement(requiredColumns, filters), prunedSchema)
    }
  }

  // Get an RDD from an unload statement. Provide result schema because
  // when a custom SQL statement is used, this means that we cannot know the results
  // without first executing it.
  private def getRDD[T: ClassTag](
                                   statement: SnowflakeSQLStatement,
                                   resultSchema: StructType
                                 ): RDD[T] = {
    val format: SupportedFormat =
      if (Utils.containVariant(resultSchema)) SupportedFormat.JSON
      else SupportedFormat.CSV

    val rdd: RDD[String] = io.readRDD(sqlContext, params, statement, format)

    format match {
      case SupportedFormat.CSV =>
        rdd.mapPartitions(CSVConverter.convert[T](_, resultSchema))
      case SupportedFormat.JSON =>
        rdd.mapPartitions(JsonConverter.convert[T](_, resultSchema))
    }

  }

  // Build a query out of required columns and filters. (Used by buildScan)
  private def standardStatement(
                                 requiredColumns: Array[String],
                                 filters: Array[Filter]
                               ): SnowflakeSQLStatement = {

    assert(!requiredColumns.isEmpty)
    // Always quote column names, and uppercase-cast them to make them equivalent to being unquoted
    // (unless already quoted):
    val columnList = requiredColumns
      .map(
        col=>
          if(params.keepOriginalColumnNameCase) Utils.quotedNameIgnoreCase(col)
          else Utils.ensureQuoted(col)
      )
      .mkString(", ")
    val whereClause = FilterPushdown.buildWhereStatement(schema, filters)
    val tableNameOrSubquery: StatementElement =
      params.table.map(_.toStatement).getOrElse(ConstantString("(" + params.query.get + ")"))
    ConstantString("SELECT") + columnList + "FROM" + tableNameOrSubquery + whereClause
  }
}

private[snowflake] object SnowflakeRelation {

  private def pruneSchema(schema: StructType,
                          columns: Array[String]): StructType = {
    val fieldMap = Map(schema.fields.map(x => x.name -> x): _*)
    new StructType(columns.map(name => fieldMap(name)))
  }

  private def isQuoted(name: String): Boolean = {
    name.startsWith("\"") && name.endsWith("\"")
  }
}
