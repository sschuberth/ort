/*
 * Copyright (C) 2021 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.scanner.provenance

import javax.sql.DataSource

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SchemaUtils.withDataBaseLock
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

import org.ossreviewtoolkit.model.RepositoryProvenance
import org.ossreviewtoolkit.model.utils.DatabaseUtils.checkDatabaseEncoding
import org.ossreviewtoolkit.model.utils.DatabaseUtils.tableExists
import org.ossreviewtoolkit.model.utils.DatabaseUtils.transaction
import org.ossreviewtoolkit.scanner.storages.utils.jsonb

class PostgresNestedProvenanceStorage(
    /**
     * The JDBC data source to obtain database connections.
     */
    private val dataSource: Lazy<DataSource>,

    /**
     * The name of the table used for storing nested provenances.
     */
    private val tableName: String = "nested_provenances"
) : NestedProvenanceStorage {
    private val table = NestedProvenances(tableName)

    /** The [Database] instance on which all operations are executed. */
    private val database by lazy {
        Database.connect(dataSource.value).apply {
            transaction {
                withDataBaseLock {
                    if (!tableExists(tableName)) {
                        checkDatabaseEncoding()
                        @Suppress("DEPRECATION")
                        SchemaUtils.createMissingTablesAndColumns(table)
                    }
                }
            }
        }
    }

    override fun readNestedProvenance(root: RepositoryProvenance): NestedProvenanceResolutionResult? =
        database.transaction {
            table.selectAll().where {
                table.vcsType eq root.vcsInfo.type.toString() and
                    (table.vcsUrl eq root.vcsInfo.url) and
                    (table.vcsRevision eq root.resolvedRevision)
            }.map { it[table.result] }.find { it.nestedProvenance.root == root }
        }

    override fun writeNestedProvenance(root: RepositoryProvenance, result: NestedProvenanceResolutionResult) {
        database.transaction {
            val idsToRemove = table.selectAll().where {
                table.vcsType eq root.vcsInfo.type.toString() and
                    (table.vcsUrl eq root.vcsInfo.url) and
                    (table.vcsRevision eq root.resolvedRevision)
            }.filter { it[table.result].nestedProvenance.root == root }.map { it[table.id] }

            table.deleteWhere { table.id inList idsToRemove }

            table.insert {
                it[vcsType] = root.vcsInfo.type.toString()
                it[vcsUrl] = root.vcsInfo.url
                it[vcsRevision] = root.resolvedRevision
                it[table.result] = result
            }
        }
    }
}

private class NestedProvenances(tableName: String) : IntIdTable(tableName) {
    val vcsType = text("vcs_type")
    val vcsUrl = text("vcs_url")
    val vcsRevision = text("vcs_revision")
    val result = jsonb<NestedProvenanceResolutionResult>("result")

    init {
        // Index to improve lookup performance.
        index(isUnique = false, vcsType, vcsUrl, vcsRevision)
    }
}
