/*
 * Copyright Dit.
 */

package dev.dit.remote.s3web.server

import dev.dit.remote.RemoteOperation
import dev.dit.remote.RemoteOperationType
import dev.dit.remote.RemoteProgress
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.OverrideMockKs
import io.mockk.impl.annotations.SpyK
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.verify
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.IllegalArgumentException
import kotlin.io.path.createTempFile

class S3WebRemoteServerTest : StringSpec() {
    @MockK
    lateinit var http: OkHttpClient

    @SpyK
    var server = S3WebRemoteServer()

    @InjectMockKs
    @OverrideMockKs
    var mockServer = S3WebRemoteServer()

    val operation =
        RemoteOperation(
            updateProgress = { _: RemoteProgress, _: String?, _: Int? -> Unit },
            remote = mapOf("url" to "http://host/path"),
            parameters = emptyMap(),
            operationId = "operation",
            commitId = "commit",
            commit = null,
            type = RemoteOperationType.PUSH,
        )

    override fun beforeTest(testCase: TestCase) {
        return MockKAnnotations.init(this)
    }

    override fun afterTest(
        testCase: TestCase,
        result: TestResult,
    ) {
        clearAllMocks()
    }

    override fun testCaseOrder() = TestCaseOrder.Random

    init {
        "get provider returns s3web" {
            server.getProvider() shouldBe "s3web"
        }

        "validate remote succeeds with only required properties" {
            val result = server.validateRemote(mapOf("url" to "url"))
            result["url"] shouldBe "url"
        }

        "validate remote fails with missing required property" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(emptyMap())
            }
        }

        "validate remote fails with invalid property" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(mapOf("url" to "url", "foo" to "bar"))
            }
        }

        "validate parameters succeeds with empty properties" {
            val result = server.validateParameters(emptyMap())
            result.size shouldBe 0
        }

        "validate parameters succeeds with null input" {
            val result = server.validateParameters(null)
            result.size shouldBe 0
        }

        "validate parameters fails with invalid property" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(mapOf("foo" to "bar"))
            }
        }

        "getFile constructs correct path" {
            mockkConstructor(Request.Builder::class)
            val builder: Request.Builder = mockk()
            every { anyConstructed<Request.Builder>().url("http://host/path") } returns builder
            every { builder.build() } returns mockk()
            val call: Call = mockk()
            every { http.newCall(any()) } returns call
            every { call.execute() } returns mockk()
            mockServer.getFile(mapOf("url" to "http://host"), "path")
        }

        "getAllCommits succeeds" {
            val response: Response = mockk(relaxed = true)
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns true
            val responseBody: ResponseBody = mockk()
            every { response.body } returns responseBody
            every { responseBody.string() } returns
                arrayOf(
                    "{\"id\":\"a\",\"properties\":{\"timestamp\":\"2019-09-20T13:45:36Z\"}}",
                    "{\"id\":\"b\",\"properties\":{\"timestamp\":\"2019-09-20T13:45:37Z\"}}",
                ).joinToString("\n")
            val commits = server.getAllCommits(mapOf("url" to "http://host"))
            commits.size shouldBe 2
            commits[0].first shouldBe "a"
            commits[0].second["timestamp"] shouldBe "2019-09-20T13:45:36Z"
            commits[1].first shouldBe "b"
            commits[1].second["timestamp"] shouldBe "2019-09-20T13:45:37Z"
        }

        "getAllCommits returns empty list on 404" {
            val response: Response = mockk(relaxed = true)
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns false
            every { response.code } returns 404
            val commits = server.getAllCommits(mapOf("url" to "http://host"))
            commits.size shouldBe 0
        }

        "getAllCommits throws exception on unknown error" {
            val response: Response = mockk(relaxed = true)
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns false
            every { response.code } returns 403
            shouldThrow<IOException> {
                server.getAllCommits(mapOf("url" to "http://host"))
            }
        }

        "list commits returns list in reverse order" {
            every { server.getAllCommits(any()) } returns
                listOf(
                    "a" to mapOf("timestamp" to "2019-09-20T13:45:36Z"),
                    "b" to mapOf("timestamp" to "2019-09-20T13:45:37Z"),
                )
            val commits = server.listCommits(mapOf("url" to "http://host"), emptyMap(), emptyList())
            commits.size shouldBe 2
            commits[0].first shouldBe "b"
            commits[1].first shouldBe "a"
        }

        "list commits filters by tag" {
            every { server.getAllCommits(any()) } returns
                listOf(
                    "a" to mapOf("tags" to mapOf("a" to "b")),
                    "b" to mapOf("tags" to mapOf("c" to "d")),
                )
            val commits = server.listCommits(mapOf("url" to "http://host"), emptyMap(), listOf("a" to "b"))
            commits.size shouldBe 1
            commits[0].first shouldBe "a"
        }

        "get commit succeeds" {
            every { server.getAllCommits(any()) } returns
                listOf(
                    "a" to mapOf("a" to "b"),
                    "b" to mapOf("c" to "d"),
                )
            val commit = server.getCommit(mapOf("url" to "http://host"), emptyMap(), "a")
            commit shouldNotBe null
            commit!!["a"] shouldBe "b"
        }

        "get commit fails" {
            every { server.getAllCommits(any()) } returns
                listOf(
                    "a" to mapOf("a" to "b"),
                    "b" to mapOf("c" to "d"),
                )
            val commit = server.getCommit(mapOf("url" to "http://host"), emptyMap(), "x")
            commit shouldBe null
        }

        "start operation fails for push operation" {
            shouldThrow<NotImplementedError> {
                server.syncDataStart(operation)
            }
        }

        "start operation succeeds for pull operation" {
            server.syncDataStart(operation.copy(type = RemoteOperationType.PULL))
        }

        "end operation suceeds" {
            server.syncDataEnd(operation, null, true)
        }

        "push archive fails" {
            shouldThrow<NotImplementedError> {
                server.pushArchive(operation, null, "volume", createTempFile().toFile())
            }
        }

        "push metadata fails" {
            shouldThrow<NotImplementedError> {
                server.pushMetadata(operation, emptyMap(), true)
            }
        }

        "pull archive succeeds" {
            val response: Response = mockk()
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns true
            val responseBody: ResponseBody = mockk()
            every { response.body } returns responseBody
            every { responseBody.byteStream() } returns ByteArrayInputStream("test".toByteArray())

            val file = createTempFile().toFile()
            server.pullArchive(operation, null, "volume", file)

            file.readText() shouldBe "test"

            verify {
                server.getFile(any(), "commit/volume.tar.gz")
            }
        }

        "pull archive fails on error" {
            val response: Response = mockk()
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns false
            every { response.code } returns 403

            shouldThrow<IOException> {
                server.pullArchive(operation, null, "volume", createTempFile().toFile())
            }
        }

        "http client has non-zero connect, read, and write timeouts" {
            // Default OkHttpClient has no timeouts (all zero), which can hang on a stalled
            // S3-website endpoint indefinitely. Verify the client is configured with explicit
            // non-zero timeouts.
            server.http.connectTimeoutMillis shouldNotBe 0
            server.http.readTimeoutMillis shouldNotBe 0
            server.http.writeTimeoutMillis shouldNotBe 0
        }

        "http client uses expected timeout values" {
            // Concrete values per the architecture review fix:
            //   connectTimeout = 10s, readTimeout = 30s, writeTimeout = 30s
            server.http.connectTimeoutMillis shouldBe 10_000
            server.http.readTimeoutMillis shouldBe 30_000
            server.http.writeTimeoutMillis shouldBe 30_000
        }

        "getAllCommits closes the response on success" {
            val response: Response = mockk(relaxed = true)
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns true
            val responseBody: ResponseBody = mockk()
            every { response.body } returns responseBody
            every { responseBody.string() } returns ""

            server.getAllCommits(mapOf("url" to "http://host"))

            verify { response.close() }
        }

        "getAllCommits closes the response on 404" {
            val response: Response = mockk(relaxed = true)
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns false
            every { response.code } returns 404

            server.getAllCommits(mapOf("url" to "http://host"))

            verify { response.close() }
        }

        "getAllCommits closes the response on error" {
            val response: Response = mockk(relaxed = true)
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns false
            every { response.code } returns 403

            shouldThrow<IOException> {
                server.getAllCommits(mapOf("url" to "http://host"))
            }

            verify { response.close() }
        }

        "getFile throws IllegalArgumentException when url is not a string" {
            // Unsafe `as String` cast would throw ClassCastException with a confusing
            // message. Verify we throw a clean IllegalArgumentException instead.
            shouldThrow<IllegalArgumentException> {
                server.getFile(mapOf("url" to 42), "path")
            }
        }

        "getAllCommits throws IllegalArgumentException when url is not a string on error path" {
            // The error-path `as String` cast in getAllCommits would also panic on bad input.
            val response: Response = mockk(relaxed = true)
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns false
            every { response.code } returns 500

            shouldThrow<IllegalArgumentException> {
                server.getAllCommits(mapOf("url" to 42))
            }
        }

        "getAllCommits throws IllegalArgumentException when id is not a string" {
            val response: Response = mockk(relaxed = true)
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns true
            val responseBody: ResponseBody = mockk()
            every { response.body } returns responseBody
            every { responseBody.string() } returns
                "{\"id\":42,\"properties\":{\"timestamp\":\"2019-09-20T13:45:36Z\"}}"

            shouldThrow<IllegalArgumentException> {
                server.getAllCommits(mapOf("url" to "http://host"))
            }
        }

        "getAllCommits skips lines missing id or properties" {
            // Lines with neither id nor properties, or only one, should be silently skipped.
            // This exercises the null-branches of `if (id != null && properties != null)`.
            val response: Response = mockk(relaxed = true)
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns true
            val responseBody: ResponseBody = mockk()
            every { response.body } returns responseBody
            every { responseBody.string() } returns
                arrayOf(
                    // Missing properties
                    "{\"id\":\"a\"}",
                    // Missing id
                    "{\"properties\":{\"timestamp\":\"2019-09-20T13:45:36Z\"}}",
                    // Both missing
                    "{}",
                    // Valid
                    "{\"id\":\"c\",\"properties\":{\"timestamp\":\"2019-09-20T13:45:38Z\"}}",
                ).joinToString("\n")
            val commits = server.getAllCommits(mapOf("url" to "http://host"))
            commits.size shouldBe 1
            commits[0].first shouldBe "c"
        }

        "getAllCommits throws IllegalArgumentException when properties is not a map" {
            val response: Response = mockk(relaxed = true)
            every { server.getFile(any(), any()) } returns response
            every { response.isSuccessful } returns true
            val responseBody: ResponseBody = mockk()
            every { response.body } returns responseBody
            every { responseBody.string() } returns
                "{\"id\":\"a\",\"properties\":\"not-a-map\"}"

            shouldThrow<IllegalArgumentException> {
                server.getAllCommits(mapOf("url" to "http://host"))
            }
        }
    }
}
