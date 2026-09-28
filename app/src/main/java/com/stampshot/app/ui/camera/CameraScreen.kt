package com.stampshot.app.ui.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.stampshot.app.MainViewModel
import com.stampshot.app.capture.PhotoCapture
import com.stampshot.app.data.AppSettings
import com.stampshot.app.location.LocationStamper
import com.stampshot.app.stamp.StampInfo
import com.stampshot.app.stamp.StampOptions
import com.stampshot.app.stamp.StampStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraScreen(viewModel: MainViewModel, onOpenGallery: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsState()

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        cameraGranted = grants[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            val perms = buildList {
                add(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    if (!cameraGranted) {
        PermissionGate(onRequest = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) })
        return
    }

    // ---------- CameraX binding ----------
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var zoomRange by remember { mutableStateOf(0.5f..10f) }

    LaunchedEffect(settings.lensFacingBack, settings.flashMode) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(
                when (settings.flashMode) {
                    AppSettings.FLASH_ON -> ImageCapture.FLASH_MODE_ON
                    AppSettings.FLASH_AUTO -> ImageCapture.FLASH_MODE_AUTO
                    else -> ImageCapture.FLASH_MODE_OFF
                },
            )
            .build()
        val selector = CameraSelector.Builder()
            .requireLensFacing(
                if (settings.lensFacingBack) CameraSelector.LENS_FACING_BACK
                else CameraSelector.LENS_FACING_FRONT,
            )
            .build()
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            imageCapture = capture
            camera?.cameraInfo?.zoomState?.observe(lifecycleOwner) { zs ->
                zoomRange = zs.minZoomRatio..zs.maxZoomRatio
                zoomRatio = zs.zoomRatio
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Camera failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Capture ----------
    var capturing by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val shutterFx = remember { android.media.MediaActionSound() }

    fun shoot() {
        val ic = imageCapture ?: return
        if (capturing) return
        capturing = true
        scope.launch {
            try {
                val s = viewModel.repo.current()
                if (s.timerSecs > 0) {
                    for (t in s.timerSecs downTo 1) {
                        countdown = t
                        delay(1000)
                    }
                    countdown = null
                }
                flash = true
                if (s.shutterSound) shutterFx.play(android.media.MediaActionSound.SHUTTER_CLICK)
                val number = viewModel.repo.nextNumber(s.currentSession)
                val loc = if ((s.showAddress || s.showGps) && LocationStamper.hasPermission(context)) {
                    LocationStamper.snapshot(context)
                } else null
                val info = StampInfo(
                    timestampMillis = System.currentTimeMillis(),
                    sessionName = s.currentSession,
                    photoNumber = number,
                    note = s.note.ifBlank { null },
                    address = loc?.address,
                    city = loc?.city,
                    latitude = loc?.location?.latitude,
                    longitude = loc?.location?.longitude,
                    showAddress = s.showAddress,
                    showGps = s.showGps,
                    showNumber = s.showNumber,
                    dateFormat = s.dateFormat,
                    gpsFormat = s.gpsFormat,
                )
                val opts = StampOptions(
                    fontScale = s.fontScale,
                    fontColorArgb = s.fontColorArgb,
                    bgColorArgb = s.bgColorArgb,
                    textOpacity = s.textOpacity,
                    bgOpacity = s.bgOpacity,
                    position = s.stampPosition,
                    font = s.stampFont,
                )
                val saved = PhotoCapture(context).capture(
                    ic, executor, info, s.style, s.keepOriginals,
                    options = opts,
                    mirror = s.mirrorFront && !s.lensFacingBack,
                    maxDim = s.photoMaxDim,
                )
                Toast.makeText(context, "Saved photo #%03d".format(saved.number), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                capturing = false
                countdown = null
            }
        }
    }

    // Tap-to-focus + pinch-to-zoom in a single touch listener.
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(previewView) {
        val scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    val target = (current * detector.scaleFactor)
                        .coerceIn(zoomRange.start, zoomRange.endInclusive)
                    cam.cameraControl.setZoomRatio(target)
                    return true
                }
            })
        previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == android.view.MotionEvent.ACTION_DOWN && !scaleDetector.isInProgress) {
                if (settings.touchToCapture) {
                    shoot()
                } else {
                    val cam = camera
                    if (cam != null) {
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        cam.cameraControl.startFocusAndMetering(action)
                        focusPoint = Offset(event.x, event.y)
                    }
                }
            }
            true
        }
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(900)
            focusPoint = null
        }
    }

    // ---------- UI ----------
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    val flashAlpha by animateFloatAsState(if (flash) 0.85f else 0f, label = "captureFlash")
    LaunchedEffect(flash) { if (flash) { delay(80); flash = false } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Rule-of-thirds grid
        if (settings.showGrid) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val lineColor = Color.White.copy(alpha = 0.35f)
                val w = size.width
                val h = size.height
                for (i in 1..2) {
                    drawLine(lineColor, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), strokeWidth = 1f)
                    drawLine(lineColor, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), strokeWidth = 1f)
                }
            }
        }

        // Countdown timer overlay
        countdown?.let { t ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "$t",
                    color = Color.White,
                    style = MaterialTheme.typography.displayLarge,
                )
            }
        }

        // Focus ring
        focusPoint?.let { p ->
            val density = previewView.resources.displayMetrics.density
            Box(
                modifier = Modifier
                    .offset(
                        x = (p.x / density).dp - 36.dp,
                        y = (p.y / density).dp - 36.dp,
                    )
                    .size(72.dp)
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp)),
            )
        }

        // Live stamp preview
        StampPreview(
            settings = settings,
            previewView = previewView,
            modifier = Modifier.align(Alignment.BottomStart),
        )

        // Capture flash overlay
        if (flashAlpha > 0f) {
            Box(Modifier.fillMaxSize().alpha(flashAlpha).background(Color.White))
        }

        // Top controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                viewModel.setFlashMode((settings.flashMode + 1) % 3)
            }) {
                Icon(
                    imageVector = when (settings.flashMode) {
                        AppSettings.FLASH_ON -> Icons.Filled.FlashOn
                        AppSettings.FLASH_AUTO -> Icons.Filled.FlashAuto
                        else -> Icons.Filled.FlashOff
                    },
                    contentDescription = "Flash mode",
                    tint = Color.White,
                )
            }
            ZoomBadge(zoomRatio)
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onOpenGallery) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = "Gallery", tint = Color.White)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SessionChip(
                label = "${settings.currentSession} · #%03d".format(settings.nextNumber()),
                onClick = { showSessions = true },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Box(modifier = Modifier.size(64.dp))
                ShutterButton(capturing = capturing, onClick = { shoot() })
                IconButton(
                    onClick = { viewModel.setLensFacingBack(!settings.lensFacingBack) },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000)),
                ) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
                }
            }
        }

        if (capturing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(56.dp),
                color = Color.White,
            )
        }
    }

    if (showSettings) {
        SettingsSheet(viewModel = viewModel, onDismiss = { showSettings = false })
    }
    if (showSessions) {
        SessionSheet(viewModel = viewModel, onDismiss = { showSessions = false })
    }
}

@Composable
private fun ZoomBadge(ratio: Float) {
    Surface(
        color = Color(0x55000000),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = "%.1fx".format(ratio),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SessionChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color(0x66000000),
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ShutterButton(capturing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            enabled = !capturing,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = Color.White,
        ) {}
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "StampShot needs the camera to take photos.",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 20.dp)) {
            Text("Allow camera")
        }
    }
}
