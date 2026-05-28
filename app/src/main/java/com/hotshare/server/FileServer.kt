package com.hotshare.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        private const val TRASH_DIR_NAME = ".hotshare_trash"
    }

    // 回收站目录
    private val trashDir: File get() = File(storageDir, TRASH_DIR_NAME)

    /** 活跃连接追踪 IP → 最后活动时间戳 */
    private val activeClients = ConcurrentHashMap<String, Long>()
    private val connectionCleanupThreshold = 30_000L // 30 秒无活动视为断开

    // 缓存 Web UI 资源，提高性能
    private val staticCache = mutableMapOf<String, String>()

    init {
        val wifiIp = com.hotshare.util.NetworkUtil.getWlanIpAddress(appContext)
        Log.i(TAG, "服务器绑定到 $wifiIp:$port → 仅限 WLAN，零蜂窝流量")
    }

    // ====== 连接追踪 ======

    /**
     * 记录客户端连接
     */
    private fun trackConnection(session: IHTTPSession) {
        val ip = session.remoteIpAddress ?: return
        val now = System.currentTimeMillis()
        activeClients[ip] = now
    }

    /**
     * 获取活跃连接列表（清理超时的旧连接）
     */
    fun getActiveConnections(): List<String> {
        val now = System.currentTimeMillis()
        val threshold = now - connectionCleanupThreshold
        // 移除超时连接
        val staleIps = activeClients.filter { it.value < threshold }.keys
        staleIps.forEach { activeClients.remove(it) }
        return activeClients.keys.toList()
    }

    /**
     * 获取连接数
     */
    fun getConnectionCount(): Int = getActiveConnections().size

    override fun serve(session: IHTTPSession): Response {
        // 记录客户端连接
        trackConnection(session)

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
                val connections = getActiveConnections()
                val json = """
                    {
                        "status": "ok",
                        "name": "HotShare",
                        "ip": "$ip",
                        "port": $listeningPort,
                        "storage": "${storageDir.absolutePath}",
                        "version": "1.1.0",
                        "connected": ${connections.size},
                        "connections": [${connections.joinToString(",") { "\"$it\"" }}]
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

            // ========== 批量下载 ZIP ==========
            method == Method.GET && uri.startsWith("/api/download-zip") -> {
                handleZipDownload(session)
            }

            // ========== 内联预览（浏览器直接展示） ==========
            method == Method.GET && uri.startsWith("/api/preview/") -> {
                val fileName = URLDecoder.decode(uri.removePrefix("/api/preview/"), "UTF-8")
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
                // inline = 浏览器直接展示（图片/视频/PDF等）而非下载
                val rfc5987Name = rfc5987Encode(fileName)
                val asciiSafeName = fileName.replace(Regex("[^\\x20-\\x7E]"), "_")
                response.addHeader(
                    "Content-Disposition",
                    "inline; filename=\"$asciiSafeName\"; filename*=UTF-8''$rfc5987Name"
                )
                response.addHeader("Accept-Ranges", "bytes")
                response
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
                val rfc5987Name = rfc5987Encode(fileName)
                val asciiSafeName = fileName.replace(Regex("[^\\x20-\\x7E]"), "_")
                response.addHeader(
                    "Content-Disposition",
                    "attachment; filename=\"$asciiSafeName\"; filename*=UTF-8''$rfc5987Name"
                )
                response.addHeader("Accept-Ranges", "bytes")
                response
            }

            // ========== 删除文件 → 移入回收站 ==========
            method == Method.DELETE && uri.startsWith("/api/files/") -> {
                val fileName = URLDecoder.decode(uri.removePrefix("/api/files/"), "UTF-8")
                handleTrashFile(fileName)
            }

            // ========== 连接状态 ==========
            method == Method.GET && uri == "/api/connections" -> {
                val connections = getActiveConnections()
                val now = System.currentTimeMillis()
                val json = connections.map { ip ->
                    """{"ip":"$ip","lastSeen":${activeClients[ip] ?: now},"connected":true}"""
                }.joinToString(",\n")
                newFixedLengthResponse(
                    Response.Status.OK, "application/json; charset=utf-8",
                    """{"count":${connections.size},"clients":[$json]}"""
                )
            }

            // ========== 回收站管理 ==========
            // GET /api/trash → 列出回收站
            method == Method.GET && uri == "/api/trash" -> {
                handleListTrash()
            }

            // POST /api/trash/restore/文件名 → 恢复文件
            method == Method.POST && uri.startsWith("/api/trash/restore/") -> {
                val fileName = URLDecoder.decode(uri.removePrefix("/api/trash/restore/"), "UTF-8")
                handleRestoreTrash(fileName)
            }

            // DELETE /api/trash/empty → 清空回收站
            // DELETE /api/trash/文件名 → 永久删除单个
            method == Method.DELETE && uri.startsWith("/api/trash") -> {
                when {
                    uri == "/api/trash/empty" -> handleEmptyTrash()
                    uri.startsWith("/api/trash/") -> {
                        val fileName = URLDecoder.decode(uri.removePrefix("/api/trash/"), "UTF-8")
                        handlePermanentDelete(fileName)
                    }
                    else -> newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, "application/json",
                        """{"error":"invalid trash operation"}"""
                    )
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
     * 处理文件上传 (multipart/form-data) — 流式写入磁盘，不占内存
     *
     * ⚠️ 不依赖 NanoHTTPD 的 parseBody()（它对二进制文件使用字符流，会损坏数据）。
     * 改为直接从原始 InputStream 读取字节流并流式写入文件。
     */
    private fun handleUpload(session: IHTTPSession): Response {
        try {
            val headers = session.headers ?: emptyMap()

            val ct = headers["content-type"] ?: headers["Content-Type"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"error":"缺少 Content-Type"}"""
            )
            val boundary = parseBoundary(ct) ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"error":"无法识别 multipart boundary"}"""
            )

            val contentLength = (headers["content-length"] ?: headers["Content-Length"])
                ?.toLongOrNull()
                ?: return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"error":"缺少 Content-Length"}"""
                )

            val boundaryBytes = "--$boundary".toByteArray(Charsets.UTF_8)
            val doubleCrlf = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

            // 1. 查找第一个 boundary（跳过 preamble）
            val preambleEnd = findBoundaryInStream(session.inputStream, boundaryBytes, contentLength)
            if (preambleEnd < 0) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"error":"无效的 multipart 格式"}"""
                )
            }

            // 2. 读取头部（直到 \r\n\r\n），提取 filename
            val headerResult = readMultipartHeaders(session.inputStream, doubleCrlf)
            if (headerResult == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"error":"无法解析 multipart 头部"}"""
                )
            }

            val (headerBytes, headerTotalRead) = headerResult
            val headerSection = headerBytes.toString(Charsets.UTF_8)
            val filenameRegex = Regex("""filename="([^"]*)"""", RegexOption.IGNORE_CASE)
            val fileName = filenameRegex.find(headerSection)?.groupValues?.getOrNull(1)
                ?: return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"error":"请求中没有文件名"}"""
                )

            val safeName = com.hotshare.util.FileUtil.safeFileName(fileName)
            val destFile = resolveDestFile(safeName)

            // 3. 流式读取文件内容，边读边写入磁盘
            val headerPlusPreamble = preambleEnd + headerTotalRead
            val remainingStream = contentLength - headerPlusPreamble

            val fileSize = streamFileContent(session.inputStream, destFile, boundaryBytes, remainingStream)
            if (fileSize < 0) {
                // 写入可能不完整，但文件已部分存在
                Log.w(TAG, "文件写入可能不完整: $safeName")
            }

            Log.i(TAG, "📁 文件已接收: ${destFile.name} ($fileSize bytes, 流式写入)")

            val json = """{"status":"ok","name":"${escapeJson(destFile.name)}","size":$fileSize}"""
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

    // ====== 流式 Multipart 解析（不加载整个文件到内存）======

    /**
     * 在输入流中查找 boundary 标记，返回已消耗的字节数
     */
    private fun findBoundaryInStream(input: java.io.InputStream, boundaryBytes: ByteArray, maxBytes: Long): Long {
        val buffer = ByteArray(boundaryBytes.size)
        var totalRead = 0L

        while (totalRead < maxBytes) {
            // 逐字节扫描（小 boundary 时足够快）
            val b = input.read()
            if (b < 0) return -1
            totalRead++

            if (b == boundaryBytes[0].toInt()) {
                // 可能匹配到 boundary
                buffer[0] = b.toByte()
                var matched = 1
                while (matched < boundaryBytes.size && totalRead < maxBytes) {
                    val next = input.read()
                    if (next < 0) return -1
                    totalRead++
                    buffer[matched] = next.toByte()
                    if (buffer[matched] != boundaryBytes[matched]) {
                        matched = -1
                        break
                    }
                    matched++
                }
                if (matched == boundaryBytes.size) {
                    // 跳过 boundary 后的 \r\n
                    val cr = input.read()
                    val lf = input.read()
                    if (cr == '\r'.code.toInt() && lf == '\n'.code.toInt()) {
                        totalRead += 2
                    }
                    return totalRead
                }
            }
        }
        return -1
    }

    /**
     * 读取 multipart 头部到 \r\n\r\n 结束
     * 返回 (header 字节数组, 已读取总字节数)
     */
    private fun readMultipartHeaders(input: java.io.InputStream, delimiter: ByteArray): Pair<ByteArray, Long>? {
        val headerList = java.util.ArrayList<Byte>()
        val matchBuffer = ByteArray(4)
        var matchPos = 0
        var totalRead = 0L

        while (true) {
            val b = input.read()
            if (b < 0) return null
            totalRead++
            headerList.add(b.toByte())

            // 检测 \r\n\r\n
            matchBuffer[matchPos] = b.toByte()
            matchPos = (matchPos + 1) % 4

            if (headerList.size >= 4) {
                val last4 = headerList.subList(headerList.size - 4, headerList.size)
                if (last4[0] == '\r'.code.toByte() && last4[1] == '\n'.code.toByte() &&
                    last4[2] == '\r'.code.toByte() && last4[3] == '\n'.code.toByte()) {
                    val headerBytes = headerList.dropLast(4).toByteArray()
                    return Pair(headerBytes, totalRead)
                }
            }
        }
    }

    /**
     * 流式读取文件内容，直接写入磁盘，直到遇到下一个 boundary
     * 返回写入的文件大小
     */
    private fun streamFileContent(input: java.io.InputStream, destFile: File, boundaryBytes: ByteArray, maxBytes: Long): Long {
        val buffer = ByteArray(65536) // 64KB buffer
        var written = 0L
        var remaining = maxBytes

        destFile.outputStream().use { output ->
            // 缓冲区用于检测 boundary
            val boundaryBuffer = ByteArray(boundaryBytes.size)
            var boundaryMatchCount = 0
            var boundaryMatched = false

            while (remaining > 0 && !boundaryMatched) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) break

                // 扫描读入的数据中是否有 boundary
                var writeStart = 0
                for (i in 0 until read) {
                    val b = buffer[i]

                    if (b == boundaryBytes[0] && boundaryMatchCount == 0) {
                        // 可能开始匹配 boundary
                        boundaryMatchCount = 1
                        boundaryBuffer[0] = b
                    } else if (boundaryMatchCount > 0) {
                        if (boundaryMatchCount < boundaryBytes.size && b == boundaryBytes[boundaryMatchCount]) {
                            boundaryBuffer[boundaryMatchCount] = b
                            boundaryMatchCount++
                        } else {
                            // 匹配失败，先把已缓冲的匹配字节写出去
                            if (boundaryMatchCount > 0) {
                                output.write(boundaryBuffer, 0, boundaryMatchCount)
                                written += boundaryMatchCount
                                boundaryMatchCount = 0
                            }
                            // 当前字节也需要检查是否为新的可能匹配开始
                            if (b == boundaryBytes[0]) {
                                boundaryMatchCount = 1
                                boundaryBuffer[0] = b
                            }
                        }
                    }

                    if (boundaryMatchCount == boundaryBytes.size) {
                        // 完整的 boundary 匹配到了！写入前面的数据（不含 boundary）
                        output.write(buffer, writeStart, i - writeStart - boundaryBytes.size + 1)
                        written += (i - writeStart - boundaryBytes.size + 1)
                        boundaryMatched = true
                        break
                    }
                }

                if (!boundaryMatched) {
                    // 写入所有已读取但不包含 partial boundary 的数据
                    val writeEnd = if (boundaryMatchCount > 0) read - boundaryMatchCount else read
                    if (writeEnd > writeStart) {
                        output.write(buffer, writeStart, writeEnd - writeStart)
                        written += (writeEnd - writeStart)
                    }
                }

                remaining -= read
            }
        }

        return written
    }

    /**
     * 解析目标文件路径，同名自动重命名
     */
    private fun resolveDestFile(safeName: String): File {
        val destFile = File(storageDir, safeName)
        return if (destFile.exists()) {
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

    // ====== 回收站操作 ======

    /**
     * 删除文件 — 移入回收站而非永久删除
     */
    private fun handleTrashFile(fileName: String): Response {
        val safeName = com.hotshare.util.FileUtil.safeFileName(fileName)
        val file = File(storageDir, safeName)
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"error":"not found"}"""
            )
        }

        // 确保回收站目录存在
        if (!trashDir.exists()) trashDir.mkdirs()

        // 重名处理：避免覆盖已有回收文件
        val trashFile = resolveTrashName(safeName)

        // 移动文件到回收站
        if (file.renameTo(trashFile)) {
            Log.i(TAG, "🗑️ 文件已移入回收站: $safeName → .trash/")
            return newFixedLengthResponse(
                Response.Status.OK, "application/json",
                """{"status":"trashed","name":"${escapeJson(safeName)}"}"""
            )
        } else {
            // renameTo 失败时 fallback 为复制+删除
            try {
                file.copyTo(trashFile, overwrite = false)
                file.delete()
                Log.i(TAG, "🗑️ 文件已复制到回收站: $safeName")
                return newFixedLengthResponse(
                    Response.Status.OK, "application/json",
                    """{"status":"trashed","name":"${escapeJson(safeName)}"}"""
                )
            } catch (e: Exception) {
                Log.e(TAG, "移入回收站失败", e)
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":"trash failed: ${escapeJson(e.message ?: "")}"}"""
                )
            }
        }
    }

    /**
     * 处理回收站中的重名
     */
    private fun resolveTrashName(originalName: String): File {
        val target = File(trashDir, originalName)
        if (!target.exists()) return target
        val base = originalName.substringBeforeLast(".")
        val ext = originalName.substringAfterLast(".", "")
        var n = 1
        var candidate: File
        do {
            candidate = File(trashDir, "${base}_trashed_($n).${ext}")
            n++
        } while (candidate.exists())
        return candidate
    }

    /**
     * 列出回收站文件
     */
    private fun handleListTrash(): Response {
        if (!trashDir.exists()) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]")
        }
        val files = trashDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val encodedName = URLEncoder.encode(file.name, "UTF-8")
                // 尝试还原原始文件名（去掉 _trashed_(n) 后缀）
                val originalName = deTrashName(file.name)
                """{
                    "name":"${escapeJson(file.name)}",
                    "originalName":"${escapeJson(originalName)}",
                    "size":${file.length()},
                    "mtime":${file.lastModified()},
                    "encodedName":"$encodedName",
                    "encodedOriginal":"${URLEncoder.encode(originalName, "UTF-8")}"
                }""".trimIndent()
            }
            ?.joinToString(",\n") ?: ""
        val json = "[\n$files\n]"
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    /**
     * 从 trash 文件名还原原始文件名
     */
    private fun deTrashName(trashName: String): String {
        // 去掉 _trashed_(n) 后缀
        return trashName.replace(Regex("_trashed_\\(\\d+\\)\\..+"), "") // don't strip ext actually
            .let {
                // 更好的方式：_trashed_(n) 出现在 ext 之前
                val idx = it.indexOf("_trashed_(")
                if (idx >= 0) {
                    val ext = it.substringAfterLast(".")
                    it.substring(0, idx) + "." + ext
                } else {
                    it
                }
            }
    }

    /**
     * 从回收站恢复文件
     */
    private fun handleRestoreTrash(encodedFileName: String): Response {
        val trashName = URLDecoder.decode(encodedFileName, "UTF-8")
        val trashFile = File(trashDir, com.hotshare.util.FileUtil.safeFileName(trashName))
        if (!trashFile.exists() || !trashFile.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"error":"trash item not found"}"""
            )
        }

        // 还原到原始名称
        val originalName = deTrashName(trashName)
        val restoreFile = File(storageDir, originalName)

        // 如果原始位置已有同名文件，自动重命名
        val finalRestore = if (restoreFile.exists()) {
            val base = originalName.substringBeforeLast(".")
            val ext = originalName.substringAfterLast(".", "")
            var n = 1
            var candidate: File
            do {
                candidate = File(storageDir, "${base}_restored($n).${ext}")
                n++
            } while (candidate.exists())
            candidate
        } else {
            restoreFile
        }

        if (trashFile.renameTo(finalRestore)) {
            Log.i(TAG, "♻️ 文件已从回收站恢复: $trashName → ${finalRestore.name}")
            return newFixedLengthResponse(
                Response.Status.OK, "application/json",
                """{"status":"restored","name":"${escapeJson(finalRestore.name)}"}"""
            )
        } else {
            try {
                trashFile.copyTo(finalRestore, overwrite = false)
                trashFile.delete()
                return newFixedLengthResponse(
                    Response.Status.OK, "application/json",
                    """{"status":"restored","name":"${escapeJson(finalRestore.name)}"}"""
                )
            } catch (e: Exception) {
                Log.e(TAG, "恢复文件失败", e)
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":"restore failed: ${escapeJson(e.message ?: "")}"}"""
                )
            }
        }
    }

    /**
     * 永久删除回收站中的单个文件
     */
    private fun handlePermanentDelete(encodedFileName: String): Response {
        val trashName = URLDecoder.decode(encodedFileName, "UTF-8")
        val trashFile = File(trashDir, com.hotshare.util.FileUtil.safeFileName(trashName))
        if (!trashFile.exists() || !trashFile.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"error":"not found"}"""
            )
        }
        trashFile.delete()
        Log.i(TAG, "🗑️ 已永久删除: $trashName")
        return newFixedLengthResponse(
            Response.Status.OK, "application/json",
            """{"status":"permanently_deleted"}"""
        )
    }

    /**
     * 清空回收站
     */
    private fun handleEmptyTrash(): Response {
        if (!trashDir.exists()) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"already_empty"}""")
        }
        val files = trashDir.listFiles()?.filter { it.isFile } ?: emptyList()
        var deleted = 0
        for (f in files) {
            if (f.delete()) deleted++
        }
        Log.i(TAG, "🗑️ 回收站已清空: 删除了 $deleted 个文件")
        return newFixedLengthResponse(
            Response.Status.OK, "application/json",
            """{"status":"emptied","deleted":$deleted}"""
        )
    }

    // ====== ZIP 批量下载 ======

    /**
     * 从 Content-Type 头提取 boundary 字符串
     */
    private fun parseBoundary(contentType: String): String? {
        val regex = Regex("""boundary="?([^";\s]+)"?""", RegexOption.IGNORE_CASE)
        return regex.find(contentType)?.groupValues?.getOrNull(1)
    }

    /**
     * 处理 ZIP 批量下载
     * GET /api/download-zip?files=name1&files=name2
     */
    private fun handleZipDownload(session: IHTTPSession): Response {
        val params = session.parameters ?: emptyMap()
        val fileNames = params["files"]
            ?.filter { it.isNotBlank() }
            ?.map { URLDecoder.decode(it, "UTF-8") }
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"error":"no files specified"}"""
            )

        if (fileNames.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"error":"no files specified"}"""
            )
        }

        // 验证文件都存在
        val files = fileNames.mapNotNull { name ->
            val safe = com.hotshare.util.FileUtil.safeFileName(name)
            val f = File(storageDir, safe)
            if (f.exists() && f.isFile) f else null
        }

        if (files.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"error":"no valid files found"}"""
            )
        }

        // 写入临时文件，避免大文件占用内存
        try {
            val tempZip = File(storageDir, ".zip_${System.currentTimeMillis()}_${files.hashCode()}.tmp")
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(tempZip)).use { zos ->
                for (file in files) {
                    val entry = ZipEntry(file.name)
                    entry.size = file.length()
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            val response = newFixedLengthResponse(
                Response.Status.OK,
                "application/zip",
                java.io.FileInputStream(tempZip),
                tempZip.length()
            )
            val rfcName = rfc5987Encode("HotShare_${files.size}files.zip")
            response.addHeader(
                "Content-Disposition",
                "attachment; filename=\"HotShare_${files.size}files.zip\"; filename*=UTF-8''$rfcName"
            )
            // 发送完后清理临时文件
            tempZip.deleteOnExit()
            Log.i(TAG, "📦 ZIP 下载: ${files.size} 个文件, ${tempZip.length()} bytes (临时文件)")
            return response
        } catch (e: Exception) {
            Log.e(TAG, "ZIP 打包失败", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"error":"zip failed: ${escapeJson(e.message ?: "")}"}"""
            )
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
