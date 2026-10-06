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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.jarvis.assistant.util.JLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Real hand tracking: front camera -> MediaPipe HandLandmarker (21 landmarks, runs fully on-device)
 * -> [HandGestureEngine]. No frame is stored or sent anywhere. If the model cannot be loaded the
 * caller falls back to the simpler motion-based swipe detector.
 */
class HandTrackerSource(
    private val context: Context,
    private val engine: HandGestureEngine,
    private val onEvents: (List<HandEvent>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var landmarker: HandLandmarker? = null
    private var lastFrame = 0L
    private var lastVideoTs = 0L

    /** [onState] is called with true once frames are flowing, false if tracking could not start. */
    fun start(owner: LifecycleOwner, onState: (Boolean) -> Unit) {
        stop()
        val exec = Executors.newSingleThreadExecutor().also { executor = it }
        exec.execute {
            val created = try {
                HandLandmarker.createFromOptions(
                    context,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(1)
                        .setMinHandDetectionConfidence(0.55f)
                        .setMinHandPresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .build(),
                )
            } catch (e: Throwable) {
                JLog.w("HandTracker", "Hand model unavailable", e)
                null
            }
            if (created == null) {
                main.post { onState(false) }
                return@execute
            }
            landmarker = created
            main.post { bind(owner, exec, onState) }
        }
    }

    private fun bind(owner: LifecycleOwner, exec: ExecutorService, onState: (Boolean) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { image -> analyze(image) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                engine.reset()
                onState(true)
            } catch (e: Exception) {
                JLog.w("HandTracker", "Camera unavailable", e)
                onState(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            JLog.w("HandTracker", "unbind failed", e)
        }
        provider = null
        val exec = executor
        executor = null
        // Close the model on the same thread that uses it, after the last frame.
        exec?.execute {
            try {
                landmarker?.close()
            } catch (_: Exception) {
            }
            landmarker = null
        }
        exec?.shutdown()
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < FRAME_GAP_MS) return
            lastFrame = now
            val tracker = landmarker ?: return
            val bitmap = image.toBitmap()
            val mp = BitmapImageBuilder(bitmap).build()
            val options = ImageProcessingOptions.builder().setRotationDegrees(image.imageInfo.rotationDegrees).build()
            // Timestamps must be strictly increasing.
            val ts = maxOf(now, lastVideoTs + 1).also { lastVideoTs = it }
            val result = tracker.detectForVideo(mp, options, ts)
            val hand = result.landmarks().firstOrNull()
            val frame = hand?.takeIf { it.size == 21 }?.let { lm ->
                HandFrame(lm.map { Pt(it.x(), it.y()) }, now)
            }
            val events = engine.onFrame(frame, now)
            if (events.isNotEmpty()) main.post { onEvents(events) }
        } catch (e: Throwable) {
            JLog.w("HandTracker", "frame failed", e)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val MODEL = "hand_landmarker.task"
        const val FRAME_GAP_MS = 40L
    }
}
