/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.remote.s3web.client

import io.kotlintest.TestCaseOrder
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import java.net.URI

class S3WebRemoteClientTest : StringSpec() {
    var client = S3WebRemoteClient()

    override fun testCaseOrder() = TestCaseOrder.Random

    init {
        "get provider returns s3web" {
            client.getProvider() shouldBe "s3web"
        }

        "parsing full S3 web URI succeeds" {
            val result = client.parseUri(URI("s3web://host/object/path"), emptyMap())
            result["url"] shouldBe "http://host/object/path"
        }

        "parsing S3 web without path succeeds" {
            val result = client.parseUri(URI("s3web://host"), emptyMap())
            result["url"] shouldBe "http://host"
        }

        "specifying an invalid property fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3web://host/path"), mapOf("foo" to "bar"))
            }
        }

        "plain s3web provider fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3web"), emptyMap())
            }
        }

        "specifying user fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3web://user@host/path"), emptyMap())
            }
        }

        "specifying password fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3web://user:password@host/path"), emptyMap())
            }
        }

        "specifying port succeeds" {
            val result = client.parseUri(URI("s3web://host:1023/object/path"), emptyMap())
            result["url"] shouldBe "http://host:1023/object/path"
        }

        "missing host in s3 web URI fails" {
            shouldThrow<IllegalArgumentException> {
                client.parseUri(URI("s3:///path"), emptyMap())
            }
        }

        "s3 web remote to URI succeeds" {
            val (uri, props) = client.toUri(mapOf("url" to "http://host/path"))
            uri shouldBe "s3web://host/path"
            props.size shouldBe 0
        }

        "get parameters returns empty map" {
            val result = client.getParameters(mapOf("url" to "http://host/path"))
            result.size shouldBe 0
        }
    }
}
