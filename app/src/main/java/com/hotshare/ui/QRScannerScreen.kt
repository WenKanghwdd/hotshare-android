package com.hotshare.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * QR 码扫描界面 — 扫描 Mac 端的二维码自动连接
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScannerScreen(
    onScanResult: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }

    var permissionState by remember { mutableStateOf(
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                -> PermissionState.GRANTED
            activity == null || !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity!!, Manifest.permission.CAMERA)
                -> PermissionState.DENIED_PERMANENTLY
            else -> PermissionState.DENIED_ONCE
        }
    ) }
    var scanResult by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 请求权限
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionState = if (granted) PermissionState.GRANTED
        else {
            if (activity == null || !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity!!, Manifest.permission.CAMERA)) {
                PermissionState.DENIED_PERMANENTLY
            } else {
                PermissionState.DENIED_ONCE
            }
        }
    }

    // 自动请求权限
    LaunchedEffect(Unit) {
        if (permissionState == PermissionState.DENIED_ONCE) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫描 Mac 端 QR 码", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    TextButton(onClick = onClose) {
                        Text("✕ 关闭", color = Color.Gray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF315BB8),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0D1B2A)),
            contentAlignment = Alignment.Center
        ) {

            when (permissionState) {
                PermissionState.GRANTED -> {
                    CameraPreview(onQrDetected = { code ->
                        if (scanResult == null) {
                            scanResult = code
                            Log.i("QRScanner", "✅ 扫描成功: $code")
                            onScanResult(code)
                        }
                    })

                    // 扫描框
                    Box(
                        modifier = Modifier
                            .size(250.dp)
                            .border(2.dp, Color(0xFF7DAFDD), RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                    )

                    // 扫描线
                    Box(
                        modifier = Modifier
                            .width(250.dp)
                            .height(2.dp)
                            .background(Color(0xFFD0ECF4).copy(alpha = 0.6f))
                            .align(Alignment.Center)
                    )

                    // 提示文字
                    Text(
                        "将 Mac 屏幕上的 QR 码置于框内",
                        color = Color(0xFFD0ECF4).copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 60.dp)
                    )
                }

                PermissionState.DENIED_ONCE -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text("📷", fontSize = 56.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "需要相机权限才能扫描 QR 码",
                            color = Color(0xFFF8FAE7), fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "扫码功能用于扫描 Mac 端的二维码，自动连接 Mac 文件服务",
                            color = Color(0xFFD0ECF4).copy(alpha = 0.7f), fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7DAFDD))
                        ) {
                            Text("📷 授予相机权限", color = Color(0xFF0D1B2A), fontWeight = FontWeight.Medium)
                        }
                    }
                }

                PermissionState.DENIED_PERMANENTLY -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text("⚠️", fontSize = 56.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "相机权限已被拒绝",
                            color = Color(0xFFF8FAE7), fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "请在系统设置中开启 HotShare 的相机权限以使用扫码功能",
                            color = Color(0xFFD0ECF4).copy(alpha = 0.7f), fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF315BB8))
                        ) {
                            Text("⚙️ 去设置开启", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                PermissionState.INITIAL -> {
                    CircularProgressIndicator(color = Color(0xFF7DAFDD))
                    LaunchedEffect(Unit) {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            // 扫描成功覆盖层
            if (scanResult != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D1B2A).copy(alpha = 0.6f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("✅", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("扫描成功！", color = Color(0xFFF8FAE7), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "正在打开连接...",
                        color = Color(0xFF7DAFDD),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

// ===== 权限状态 =====

private enum class PermissionState {
    INITIAL,
    GRANTED,
    DENIED_ONCE,
    DENIED_PERMANENTLY
}

/**
 * 从 Compose 的 Context 中向上查找 Activity
 * 解决 Dialog 中 Context 是 ContextThemeWrapper 而非 Activity 的问题
 */
private fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

// ===== CameraX 预览 =====

@Composable
private fun CameraPreview(
    onQrDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    try {
                        val result = scanQRCode(imageProxy)
                        if (result != null) {
                            onQrDetected(result)
                        }
                    } catch (e: Exception) {
                        Log.w("QRScanner", "分析帧异常", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, preview, imageAnalysis
                    )
                    Log.i("QRScanner", "CameraX 绑定成功")
                } catch (e: Exception) {
                    Log.e("QRScanner", "CameraX 绑定失败", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ===== QR 码解码 =====

private fun scanQRCode(imageProxy: ImageProxy): String? {
    val bitmap = yuv420888ToBitmap(imageProxy) ?: return null

    return try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        val reader = QRCodeReader()
        val result = reader.decode(binaryBitmap)
        result.text
    } catch (e: com.google.zxing.NotFoundException) {
        null
    } catch (e: Exception) {
        Log.w("QRScanner", "解码异常", e)
        null
    }
}

private fun yuv420888ToBitmap(image: ImageProxy): android.graphics.Bitmap? {
    val planes = image.planes
    val width = image.width
    val height = image.height
    val pixelCount = width * height

    val nv21 = ByteArray(pixelCount * 3 / 2)
    var uvOffset = pixelCount

    // Y 平面
    val yPlane = planes[0]
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    if (yRowStride == width) {
        yBuffer.get(nv21, 0, pixelCount)
    } else {
        var dstPos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, dstPos, width)
            dstPos += width
        }
    }

    // UV 平面
    if (planes.size >= 3) {
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val uvHeight = height / 2
        val uvWidth = width / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vPos = vBuffer.get(row * vRowStride + col * vPixelStride).toInt() and 0xFF
                val uPos = uBuffer.get(row * uRowStride + col * uPixelStride).toInt() and 0xFF
                nv21[uvOffset++] = vPos.toByte()
                nv21[uvOffset++] = uPos.toByte()
            }
        }
    } else if (planes.size >= 2) {
        val uvPlane = planes[1]
        val uvBuffer = uvPlane.buffer
        val uvSize = pixelCount / 2
        uvBuffer.get(nv21, pixelCount, uvSize)
    }

    return try {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val jpegBytes = out.toByteArray()
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    } catch (e: Exception) {
        Log.e("QRScanner", "YUV→Bitmap 转换失败", e)
        null
    }
}
