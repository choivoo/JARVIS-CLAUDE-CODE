package com.jarvis.assistant.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.core.JarvisController
import com.jarvis.assistant.data.model.Gesture
import com.jarvis.assistant.data.model.GestureAction
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.util.Haptics
import com.jarvis.assistant.util.JLog
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Air gestures.
 *  - Proximity waves (single / double) work anywhere JARVIS is active: app open or standby service running.
 *  - Camera swipes (left / right / up / down) work only while the JARVIS screen is open, show an
 *    on-screen indicator, and never store frames.
 */
class GestureManager(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val controller: JarvisController,
    private val scope: CoroutineScope,
) {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val proximity: Sensor? =
        sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val waveDetector = ProximityWaveDetector()
    private val camera = CameraGestureSource(context) { swipe -> fire(swipe.toGesture()) }
    private val engine = HandGestureEngine()
    private val handTracker = HandTrackerSource(context, engine) { events -> handleHandEvents(events) }
    private var usingFallback = false

    private var users = 0
    private var registered = false
    private var pollJob: Job? = null
    private var torchOn = false

    private val _cameraActive = MutableStateFlow(false)
    val cameraActive: StateFlow<Boolean> = _cameraActive

    val proximityAvailable: Boolean get() = proximity != null

    // ---- pointer state for the hologram workspace --------------------------------------------
    data class Pointer(
        val visible: Boolean = false,
        val x: Float = 0.5f,
        val y: Float = 0.5f,
        val pose: HandPose = HandPose.UNKNOWN,
        val pinching: Boolean = false,
        val dwell: Float = 0f,
    )

    sealed interface PointerEvent {
        data class Down(val x: Float, val y: Float) : PointerEvent
        data class Up(val x: Float, val y: Float) : PointerEvent
        data class Click(val x: Float, val y: Float) : PointerEvent
    }

    private val _pointer = MutableStateFlow(Pointer())
    val pointer: StateFlow<Pointer> = _pointer

    private val _pointerEvents = MutableSharedFlow<PointerEvent>(extraBufferCapacity = 16)
    val pointerEvents: SharedFlow<PointerEvent> = _pointerEvents

    private val _trackingEngine = MutableStateFlow("")
    /** "MediaPipe hand tracking", "Motion fallback" or empty when the camera is off. */
    val trackingEngine: StateFlow<String> = _trackingEngine

    /** While true (hologram screen open) swipes and poses drive the pointer UI instead of global actions. */
    @Volatile
    var pointerMode: Boolean = false

    private fun handleHandEvents(events: List<HandEvent>) {
        for (e in events) {
            when (e) {
                is HandEvent.Cursor -> _pointer.value = Pointer(true, e.x, e.y, e.pose, e.pinching, e.dwell)
                is HandEvent.PinchDown -> _pointerEvents.tryEmit(PointerEvent.Down(e.x, e.y))
                is HandEvent.PinchUp -> {
                    _pointerEvents.tryEmit(PointerEvent.Up(e.x, e.y))
                    _pointerEvents.tryEmit(PointerEvent.Click(e.x, e.y))
                }
                is HandEvent.DwellClick -> {
                    _pointerEvents.tryEmit(PointerEvent.Down(e.x, e.y))
                    _pointerEvents.tryEmit(PointerEvent.Up(e.x, e.y))
                    _pointerEvents.tryEmit(PointerEvent.Click(e.x, e.y))
                }
                is HandEvent.SwipeEvent -> if (!pointerMode) fire(e.direction.toGesture())
                is HandEvent.PoseHold -> when (e.pose) {
                    HandPose.OPEN_PALM -> fire(Gesture.PALM_HOLD)
                    HandPose.FIST -> if (!pointerMode) fire(Gesture.FIST_HOLD)
                    HandPose.VICTORY -> fire(Gesture.VICTORY_HOLD)
                    HandPose.THUMBS_UP -> if (!pointerMode) fire(Gesture.THUMBS_UP_HOLD)
                    else -> Unit
                }
                HandEvent.Lost -> _pointer.value = Pointer(visible = false)
            }
        }
    }

    init {
        scope.launch {
            settingsRepo.settings.map { it.proximityGestures }.distinctUntilChanged().collect { refreshProximity() }
        }
    }

    /** Called while the app is visible or the standby service runs. Reference counted. */
    fun acquire() {
        users++
        refreshProximity()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        refreshProximity()
    }

    private fun refreshProximity() {
        val want = users > 0 && settingsRepo.settings.value.proximityGestures && proximity != null
        if (want && !registered) {
            registered = sensors.registerListener(listener, proximity, SensorManager.SENSOR_DELAY_NORMAL)
        } else if (!want && registered) {
            sensors.unregisterListener(listener)
            registered = false
            pollJob?.cancel()
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val near = event.values[0] < (proximity?.maximumRange ?: 5f)
            val now = SystemClock.elapsedRealtime()
            waveDetector.onSensor(near, now)?.let { fireWave(it) }
            if (waveDetector.hasPending) startPolling()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (waveDetector.hasPending) {
                delay(80)
                waveDetector.poll(SystemClock.elapsedRealtime())?.let { fireWave(it) }
            }
        }
    }

    private fun fireWave(wave: ProximityWaveDetector.Wave) =
        fire(if (wave == ProximityWaveDetector.Wave.SINGLE) Gesture.WAVE else Gesture.DOUBLE_WAVE)

    // ------------------------------------------------------------------ camera

    /** Starts hand tracking bound to [owner]'s lifecycle. Needs CAMERA permission. */
    fun startCamera(owner: LifecycleOwner) {
        if (!Perms.hasCamera(context)) return
        val s = settingsRepo.settings.value
        engine.config = HandConfig(
            pinchOn = 0.22f + 0.2f * s.pinchSensitivity,
            pinchOff = 0.36f + 0.2f * s.pinchSensitivity,
            dwellEnabled = s.dwellClick,
        )
        usingFallback = false
        handTracker.start(owner) { ok ->
            if (ok) {
                _trackingEngine.value = "MediaPipe hand tracking"
                _cameraActive.value = true
            } else if (!usingFallback) {
                // No hand model on this device: fall back to simple motion swipes.
                usingFallback = true
                camera.start(owner) { active ->
                    _cameraActive.value = active
                    _trackingEngine.value = if (active) "Motion fallback" else ""
                }
            }
        }
    }

    fun stopCamera() {
        handTracker.stop()
        camera.stop()
        engine.reset()
        _pointer.value = Pointer(visible = false)
        _trackingEngine.value = ""
        _cameraActive.value = false
    }

    // ------------------------------------------------------------------ dispatch

    fun fire(gesture: Gesture) {
        val settings = settingsRepo.settings.value
        val action = settings.gestureActions[gesture] ?: gesture.default
        if (action == GestureAction.NONE) return
        JLog.d("Gesture", "$gesture -> $action")
        if (settings.wakeHaptic) Haptics.tick(context, 25)
        run(action)
    }

    internal fun run(action: GestureAction) {
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.LISTEN -> controller.listenNow()
            GestureAction.STOP -> controller.interrupt()
            GestureAction.MUSIC_TOGGLE ->
                controller.quickAction(AiAction(if (audio.isMusicActive) "MUSIC_PAUSE" else "MUSIC_PLAY"), speak = false)
            GestureAction.FLASHLIGHT_TOGGLE -> {
                torchOn = !torchOn
                controller.quickAction(AiAction(if (torchOn) "FLASHLIGHT_ON" else "FLASHLIGHT_OFF"), speak = false)
            }
            GestureAction.TELL_TIME, GestureAction.TELL_BATTERY, GestureAction.TELL_WEATHER ->
                controller.quickAction(AiAction(action.commandType!!), speak = true)
            else -> controller.quickAction(AiAction(action.commandType!!), speak = false)
        }
    }

    private fun Swipe.toGesture() = when (this) {
        Swipe.LEFT -> Gesture.SWIPE_LEFT
        Swipe.RIGHT -> Gesture.SWIPE_RIGHT
        Swipe.UP -> Gesture.SWIPE_UP
        Swipe.DOWN -> Gesture.SWIPE_DOWN
    }
}
