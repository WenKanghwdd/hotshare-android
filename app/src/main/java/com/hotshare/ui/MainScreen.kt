package com.hotshare.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hotshare.service.HotspotService
import com.hotshare.util.FileUtil
import com.hotshare.util.NetworkUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(HotspotService.isRunning) }
    var serverIp by remember { mutableStateOf(HotspotService.serverIp) }
    var port by remember { mutableStateOf(HotspotService.port) }

    // 注册广播接收服务状态
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.getStringExtra("status")) {
                    "started" -> {
                        isRunning = true
                        serverIp = intent.getStringExtra("ip") ?: ""
                        port = intent.getIntExtra("port", 8080)
                    }
                    "stopped" -> { isRunning = false }
                    "error" -> { isRunning = false }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter("com.hotshare.SERVICE_STATUS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // 轮询状态
    LaunchedEffect(Unit) {
        while (true) {
            isRunning = HotspotService.isRunning
            serverIp = HotspotService.serverIp
            port = HotspotService.port
            kotlinx.coroutines.delay(1000)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7DAFDD),
            secondary = Color(0xFFD0ECF4),
            tertiary = Color(0xFF315BB8),
            background = Color(0xFF0D1B2A),
            surface = Color(0xFF1B2838),
            onPrimary = Color(0xFF0D1B2A),
            onSecondary = Color(0xFF0D1B2A),
            onBackground = Color(0xFFF8FAE7),
            onSurface = Color(0xFFF8FAE7),
            surfaceVariant = Color(0xFF243447),
            onSurfaceVariant = Color(0xFFD0ECF4),
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(40.dp))

                // 标题
                Text(
                    text = "🔥 HotShare",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "热点直连 · 零公网 · 高速传输",
                    fontSize = 14.sp,
                    color = Color(0xFFD0ECF4),
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 控制按钮
                Button(
                    onClick = {
                        if (isRunning) {
                            context.stopService(HotspotService.startIntent(context))
                        } else {
                            ContextCompat.startForegroundService(
                                context,
                                HotspotService.startIntent(context)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) Color(0xFF315BB8) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (isRunning) "⏹ 停止服务" else "▶ 启动服务",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isRunning) {
                    Spacer(modifier = Modifier.height(20.dp))

                    // === 连接信息卡片（含 QR 码） ===
                    val url = "http://$serverIp:$port"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 状态指示
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF7DAFDD), RoundedCornerShape(5.dp))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "服务运行中",
                                    fontSize = 14.sp,
                                    color = Color(0xFF7DAFDD)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // IP 地址（大号可点击复制）
                            Text(
                                text = "🌐 访问地址",
                                fontSize = 12.sp,
                                color = Color(0xFFD0ECF4)
                            )
                            Text(
                                text = url,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clickable {
                                        // 复制到剪贴板
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("HotShare URL", url)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "📋 已复制链接", Toast.LENGTH_SHORT).show()
                                    }
                            )
                            Text(
                                text = "点击复制链接",
                                fontSize = 11.sp,
                                color = Color(0xFF7DAFDD).copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 2.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // QR 码
                            val qrBitmap = remember(url) { generateQrCode(url, 400) }
                            if (qrBitmap != null) {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAE7))
                                ) {
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = "HotShare QR Code",
                                        modifier = Modifier
                                            .size(200.dp)
                                            .padding(8.dp)
                                    )
                                }
                                Text(
                                    text = "📱 Mac 相机扫码自动打开",
                                    fontSize = 12.sp,
                                    color = Color(0xFFD0ECF4),
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // === 连接设备数 ===
                            var connectionCount by remember { mutableStateOf(0) }
                            var connectionIps by remember { mutableStateOf(listOf<String>()) }

                            LaunchedEffect(isRunning) {
                                while (isRunning) {
                                    try {
                                        val conn = HotspotService.instance?.getConnectionInfo()
                                        connectionCount = conn?.first ?: 0
                                        connectionIps = conn?.second ?: emptyList()
                                    } catch (_: Exception) { }
                                    kotlinx.coroutines.delay(3000)
                                }
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF315BB8).copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val dotColor = if (connectionCount > 0) Color(0xFF7DAFDD) else Color(0xFF7DAFDD)
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(dotColor, RoundedCornerShape(4.dp))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when {
                                            connectionCount == 0 -> "等待设备连接..."
                                            connectionCount == 1 -> "1 台设备已连接"
                                            else -> "$connectionCount 台设备已连接"
                                        },
                                        fontSize = 13.sp,
                                        color = if (connectionCount > 0) Color(0xFFD0ECF4) else Color.Gray
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // 操作提示
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF1B2838)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp)
                                ) {
                                    Text(
                                        "📋 操作步骤",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "1️⃣ 确保 Mac 已连接手机热点 Wi-Fi\n" +
                                        "2️⃣ Mac 浏览器打开上方地址\n" +
                                        "3️⃣ 或用相机扫描二维码自动打开\n" +
                                        "4️⃣ 拖拽文件/文件夹到网页即可传输",
                                        fontSize = 12.sp,
                                        color = Color(0xFFD0ECF4),
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // === 扫描 Mac QR 码 ===
                    var showScanner by remember { mutableStateOf(false) }

                    if (showScanner) {
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = { showScanner = false },
                            properties = androidx.compose.ui.window.DialogProperties(
                                usePlatformDefaultWidth = false,
                                dismissOnBackPress = true,
                                dismissOnClickOutside = false
                            )
                        ) {
                            QRScannerScreen(
                                onScanResult = { url ->
                                    showScanner = false
                                    // 解析 hotshare:// 协议或直接取 URL
                                    val finalUrl = url
                                        .removePrefix("hotshare://mac?host=")
                                        .let {
                                            if (it.startsWith("http")) it
                                            else "http://$it"
                                        }
                                    // 在浏览器中打开
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(finalUrl))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "无法打开: $finalUrl", Toast.LENGTH_LONG).show()
                                    }
                                },
                                onClose = { showScanner = false }
                            )
                        }
                    }

                    // 扫码按钮（始终显示）
                    Button(
                        onClick = { showScanner = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF315BB8))
                    ) {
                        Text("📷 扫描 Mac QR 码", fontSize = 14.sp)
                    }

                    // 权限提示
                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "💡 首次使用扫码需要授予相机权限，系统会自动弹出授权请求",
                            fontSize = 11.sp,
                            color = Color(0xFF315BB8).copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // === 文件发送到 Mac ===
                    FileSendSection(context)

                    Spacer(modifier = Modifier.height(16.dp))

                    // === 服务器目录文件管理 ===
                    FileManagementSection(context)

                } else {
                    Spacer(modifier = Modifier.height(24.dp))

                    // 未启动时的引导
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "👆 点击上方按钮启动服务",
                                fontSize = 16.sp,
                                color = Color(0xFFD0ECF4)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "启动后，Mac 连接手机热点即可访问",
                                fontSize = 13.sp,
                                color = Color(0xFF7DAFDD)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 底部版本信息
                Text("HotShare v1.1.0", fontSize = 12.sp, color = Color(0xFF7DAFDD))
                Text("局域网直连 · 零蜂窝流量", fontSize = 11.sp, color = Color(0xFF7DAFDD))

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ====== 文件发送组件 ======

@Composable
private fun FileSendSection(context: Context) {
    val scope = rememberCoroutineScope()
    var copiedFiles by remember { mutableStateOf(listOf<String>()) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        scope.launch {
            val dir = FileUtil.getReceiveDir(context)
            val newFiles = mutableListOf<String>()
            for (uri in uris) {
                val copied = FileUtil.copyContentUriToDir(context, uri, dir)
                if (copied != null) newFiles.add(copied)
            }
            copiedFiles = newFiles
            if (newFiles.isNotEmpty()) {
                Toast.makeText(
                    context,
                    "📋 已复制 ${newFiles.size} 个文件到服务器目录，手机上的原文件已保留",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val recentlyCopied = remember { mutableStateOf(listOf<String>()) }

    Button(
        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF315BB8))
    ) {
        Text("📤 选择文件发送到 Mac", fontSize = 14.sp)
    }

    // 轮询最近复制的文件
    LaunchedEffect(Unit) {
        while (true) {
            val dir = FileUtil.getReceiveDir(context)
            val files = dir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(5)
                ?.map { "${it.name}  (${FileUtil.formatSize(it.length())})" }
                ?: emptyList()
            recentlyCopied.value = files
            kotlinx.coroutines.delay(3000)
        }
    }

    if (recentlyCopied.value.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2838)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("📄 最近发送", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFD0ECF4))
                Spacer(modifier = Modifier.height(4.dp))
                recentlyCopied.value.forEach { info ->
                    Text(info, fontSize = 11.sp, color = Color(0xFFD0ECF4), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    val dir = FileUtil.getReceiveDir(context)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "📁 保存到: ${dir.absolutePath}",
        fontSize = 11.sp,
        color = Color(0xFFD0ECF4),
        textAlign = TextAlign.Center
    )
}

// ====== 文件管理组件 ======

@Composable
private fun FileManagementSection(context: Context) {
    val scope = rememberCoroutineScope()
    val dir = FileUtil.getReceiveDir(context)
    var fileItems by remember { mutableStateOf(listOf<File>()) }

    // 轮询文件列表
    LaunchedEffect(Unit) {
        while (true) {
            fileItems = dir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            kotlinx.coroutines.delay(3000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "📂 文件管理",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${fileItems.size} 个文件",
                    fontSize = 12.sp,
                    color = Color(0xFFD0ECF4)
                )
            }

            if (fileItems.isEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("暂无文件", fontSize = 13.sp, color = Color(0xFFD0ECF4), modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                fileItems.take(15).forEach { file ->
                    FileRow(file, onDelete = {
                        scope.launch {
                            file.delete()
                            Toast.makeText(context, "🗑️ 已删除: ${file.name}", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
                if (fileItems.size > 15) {
                    Text(
                        "... 还有 ${fileItems.size - 15} 个文件",
                        fontSize = 11.sp,
                        color = Color(0xFFD0ECF4),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: File, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        val icon = when (file.extension.lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp" -> "🖼️"
            "mp4", "mov", "mkv" -> "🎬"
            "mp3", "wav", "aac" -> "🎵"
            "pdf" -> "📕"
            "zip", "rar", "gz" -> "📦"
            "apk" -> "📱"
            "txt", "json" -> "📝"
            else -> "📄"
        }
        Text(icon, fontSize = 16.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                FileUtil.formatSize(file.length()),
                fontSize = 10.sp,
                color = Color(0xFFD0ECF4)
            )
        }
        TextButton(
            onClick = onDelete,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("🗑️", fontSize = 14.sp)
        }
    }
}

// ====== QR 码生成 ======

/**
 * 使用 ZXing 生成 QR 码 Bitmap
 */
private fun generateQrCode(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bitmap
    } catch (e: Exception) {
        android.util.Log.e("HotShare", "QR code 生成失败", e)
        null
    }
}
