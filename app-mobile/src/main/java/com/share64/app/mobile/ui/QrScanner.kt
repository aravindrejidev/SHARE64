package com.share64.app.mobile.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** The activity behind a (possibly wrapped) context; CameraX needs it as the lifecycle to bind to. */
fun Context.findComponentActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Reads QR codes out of camera frames with ZXing (pure Java, no Google services). Works on the
 * brightness plane only, tries inverted (light-on-dark) codes too, and never throws for "no code here".
 */
class QrFrameDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf<DecodeHintType, Any>(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ALSO_INVERTED to true,
            ),
        )
    }
    private var luminance = ByteArray(0)

    /** The text of the code in this frame, or null. The caller closes the frame. */
    fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        if (luminance.size != width * height) luminance = ByteArray(width * height)
        // The camera may pad every row; copy the picture without the padding.
        for (row in 0 until height) {
            val start = row * rowStride
            if (start >= buffer.limit()) break
            buffer.position(start)
            buffer.get(luminance, row * width, minOf(width, buffer.limit() - start))
        }
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}

/**
 * Camera preview that reports QR codes. Binds the back camera to the activity's lifecycle while it is
 * on screen and releases it when it leaves. [paused] keeps the picture running but ignores codes
 * (while a connection is being made); [torchOn] drives the flashlight.
 */
@Composable
fun QrScannerView(
    modifier: Modifier = Modifier,
    torchOn: Boolean,
    paused: Boolean,
    onCode: (String) -> Unit,
    onCameraFailure: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val latestOnCode by rememberUpdatedState(onCode)
    val latestPaused by rememberUpdatedState(paused)
    val latestFailure by rememberUpdatedState(onCameraFailure)

    DisposableEffect(activity, previewView) {
        if (activity == null) {
            latestFailure()
            return@DisposableEffect onDispose { }
        }
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val decoder = QrFrameDecoder()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                                )
                                .build(),
                        )
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (!latestPaused) {
                                val text = decoder.decode(image)
                                if (text != null) main.post { latestOnCode(text) }
                            }
                        } finally {
                            image.close()
                        }
                    }
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {
                    latestFailure()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            camera = null
            try {
                if (providerFuture.isDone) providerFuture.get().unbindAll()
            } catch (_: Exception) {
                // the camera is already gone
            }
            executor.shutdown()
        }
    }

    LaunchedEffect(camera, torchOn) {
        val c = camera ?: return@LaunchedEffect
        try {
            c.cameraControl.enableTorch(torchOn)
        } catch (_: Exception) {
            // no flash on this camera
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** True when the device has any camera at all (some tablets and Chromebooks do not). */
fun Context.hasCamera(): Boolean =
    packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
