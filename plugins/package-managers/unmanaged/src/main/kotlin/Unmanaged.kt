/*
 * Copyright (C) 2017 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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

package org.ossreviewtoolkit.plugins.packagemanagers.unmanaged

import java.io.File

import org.apache.logging.log4j.kotlin.logger

import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.utils.parseRepoManifestPath
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.PluginDescriptor

/**
 * A fake [PackageManager] for projects that do not use any of the known package managers, or no package manager at all.
 * It is required as in ORT's data model e.g. scan results need to be attached to projects (or packages), so files that
 * do not belong to any other project need to be attached to somewhere.
 */
@OrtPlugin(
    displayName = "Unmanaged",
    description = "The Unmanaged package manager for projects that do not use any package manager.",
    factory = PackageManagerFactory::class
)
class Unmanaged(override val descriptor: PluginDescriptor = UnmanagedFactory.descriptor) : PackageManager("Unmanaged") {
    // The empty list returned here deliberately causes this special package manager to never be considered in
    // PackageManager.findManagedFiles(). Instead, it will only be explicitly instantiated as part of
    // Analyzer.findManagedFiles().
    override val globsForDefinitionFiles = emptyList<String>()

    /**
     * Return a list with a single [ProjectAnalyzerResult] for the "unmanaged" [Project] defined by the
     * [definitionFile], which in this case is a directory. No dependency resolution is performed.
     */
    override fun resolveDependencies(
        analysisRoot: File,
        definitionFile: File,
        excludes: Excludes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): List<ProjectAnalyzerResult> {
        val vcsInfo = VersionControlSystem.getCloneInfo(definitionFile)

        val id = when {
            vcsInfo == VcsInfo.EMPTY -> {
                // This seems to be an analysis of a local directory that is not under version control, i.e. that is not
                // a VCS working tree. In this case we have no chance to get a version.
                logger.warn {
                    "Analysis of local directory '$definitionFile' which is not under version control will produce " +
                        "non-cacheable results as no version for the cache key can be determined."
                }

                Identifier(
                    type = projectType,
                    namespace = "",
                    name = definitionFile.name,
                    version = ""
                )
            }

            vcsInfo.type == VcsType.GIT_REPO -> {
                // For GitRepo looking at the URL and revision only is not enough, we also need to take the used
                // manifest into account.
                val manifestPath = vcsInfo.url.parseRepoManifestPath()

                Identifier(
                    type = projectType,
                    namespace = manifestPath?.substringBeforeLast('/').orEmpty(),
                    name = manifestPath?.substringAfterLast('/')?.removeSuffix(".xml")
                        ?: vcsInfo.url.split('/').last().removeSuffix(".git"),
                    version = vcsInfo.revision
                )
            }

            else -> {
                // For all non-GitRepo VCSes derive the name from the VCS URL.
                Identifier(
                    type = projectType,
                    namespace = "",
                    name = vcsInfo.url.split('/').last().removeSuffix(".git"),
                    version = vcsInfo.revision
                )
            }
        }

        return listOf(
            ProjectAnalyzerResult(
                project = Project.EMPTY.copy(id = id, vcsProcessed = vcsInfo),
                packages = emptySet()
            )
        )
    }
}
