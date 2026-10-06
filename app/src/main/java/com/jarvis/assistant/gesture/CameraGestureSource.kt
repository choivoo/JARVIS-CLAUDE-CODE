package com.jarvis.assistant.gesture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.jarvis.assistant.util.JLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Front-camera luminance stream feeding [MotionSwipeDetector]. No preview, no recording, no storage. */
class CameraGestureSource(
    private val context: Context,
    private val onSwipe: (Swipe) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var detector = MotionSwipeDetector()
    private var lastFrame = 0L

    fun start(owner: LifecycleOwner, onActive: (Boolean) -> Unit) {
        stop()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val exec = Executors.newSingleThreadExecutor().also { executor = it }
                detector = MotionSwipeDetector()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(exec) { image -> analyze(image) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                onActive(true)
            } catch (e: Exception) {
                JLog.w("CameraGesture", "Camera gestures unavailable", e)
                onActive(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            JLog.w("CameraGesture", "unbind failed", e)
        }
        provider = null
        executor?.shutdown()
        executor = null
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < FRAME_GAP_MS) return
            lastFrame = now
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val swipe = detector.onFrame(
                bytes, image.width, image.height, plane.rowStride, image.imageInfo.rotationDegrees, now,
            )
            if (swipe != null) main.post { onSwipe(swipe) }
        } catch (e: Exception) {
            JLog.w("CameraGesture", "frame analysis failed", e)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val FRAME_GAP_MS = 66L // ~15 fps is plenty and saves battery
    }
}
