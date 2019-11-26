/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.remote.s3web.server

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.titandata.remote.RemoteOperation
import io.titandata.remote.RemoteOperationType
import io.titandata.remote.RemoteServerUtil
import io.titandata.remote.archive.ArchiveRemote
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The S3 web provider is a very simple provider for reading commits created by the S3 provider. It's primary purpose is
 * to make public demo data available without requiring people to have some kind of AWS credentials. It should not be
 * used as a general purpose remote. The URL can be any URL to the S3 bucket, even behind CloudFront, such as:
 *
 *      s3web://demo.titan-data.io/hello-world/postgres
 *
 * The main thing is that it expects to find the same layout as the S3 provider generates, including a "titan" file
 * at the root of the repository that has all the commit metadata.
 */
class S3WebRemoteServer : ArchiveRemote() {

    internal val util = RemoteServerUtil()
    internal val gson = GsonBuilder().create()
    internal val http = OkHttpClient()

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
     * S3 web parameters must always be empty
     */
    override fun validateParameters(parameters: Map<String, Any>): Map<String, Any> {
        util.validateFields(parameters, emptyList(), emptyList())
        return parameters
    }

    /**
     * Fetch a file from the given remote, returning as a response.
     */
    fun getFile(remote: Map<String, Any>, path: String): Response {
        val url = remote["url"] as String
        val request = Request.Builder().url("$url/$path").build()
        return http.newCall(request).execute()
    }

    /**
     * Get all commits stored in the main metadata file. Since this is the only way we can get the metadata of
     * a commit we use it for both listing commits and fetching individual commits. It is not particuarly efficient.
     */
    internal fun getAllCommits(remote: Map<String, Any>): List<Pair<String, Map<String, Any>>> {
        val response = getFile(remote, "titan")
        val url = remote["url"] as String
        val body = if (response.isSuccessful) {
            response.body!!.string()
        } else if (response.code == 404) {
            ""
        } else {
            throw IOException("failed to get $url/titan, error code ${response.code}")
        }

        val ret = mutableListOf<Pair<String, Map<String, Any>>>()

        for (line in body.split("\n")) {
            if (line != "") {
                val result: Map<String, Any> = gson.fromJson(line, object : TypeToken<Map<String, Any>>() {}.type)
                val id = result.get("id")
                val properties = result.get("properties")
                if (id != null && properties != null) {
                    id as String
                    @Suppress("UNCHECKED_CAST")
                    properties as Map<String, Any>
                    ret.add(id to properties)
                }
            }
        }

        return ret
    }

    override fun listCommits(remote: Map<String, Any>, parameters: Map<String, Any>, tags: List<Pair<String, String?>>): List<Pair<String, Map<String, Any>>> {
        val commits = getAllCommits(remote)
        val matching = commits.filter { util.matchTags(it.second, tags) }
        return util.sortDescending(matching)
    }

    override fun getCommit(remote: Map<String, Any>, parameters: Map<String, Any>, commitId: String): Map<String, Any>? {
        val commits = getAllCommits(remote)
        val match = commits.filter { it.first == commitId }.firstOrNull()
        return match?.second
    }

    override fun syncDataEnd(operation: RemoteOperation, operationData: Any?, isSuccessful: Boolean) {
        // Do nothing
    }

    override fun syncDataStart(operation: RemoteOperation) {
        if (operation.type == RemoteOperationType.PUSH) {
            throw NotImplementedError("push operations are not supported with s3web remotes")
        }
    }

    override fun pullArchive(operation: RemoteOperation, operationData: Any?, volume: String, archive: File) {
        val archivePath = "${operation.commitId}/$volume.tar.gz"
        val response = getFile(operation.remote, archivePath)
        if (!response.isSuccessful) {
            throw IOException("failed to get ${operation.remote["url"]}/$archivePath, error code ${response.code}")
        }
        response.body!!.byteStream().use { input ->
            archive.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    override fun pushArchive(operation: RemoteOperation, operationData: Any?, volume: String, archive: File) {
        throw NotImplementedError("push operations are not supported with s3web remotes")
    }

    override fun pushMetadata(operation: RemoteOperation, commit: Map<String, Any>, isUpdate: Boolean) {
        throw NotImplementedError("push operations are not supported with s3web remotes")
    }
}
