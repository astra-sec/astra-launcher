package org.astrasec.launcher.apkupload

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ApkUploadServerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `serves a self contained page without exposing the pin`() {
        withServer { server, _ ->
            val response = exchange(server, "GET / HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n")
            assertEquals(200, response.status)
            assertTrue(response.body.contains("把应用发送到电视"))
            assertTrue(response.body.contains("X-Astra-Pin"))
            assertFalse(response.body.contains(PIN))
            assertTrue(response.headers.contains("Cache-Control: no-store"))
            assertFalse(response.headers.contains("Access-Control-Allow-Origin"))
        }
    }

    @Test
    fun `streams an upload and hands its exact bytes to coordinator`() {
        val received = AtomicReference<File>()
        withServer(onUpload = { file -> received.set(file); "请在电视上确认安装" }) { server, dir ->
            val bytes = ByteArray(150_003) { (it % 256).toByte() }
            val response = exchange(server, uploadHeader(server, bytes.size.toString()), bytes)
            assertEquals(200, response.status)
            assertEquals("请在电视上确认安装", response.body)
            val receivedFile = requireNotNull(received.get())
            assertArrayEquals(bytes, receivedFile.readBytes())
            assertEquals(dir.canonicalFile, requireNotNull(receivedFile.parentFile).canonicalFile)
            assertTrue(receivedFile.name.startsWith("astra-received-"))
            server.close()
            assertTrue("Accepted APK belongs to the coordinator", receivedFile.exists())
        }
    }

    @Test
    fun `rejects an incorrect pin before creating a file`() {
        val calls = AtomicInteger()
        withServer(onUpload = { calls.incrementAndGet(); "ok" }) { server, dir ->
            val response = exchange(server, uploadHeader(server, "4", pin = "wrong"), byteArrayOf(1, 2, 3, 4))
            assertEquals(403, response.status)
            assertEquals(0, calls.get())
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `limits repeated incorrect pin attempts`() {
        withServer { server, dir ->
            repeat(8) {
                assertEquals(403, exchange(server, uploadHeader(server, "4", pin = "wrong")).status)
            }
            assertEquals(429, exchange(server, uploadHeader(server, "4", pin = "wrong")).status)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `rejects empty invalid and excessive content lengths without allocating files`() {
        withServer { server, dir ->
            listOf("0" to 400, "-1" to 400, "+12" to 400, "x" to 400,
                (ApkUploadServer.MAX_APK_BYTES + 1).toString() to 413,
                "9999999999999999999999999" to 413
            ).forEach { (length, expected) ->
                assertEquals("Length: $length", expected, exchange(server, uploadHeader(server, length)).status)
            }
            val missingLength = uploadHeader(server, "4").replace("Content-Length: 4\r\n", "")
            assertEquals(411, exchange(server, missingLength).status)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `cleans up a truncated body and does not call coordinator`() {
        val calls = AtomicInteger()
        withServer(onUpload = { calls.incrementAndGet(); "ok" }) { server, dir ->
            val response = exchange(server, uploadHeader(server, "100"), byteArrayOf(1, 2))
            assertEquals(400, response.status)
            assertEquals(0, calls.get())
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `rejects non routes and path traversal`() {
        withServer { server, dir ->
            listOf("/upload/../../outside.apk", "/../outside.apk", "/upload?name=../outside.apk").forEach { path ->
                assertEquals(404, exchange(server, uploadHeader(server, "4").replace("POST /upload ", "POST $path ")).status)
            }
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `rejects ambiguous content length and transfer encoding`() {
        withServer { server, dir ->
            val duplicate = uploadHeader(server, "4", extra = "Content-Length: 4\r\n")
            val chunked = uploadHeader(server, "4", extra = "Transfer-Encoding: chunked\r\n")
            assertEquals(400, exchange(server, duplicate).status)
            assertEquals(400, exchange(server, chunked).status)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `rejects cross origin and DNS hosts and accepts same origin`() {
        withServer { server, _ ->
            val crossOrigin = uploadHeader(server, "1", extra = "Origin: http://attacker.example\r\n")
            assertEquals(403, exchange(server, crossOrigin).status)
            val dnsHost = uploadHeader(server, "1").replace("Host: 127.0.0.1:${server.port}", "Host: attacker.example:${server.port}")
            assertEquals(403, exchange(server, dnsHost).status)
            val sameOrigin = uploadHeader(server, "1", extra = "Origin: http://127.0.0.1:${server.port}\r\n")
            assertEquals(200, exchange(server, sameOrigin, byteArrayOf(1)).status)
        }
    }

    @Test
    fun `deletes APK rejected by package validation`() {
        withServer(onUpload = { throw IllegalArgumentException("不是有效的 APK") }) { server, dir ->
            val response = exchange(server, uploadHeader(server, "4"), byteArrayOf(1, 2, 3, 4))
            assertEquals(400, response.status)
            assertEquals("不是有效的 APK", response.body)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `allows only one active upload and closing stops incomplete reception`() {
        val calls = AtomicInteger()
        withServer(onUpload = { calls.incrementAndGet(); "ok" }) { server, dir ->
            Socket("127.0.0.1", server.port).use { first ->
                first.soTimeout = 3000
                first.getOutputStream().apply {
                    write(uploadHeader(server, "100").toByteArray(StandardCharsets.US_ASCII))
                    write(byteArrayOf(1, 2))
                    flush()
                }
                await { dir.listFiles().orEmpty().isNotEmpty() }
                assertEquals(409, exchange(server, uploadHeader(server, "1"), byteArrayOf(1)).status)
                server.close()
                await { dir.listFiles().orEmpty().isEmpty() }
                assertEquals(-1, first.getInputStream().read())
                assertEquals(0, calls.get())
                assertEquals(0, server.port)
            }
        }
    }

    @Test
    fun `closing disconnects both active and queued clients`() {
        withServer { server, _ ->
            val clients = mutableListOf<Socket>()
            try {
                repeat(8) {
                    clients.add(Socket("127.0.0.1", server.port).apply { soTimeout = 3000 })
                }
                server.close()
                clients.forEach { socket ->
                    try {
                        assertEquals(-1, socket.getInputStream().read())
                    } catch (_: SocketException) {
                        // A connection still in the accept backlog may be reset instead of EOF.
                    }
                }
            } finally {
                clients.forEach { it.close() }
            }
        }
    }

    @Test
    fun `closing during coordinator handoff preserves accepted file`() {
        val receiver = AtomicReference<ApkUploadServer>()
        val received = AtomicReference<File>()
        val callbackFinished = CountDownLatch(1)
        withServer(onUpload = { file ->
            received.set(file)
            requireNotNull(receiver.get()).close()
            callbackFinished.countDown()
            "ok"
        }) { server, _ ->
            receiver.set(server)
            val bytes = byteArrayOf(1, 2, 3, 4)
            Socket("127.0.0.1", server.port).use { socket ->
                socket.getOutputStream().apply {
                    write(uploadHeader(server, bytes.size.toString()).toByteArray(StandardCharsets.US_ASCII))
                    write(bytes)
                    flush()
                }
                assertTrue(callbackFinished.await(3, TimeUnit.SECONDS))
                assertArrayEquals(bytes, requireNotNull(received.get()).readBytes())
            }
        }
    }

    @Test
    fun `rejects oversized headers and unsupported methods`() {
        withServer { server, _ ->
            assertEquals(431, exchange(server, uploadHeader(server, "1", extra = "X-Large: ${"a".repeat(9000)}\r\n")).status)
            val options = "OPTIONS /upload HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n"
            assertEquals(405, exchange(server, options).status)
        }
    }

    private fun uploadHeader(server: ApkUploadServer, length: String, pin: String = PIN, extra: String = "") =
        "POST /upload HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n" +
            "Content-Type: application/octet-stream\r\nContent-Length: $length\r\n" +
            "X-Astra-Pin: $pin\r\n$extra\r\n"

    private fun exchange(server: ApkUploadServer, header: String, body: ByteArray = byteArrayOf()): Response {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().apply {
                write(header.toByteArray(StandardCharsets.US_ASCII))
                write(body)
                flush()
            }
            socket.shutdownOutput()
            val response = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            val headers = response.substringBefore("\r\n\r\n")
            return Response(headers.substringBefore("\r\n").split(' ')[1].toInt(), headers, response.substringAfter("\r\n\r\n"))
        }
    }

    private fun withServer(onUpload: (File) -> String = { "ok" }, test: (ApkUploadServer, File) -> Unit) {
        val dir = temporaryFolder.newFolder()
        ApkUploadServer(dir, PIN, onUpload).use { server ->
            server.start(0)
            test(server, dir)
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("Condition did not become true", condition())
    }

    private data class Response(val status: Int, val headers: String, val body: String)

    companion object {
        private const val PIN = "573812"
    }
}
