/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.bigquery

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.bigquery.model.{TableReference, TableSchema}
import com.google.cloud.hadoop.util.ApiErrorExtractor
import com.spotify.scio.bigquery.client.BigQuery
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryHelpers

import scala.collection.mutable.{Map => MMap}

/** Companion object for [[MockBigQuery]]. */
object MockBigQuery {

  /** Create a new MockBigQuery instance. */
  def apply(): MockBigQuery = new MockBigQuery(BigQuery.defaultInstance())

  /** Create a new MockBigQuery instance with the given BigQueryClient. */
  def apply(bq: BigQuery): MockBigQuery = new MockBigQuery(bq)
}

/**
 * Mock BigQuery environment for integration test.
 *
 * Use [[mockTable(original:String)* mockTable]] to feed data into live BigQuery service and
 * [[queryResult]] to query them.
 */
class MockBigQuery private (private val bq: BigQuery) extends MockTypedBigQuery {
  private val mapping = MMap.empty[TableReference, TableReference]

  /** Mock a BigQuery table. Each table can be mocked only once in a test class. */
  def mockTable(original: String): MockTable =
    mockTable(BigQueryHelpers.parseTableSpec(original))

  /** Mock a BigQuery table. Each table can be mocked only once in a test class. */
  def mockTable(original: TableReference): MockTable = {
    require(
      !mapping.contains(original),
      s"Table ${BigQueryHelpers.toTableSpec(original)} already registered for mocking"
    )

    val t = bq.tables.table(original)
    val temp = bq.tables.createTemporary(t.getLocation)
    mapping += (original -> temp)
    new MockTable(bq, t.getSchema, original, temp)
  }

  /**
   * Get result of a live query against BigQuery service, substituting mocked tables with test
   * data.
   */
  def queryResult(sqlQuery: String, flattenResults: Boolean = false): Seq[TableRow] = {
    val isLegacy = bq.query.isLegacySql(sqlQuery, flattenResults)
    val mockQuery = mapping.foldLeft(sqlQuery) { case (q, (src, dst)) =>
      q.replace(toTableSpec(src, isLegacy), toTableSpec(dst, isLegacy))
    }
    try {
      bq.query.rows(mockQuery, flattenResults).toList
    } catch {
      case e: GoogleJsonResponseException if ApiErrorExtractor.INSTANCE.itemNotFound(e) =>
        throw new RuntimeException(
          "404 Not Found, this is most likely caused by missing source table or mock data",
          e
        )
    }
  }

  private def toTableSpec(table: TableReference, isLegacy: Boolean) =
    if (isLegacy) {
      s"[${table.getProjectId}:${table.getDatasetId}.${table.getTableId}]"
    } else {
      s"`${table.getProjectId}.${table.getDatasetId}.${table.getTableId}`"
    }
}

/** A BigQuery table being mocked for test. */
class MockTable(
  private val bq: BigQuery,
  private val schema: TableSchema,
  private val original: TableReference,
  private val temp: TableReference
) extends MockTypedTable {
  private var mocked: Boolean = false

  private def ensureUnique(): Unit = {
    require(
      !mocked,
      s"Table ${BigQueryHelpers.toTableSpec(original)} already populated with mock data"
    )
    this.mocked = true
  }

  private def writeRows(rows: Seq[TableRow]): Long =
    bq.tables.writeRows(temp, rows.toList, schema, WRITE_EMPTY, CREATE_IF_NEEDED)

  /** Populate the table with mock data. */
  def withData(rows: Seq[TableRow]): Unit = {
    ensureUnique()
    writeRows(rows)
    ()
  }

  /**
   * Populate the table with sample data from the original table.
   * Note that rows are taken from the beginning of the table and not truly random.
   */
  def withSample(numRows: Int): Unit = {
    ensureUnique()
    val rows = bq.tables.rows(Table.Ref(original)).take(numRows).toList
    require(rows.length == numRows, s"Sample size ${rows.length} != requested $numRows")
    writeRows(rows)
    ()
  }

  /**
   * Populate the table with sample data from the original table.
   * Note that rows are taken from the beginning of the table and not truly random.
   */
  def withSample(minNumRows: Int, maxNumRows: Int): Unit = {
    ensureUnique()
    val rows = bq.tables.rows(Table.Ref(original)).take(maxNumRows).toList
    require(
      rows.length >= minNumRows && rows.length <= maxNumRows,
      s"Sample size ${rows.length} < requested minimal $minNumRows"
    )
    writeRows(rows)
    ()
  }
}
