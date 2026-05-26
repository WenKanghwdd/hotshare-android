package com.hotshare.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * NanoHTTPD 文件服务器
 *
 * 路由器：
 *   GET  /                  → Web UI 首页
 *   GET  /static/(*)         → 静态资源（JS/CSS）
 *   GET  /api/files         → JSON 文件列表
 *   POST /api/upload        → 上传文件（multipart/form-data）
 *   GET  /api/download/(*)  → 下载文件
 *   GET  /api/info          → 服务器信息
 */
class FileServer(
    private val appContext: Context,
    port: Int,
    private val storageDir: File
) : NanoHTTPD(com.hotshare.util.NetworkUtil.getWlanIpAddress(appContext), port) {

    companion object {
        private const val TAG = "FileServer"
    }

    // 缓存 Web UI 资源，提高性能
    private val staticCache = mutableMapOf<String, String>()

    init {
        val wifiIp = com.hotshare.util.NetworkUtil.getWlanIpAddress(appContext)
        Log.i(TAG, "服务器绑定到 $wifiIp:$port → 仅限 WLAN，零蜂窝流量")
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            handleRequest(session)
        } catch (e: Exception) {
            Log.e(TAG, "请求处理异常: ${session.uri}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MimeTypes.guess("error.txt"),
                "500 Internal Server Error"
            )
        }
    }

    private fun handleRequest(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            // ========== Web UI 首页 ==========
            method == Method.GET && (uri == "/" || uri == "") -> {
                serveStaticAsset("web/index.html")
            }

            // ========== 静态资源 ==========
            method == Method.GET && uri.startsWith("/static/") -> {
                val assetPath = "web/${uri.removePrefix("/static/")}"
                serveStaticAsset(assetPath)
            }

            // ========== 服务器信息 ==========
            method == Method.GET && uri == "/api/info" -> {
                val ip = com.hotshare.util.NetworkUtil.getWlanIpAddress(appContext)
                val json = """
                    {
                        "status": "ok",
                        "name": "HotShare",
                        "ip": "$ip",
                        "port": $listeningPort,
                        "storage": "${storageDir.absolutePath}",
                        "version": "1.0.0"
                    }
                """.trimIndent()
                newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
            }

            // ========== 文件列表 ==========
            method == Method.GET && uri == "/api/files" -> {
                val files = storageDir.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { file ->
                        val encodedName = URLEncoder.encode(file.name, "UTF-8")
                        """{"name":"${escapeJson(file.name)}","size":${file.length()},"mtime":${file.lastModified()},"encodedName":"$encodedName"}"""
                    }
                    ?.joinToString(",\n") ?: ""
                val json = "[\n$files\n]"
                newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
            }

            // ========== 上传文件 ==========
            method == Method.POST && uri == "/api/upload" -> {
                handleUpload(session)
            }

            // ========== 下载文件 ==========
            method == Method.GET && uri.startsWith("/api/download/") -> {
                val fileName = URLDecoder.decode(uri.removePrefix("/api/download/"), "UTF-8")
                val file = File(storageDir, com.hotshare.util.FileUtil.safeFileName(fileName))
                if (!file.exists() || !file.isFile) {
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain",
                        "404 Not Found"
                    )
                }
                val mime = MimeTypes.guess(fileName)
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mime,
                    FileInputStream(file),
                    file.length()
                )
                // 强制下载
                // filename= 给旧客户端降级，filename*= 用于中文/特殊字符（RFC 5987）
                val rfc5987Name = rfc5987Encode(fileName)
                val asciiSafeName = fileName.replace(Regex("[^\\x20-\\x7E]"), "_")
                response.addHeader(
                    "Content-Disposition",
                    "attachment; filename=\"$asciiSafeName\"; filename*=UTF-8''$rfc5987Name"
                )
                response.addHeader("Accept-Ranges", "bytes")
                response
            }

            // ========== 删除文件 ==========
            method == Method.DELETE && uri.startsWith("/api/files/") -> {
                val fileName = URLDecoder.decode(uri.removePrefix("/api/files/"), "UTF-8")
                val file = File(storageDir, com.hotshare.util.FileUtil.safeFileName(fileName))
                if (file.exists() && file.isFile) {
                    file.delete()
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"deleted"}""")
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
                }
            }

            else -> {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain; charset=utf-8",
                    "404 — 未找到该资源"
                )
            }
        }
    }

    /**
     * 处理文件上传 (multipart/form-data)
     *
     * ⚠️ 不依赖 NanoHTTPD 的 parseBody()（它对二进制文件使用字符流，会损坏数据）。
     * 改为直接从原始 InputStream 读取字节流，手动解析 multipart boundary。
     */
    private fun handleUpload(session: IHTTPSession): Response {
        try {
            val headers = session.headers ?: emptyMap()

            // 从 Content-Type 中提取 boundary
            val ct = headers["content-type"] ?: headers["Content-Type"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"error":"缺少 Content-Type"}"""
            )
            val boundary = parseBoundary(ct) ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"error":"无法识别 multipart boundary"}"""
            )

            // 读取 Content-Length 指定量的原始字节（不能用 readBytes 死等 EOF）
            val contentLength = (headers["content-length"] ?: headers["Content-Length"])
                ?.toLongOrNull()
                ?: return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"error":"缺少 Content-Length"}"""
                )
            val bodyBytes = ByteArray(contentLength.toInt())
            var offset = 0
            while (offset < contentLength) {
                val read = session.inputStream.read(bodyBytes, offset, (contentLength - offset).toInt())
                if (read < 0) {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, "application/json",
                        """{"error":"请求体意外结束"}"""
                    )
                }
                offset += read
            }

            // 解析 multipart，提取文件名和文件内容
            val result = parseMultipartBinary(bodyBytes, boundary)
            if (result == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"error":"无法从请求中提取文件"}"""
                )
            }

            val (fileName, fileBytes) = result
            val safeName = com.hotshare.util.FileUtil.safeFileName(fileName)
            val destFile = File(storageDir, safeName)

            // 同名文件自动重命名
            val finalFile = if (destFile.exists()) {
                val base = safeName.substringBeforeLast(".")
                val ext = safeName.substringAfterLast(".", "")
                var n = 1
                var candidate: File
                do {
                    candidate = File(storageDir, "${base}_($n).${ext}")
                    n++
                } while (candidate.exists())
                candidate
            } else {
                destFile
            }

            // 直接写入字节数组（二进制安全）
            finalFile.writeBytes(fileBytes)

            Log.i(TAG, "文件已接收: ${finalFile.name} (${finalFile.length()} bytes)")

            val json = """{"status":"ok","name":"${escapeJson(finalFile.name)}","size":${finalFile.length()}}"""
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)

        } catch (e: Exception) {
            Log.e(TAG, "上传处理异常", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"上传失败: ${escapeJson(e.message ?: "未知错误")}"}"""
            )
        }
    }

    // ====== Multipart 二进制解析 ======

    /**
     * 从 Content-Type 头提取 boundary 字符串
     */
    private fun parseBoundary(contentType: String): String? {
        // boundary=xxxxx 或 boundary="xxxx"
        val regex = Regex("""boundary="?([^";\s]+)"?""", RegexOption.IGNORE_CASE)
        return regex.find(contentType)?.groupValues?.getOrNull(1)
    }

    private fun parseMultipartBinary(body: ByteArray, boundary: String): Pair<String, ByteArray>? {
        val boundaryBytes = "--$boundary".toByteArray(Charsets.UTF_8)
        val boundaryEndBytes = "--$boundary--".toByteArray(Charsets.UTF_8)
        val doubleCrlf = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

        // 查找第一个 boundary（跳过 preamble）
        var pos = indexOf(body, boundaryBytes, 0)
        if (pos < 0) return null
        pos += boundaryBytes.size

        // 跳过第一个 boundary 后面的 \r\n
        if (pos + 1 < body.size && body[pos] == '\r'.code.toByte() && body[pos + 1] == '\n'.code.toByte()) {
            pos += 2
        }

        while (pos < body.size) {
            // 查找下一个 boundary 或结束 boundary
            val nextBoundary = indexOf(body, boundaryBytes, pos)
            val endBoundary = indexOf(body, boundaryEndBytes, pos)
            val partEnd = when {
                nextBoundary >= 0 && endBoundary >= 0 -> minOf(nextBoundary, endBoundary)
                nextBoundary >= 0 -> nextBoundary
                endBoundary >= 0 -> endBoundary
                else -> body.size
            }

            // 该 part 的范围: [pos, partEnd)
            val partBytes = body.copyOfRange(pos, partEnd)

            // 解析 part: 查找 \r\n\r\n 分隔头部和内容
            val headerEnd = indexOf(partBytes, doubleCrlf, 0)
            if (headerEnd < 0) {
                // 没有内容部分，跳到下一个
                pos = partEnd + boundaryBytes.size
                if (pos + 1 < body.size && body[pos] == '\r'.code.toByte() && body[pos + 1] == '\n'.code.toByte()) {
                    pos += 2
                }
                continue
            }

            val headerSection = partBytes.copyOfRange(0, headerEnd).toString(Charsets.UTF_8)
            val contentBytes = partBytes.copyOfRange(headerEnd + doubleCrlf.size, partBytes.size)

            // 检查 Content-Disposition 是否包含文件字段
            // 提取 filename="xxx" 或 filename*=UTF-8''xxx
            val filenameRegex = Regex("""filename="([^"]*)"""", RegexOption.IGNORE_CASE)
            val filenameMatch = filenameRegex.find(headerSection)
            val fileName = if (filenameMatch != null) {
                filenameMatch.groupValues[1]
            } else {
                // 尝试 filename*=UTF-8''xxx
                val rfc5987Regex = Regex("""filename\*\s*=\s*UTF-8''(.+?)(?:;|\s*$)""", RegexOption.IGNORE_CASE)
                val rfc5987Match = rfc5987Regex.find(headerSection)
                rfc5987Match?.groupValues?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
            }

            if (fileName != null && fileName.isNotEmpty()) {
                // 去掉尾随的 \r\n（multipart 规范约定内容后跟 \r\n + boundary）
                val content = if (contentBytes.size >= 2 &&
                    contentBytes[contentBytes.size - 2] == '\r'.code.toByte() &&
                    contentBytes[contentBytes.size - 1] == '\n'.code.toByte()
                ) {
                    contentBytes.copyOfRange(0, contentBytes.size - 2)
                } else {
                    contentBytes
                }
                return Pair(fileName, content)
            }

            // 不是文件字段，继续找下一个
            pos = partEnd + boundaryBytes.size
            if (pos + 1 < body.size && body[pos] == '\r'.code.toByte() && body[pos + 1] == '\n'.code.toByte()) {
                pos += 2
            }
        }

        return null
    }

    /**
     * 在字节数组中查找子数组（类似 String.indexOf）
     */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, startPos: Int): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        val end = haystack.size - needle.size
        for (i in startPos..end) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    /**
     * 从 assets 目录提供静态资源
     */
    private fun serveStaticAsset(assetPath: String): Response {
        try {
            // 先从缓存读取
            val cached = staticCache[assetPath]
            if (cached != null) {
                val mime = guessAssetMime(assetPath)
                return newFixedLengthResponse(Response.Status.OK, mime, cached)
            }

            // 从 assets 读取
            val inputStream = appContext.assets.open(assetPath)
            val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            // 缓存非大文件
            if (content.length < 500_000) {
                staticCache[assetPath] = content
            }

            val mime = guessAssetMime(assetPath)
            return newFixedLengthResponse(Response.Status.OK, mime, content)

        } catch (e: Exception) {
            Log.w(TAG, "资源未找到: $assetPath", e)
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/html; charset=utf-8",
                "<h1>404 - 资源未找到</h1><p>$assetPath</p>"
            )
        }
    }

    private fun guessAssetMime(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".css")  -> "text/css; charset=utf-8"
            path.endsWith(".js")   -> "application/javascript; charset=utf-8"
            else                   -> "text/plain"
        }
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * RFC 5987 百分号编码 — 用于 Content-Disposition filename*
     *
     * URLEncoder.encode 把空格变成 +（RFC 5987 要求 %20），
     * 所以在这里做一次修正。
     */
    private fun rfc5987Encode(s: String): String {
        return URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
    }
}
