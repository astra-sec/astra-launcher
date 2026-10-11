package org.astrasec.launcher.apkupload

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A short-lived, PIN-protected HTTP receiver. The caller owns successfully received files. */
class ApkUploadServer(
    private val cacheDir: File,
    private val pin: String,
    private val onUpload: (File) -> String
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val uploading = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val incompleteFiles = ConcurrentHashMap.newKeySet<File>()
    private val failedPinAttempts = ArrayDeque<Long>()
    private val workers = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "astra-apk-upload").apply { isDaemon = true } }
    )
    @Volatile private var listener: ServerSocket? = null

    val port: Int
        get() = listener?.localPort?.takeIf { it > 0 } ?: 0

    init {
        require(pin.length in 4..64) { "接收码长度必须为 4 至 64 个字符" }
    }

    @Synchronized
    fun start(port: Int = 8080): Int {
        check(!closed.get()) { "接收服务已经关闭" }
        listener?.let { return it.localPort }
        require(cacheDir.isDirectory || cacheDir.mkdirs()) { "无法创建接收目录" }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            listener = socket
            Thread({ acceptConnections(socket) }, "astra-apk-listener").apply {
                isDaemon = true
                start()
            }
            return socket.localPort
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    private fun acceptConnections(socket: ServerSocket) {
        while (!closed.get()) {
            val connection = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            synchronized(this) {
                // Registration and submission must finish before close snapshots the sockets.
                if (closed.get()) {
                    connection.close()
                } else {
                    try {
                        connection.soTimeout = 30_000
                        connections.add(connection)
                        workers.execute { handleConnection(connection) }
                    } catch (_: RejectedExecutionException) {
                        connections.remove(connection)
                        connection.close()
                    } catch (_: IOException) {
                        connections.remove(connection)
                        connection.close()
                    }
                }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val request = readRequest(input)
                validateHostAndOrigin(request)
                when {
                    request.method == "GET" && request.path == "/" -> {
                        if (request.headers.containsKey("content-length") && request.headers["content-length"] != "0") {
                            throw HttpError(400, "GET 请求不能包含正文")
                        }
                        respond(socket, 200, UploadPage.HTML, "text/html; charset=utf-8")
                    }
                    request.method == "POST" && request.path == "/upload" -> receiveApk(socket, input, request)
                    request.method !in listOf("GET", "POST") -> throw HttpError(405, "不支持此请求方法")
                    else -> throw HttpError(404, "没有此页面")
                }
            } catch (error: HttpError) {
                respondSafely(socket, error.status, error.message ?: "请求失败")
            } catch (_: SocketTimeoutException) {
                respondSafely(socket, 408, "上传超时，请重新选择文件并重试")
            } catch (_: IOException) {
                // Disconnects are expected when the browser or receiving screen closes.
            } catch (_: Exception) {
                respondSafely(socket, 500, "接收失败，请在电视上重新开启接收")
            } finally {
                connections.remove(socket)
            }
        }
    }

    private fun receiveApk(socket: Socket, input: InputStream, request: Request) {
        authenticate(request.headers["x-astra-pin"] ?: "")
        val contentLength = request.headers["content-length"]
            ?: throw HttpError(411, "上传必须提供文件长度")
        if (!contentLength.matches(Regex("[0-9]+"))) throw HttpError(400, "文件长度无效")
        val length = contentLength.toLongOrNull() ?: throw HttpError(413, "APK 不能超过 256 MB")
        if (length <= 0) throw HttpError(400, "文件不能为空")
        if (length > MAX_APK_BYTES) throw HttpError(413, "APK 不能超过 256 MB")
        if (request.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) != "application/octet-stream") {
            throw HttpError(415, "请通过此页面选择 APK 文件上传")
        }
        if (!uploading.compareAndSet(false, true)) throw HttpError(409, "正在接收另一个 APK，请稍后重试")
        var file: File? = null
        var handedOff = false
        try {
            val incomingFile = synchronized(this) {
                if (closed.get()) throw HttpError(503, "接收已经停止")
                File.createTempFile("astra-received-", ".apk", cacheDir).also { incompleteFiles.add(it) }
            }
            file = incomingFile
            incomingFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    if (closed.get()) throw HttpError(503, "接收已经停止")
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count == -1) throw HttpError(400, "文件传输不完整，请重试")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            synchronized(this) {
                if (closed.get()) throw HttpError(503, "接收已经停止")
                // Once validation begins, close must not delete the completed file underneath it.
                incompleteFiles.remove(incomingFile)
            }
            // Run validation outside the lifecycle lock so closing the screen never waits for it.
            val result = try {
                onUpload(incomingFile)
            } catch (error: IllegalArgumentException) {
                throw HttpError(400, error.message?.take(500) ?: "无法识别此 APK")
            }
            handedOff = true
            respond(socket, 200, result)
        } finally {
            file?.let {
                incompleteFiles.remove(it)
                if (!handedOff) it.delete()
            }
            uploading.set(false)
        }
    }

    private fun authenticate(candidate: String) {
        synchronized(failedPinAttempts) {
            val now = System.nanoTime()
            while (failedPinAttempts.isNotEmpty() && now - failedPinAttempts.first > TimeUnit.MINUTES.toNanos(1)) {
                failedPinAttempts.removeFirst()
            }
            if (failedPinAttempts.size >= 8) throw HttpError(429, "接收码错误次数过多，请一分钟后重试")
            if (!MessageDigest.isEqual(pin.toByteArray(StandardCharsets.UTF_8), candidate.toByteArray(StandardCharsets.UTF_8))) {
                failedPinAttempts.addLast(now)
                throw HttpError(403, "接收码不正确，请查看电视上的接收码")
            }
        }
    }

    private fun readRequest(input: InputStream): Request {
        val requestLine = readLine(input, 2048)
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[2] !in listOf("HTTP/1.0", "HTTP/1.1")) throw HttpError(400, "请求格式无效")
        val headers = mutableMapOf<String, String>()
        var size = requestLine.length
        while (true) {
            val line = readLine(input, 8192)
            size += line.length + 2
            if (size > 16 * 1024 || headers.size > 48) throw HttpError(431, "请求头过长")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "请求头无效")
            val name = line.substring(0, colon).lowercase(Locale.ROOT)
            if (!name.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+"))) throw HttpError(400, "请求头无效")
            if (headers.put(name, line.substring(colon + 1).trim()) != null) throw HttpError(400, "请求头不能重复")
        }
        if (headers.containsKey("transfer-encoding")) throw HttpError(400, "不支持分块传输，请通过此页面上传")
        if (headers.containsKey("expect")) throw HttpError(417, "不支持此传输方式")
        return Request(parts[0], parts[1], headers)
    }

    private fun validateHostAndOrigin(request: Request) {
        val host = request.headers["host"] ?: throw HttpError(400, "缺少设备地址")
        val hostUri = try { URI("http://$host") } catch (_: Exception) { throw HttpError(400, "设备地址无效") }
        val rawAddress = hostUri.host?.removePrefix("[")?.removeSuffix("]")
            ?: throw HttpError(400, "请使用电视显示的 IP 地址")
        if (hostUri.rawAuthority != host || !hostUri.path.isNullOrEmpty() || hostUri.rawQuery != null ||
            hostUri.rawFragment != null || hostUri.userInfo != null || hostUri.port !in -1..65535
        ) throw HttpError(400, "设备地址无效")
        val isIpv4 = rawAddress.split('.').let { parts ->
            parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 }
        }
        val isIpv6 = rawAddress.contains(':') && rawAddress.all { it in "0123456789abcdefABCDEF:" || it == '.' }
        if (!isIpv4 && !isIpv6) throw HttpError(403, "请使用电视显示的局域网 IP 地址")
        val address = try { InetAddress.getByName(rawAddress) } catch (_: Exception) { throw HttpError(400, "设备地址无效") }
        if (!address.isLoopbackAddress && !address.isSiteLocalAddress && !address.isLinkLocalAddress &&
            !(address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc)
        ) throw HttpError(403, "仅支持局域网地址")
        request.headers["origin"]?.let { origin ->
            val originUri = try { URI(origin) } catch (_: Exception) { throw HttpError(403, "来源地址不匹配") }
            if (originUri.scheme != "http" || !originUri.rawAuthority.equals(host, ignoreCase = true) ||
                !originUri.path.isNullOrEmpty() || originUri.rawQuery != null || originUri.rawFragment != null
            ) throw HttpError(403, "请直接打开电视显示的网页地址")
        }
    }

    private fun readLine(input: InputStream, maximum: Int): String {
        val line = StringBuilder()
        while (line.length <= maximum) {
            val next = input.read()
            if (next == -1) throw HttpError(400, "请求不完整")
            if (next == '\r'.code) {
                if (input.read() != '\n'.code) throw HttpError(400, "请求格式无效")
                return line.toString()
            }
            if (next !in 32..126) throw HttpError(400, "请求头包含无效字符")
            line.append(next.toChar())
        }
        throw HttpError(431, "请求头过长")
    }

    private fun respondSafely(socket: Socket, status: Int, message: String) {
        try { respond(socket, status, message) } catch (_: IOException) { }
    }

    private fun respond(socket: Socket, status: Int, body: String, contentType: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Content Too Large"
            415 -> "Unsupported Media Type"
            417 -> "Expectation Failed"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(StandardCharsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { listener?.close() } catch (_: IOException) { }
        listener = null
        connections.forEach { try { it.close() } catch (_: IOException) { } }
        connections.clear()
        workers.shutdownNow()
        incompleteFiles.forEach { it.delete() }
        incompleteFiles.clear()
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>)
    private class HttpError(val status: Int, message: String) : Exception(message)

    companion object {
        const val MAX_APK_BYTES = 256L * 1024L * 1024L
    }
}
