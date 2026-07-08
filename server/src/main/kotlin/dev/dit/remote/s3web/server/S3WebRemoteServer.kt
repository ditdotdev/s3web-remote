// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.remote.s3web.server

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.dit.remote.RemoteOperation
import dev.dit.remote.RemoteOperationType
import dev.dit.remote.RemoteServerUtil
import dev.dit.remote.archive.ArchiveRemote
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * The S3 web provider is a very simple provider for reading commits created by the S3 provider. It's primary purpose is
 * to make public demo data available without requiring people to have some kind of AWS credentials. It should not be
 * used as a general purpose remote. The URL can be any URL to the S3 bucket, even behind CloudFront, such as:
 *
 *      s3web://demo.dit-data.io/hello-world/postgres
 *
 * The main thing is that it expects to find the same layout as the S3 provider generates, including a "dit" file
 * at the root of the repository that has all the commit metadata.
 */
class S3WebRemoteServer : ArchiveRemote() {
    internal val util = RemoteServerUtil()
    internal val gson = GsonBuilder().create()
    internal val http =
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .build()

    override fun getProvider(): String {
        return "s3web"
    }

    /**
     * S3 Web remotes have only a single property, "url"
     */
    override fun validateRemote(remote: Map<String, Any>): Map<String, Any> {
        util.validateFields(remote, listOf("url"), emptyList())
        return remote
    }

    /**
     * Validate parameters, which are all optional (there are no supported parameters currently).
     */
    override fun validateParameters(parameters: Map<String, Any>?): Map<String, Any> {
        val params = parameters ?: emptyMap()
        util.validateFields(params, emptyList(), emptyList())
        return params
    }

    /**
     * Extract the "url" property from a remote map as a String, throwing a clean
     * IllegalArgumentException rather than ClassCastException if the value is not a String.
     */
    private fun remoteUrl(remote: Map<String, Any>): String {
        return remote["url"] as? String
            ?: throw IllegalArgumentException("url must be a string")
    }

    /**
     * Fetch a file from the given remote, returning as a response.
     */
    fun getFile(
        remote: Map<String, Any>,
        path: String,
    ): Response {
        val url = remoteUrl(remote)
        val request = Request.Builder().url("$url/$path").build()
        return http.newCall(request).execute()
    }

    /**
     * Get all commits stored in the main metadata file. Since this is the only way we can get the metadata of
     * a commit we use it for both listing commits and fetching individual commits. It is not particuarly efficient.
     */
    internal fun getAllCommits(remote: Map<String, Any>): List<Pair<String, Map<String, Any>>> {
        val ret = mutableListOf<Pair<String, Map<String, Any>>>()

        getFile(remote, "dit").use { response ->
            val body =
                when {
                    response.isSuccessful -> response.body.string()
                    response.code == 404 -> ""
                    else -> {
                        val url = remoteUrl(remote)
                        throw IOException("failed to get $url/dit, error code ${response.code}")
                    }
                }

            for (line in body.split("\n")) {
                if (line != "") {
                    val result: Map<String, Any> = gson.fromJson(line, object : TypeToken<Map<String, Any>>() {}.type)
                    val id = result.get("id")
                    val properties = result.get("properties")
                    if (id != null && properties != null) {
                        val idString =
                            id as? String
                                ?: throw IllegalArgumentException("commit id must be a string")

                        @Suppress("UNCHECKED_CAST")
                        val propertiesMap =
                            properties as? Map<String, Any>
                                ?: throw IllegalArgumentException("commit properties must be a map")
                        ret.add(idString to propertiesMap)
                    }
                }
            }
        }

        return ret
    }

    override fun listCommits(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
        tags: List<Pair<String, String?>>,
    ): List<Pair<String, Map<String, Any>>> {
        val commits = getAllCommits(remote)
        val matching = commits.filter { util.matchTags(it.second, tags) }
        return util.sortDescending(matching)
    }

    override fun getCommit(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
        commitId: String,
    ): Map<String, Any>? {
        val commits = getAllCommits(remote)
        val match = commits.filter { it.first == commitId }.firstOrNull()
        return match?.second
    }

    override fun syncDataEnd(
        operation: RemoteOperation,
        operationData: Any?,
        isSuccessful: Boolean,
    ) {
        // Do nothing
    }

    override fun syncDataStart(operation: RemoteOperation) {
        if (operation.type == RemoteOperationType.PUSH) {
            throw NotImplementedError("push operations are not supported with s3web remotes")
        }
    }

    override fun pullArchive(
        operation: RemoteOperation,
        operationData: Any?,
        volume: String,
        archive: File,
    ) {
        val archivePath = "${operation.commitId}/$volume.tar.gz"
        val response = getFile(operation.remote, archivePath)
        if (!response.isSuccessful) {
            throw IOException("failed to get ${operation.remote["url"]}/$archivePath, error code ${response.code}")
        }
        response.body.byteStream().use { input ->
            archive.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    override fun pushArchive(
        operation: RemoteOperation,
        operationData: Any?,
        volume: String,
        archive: File,
    ) {
        throw NotImplementedError("push operations are not supported with s3web remotes")
    }

    override fun pushMetadata(
        operation: RemoteOperation,
        commit: Map<String, Any>,
        isUpdate: Boolean,
    ) {
        throw NotImplementedError("push operations are not supported with s3web remotes")
    }
}
