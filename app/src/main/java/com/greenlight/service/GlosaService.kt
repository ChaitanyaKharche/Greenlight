package com.greenlight.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.greenlight.MainActivity
import com.greenlight.R
import com.greenlight.core.LatLon
import com.greenlight.data.GreenLightDb
import com.greenlight.model.GlosaAction
import com.greenlight.model.GlosaAdvice
import com.greenlight.nav.GlosaEngine
import com.greenlight.nav.LocationEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps the advisory alive with the screen off and another app in front.
 *
 * Android 14 requires a declared foreground service type; "location" is the honest one here,
 * and the notification is what makes the tracking visible to the user rather than silent.
 */
class GlosaService : LifecycleService() {

    private lateinit var engine: GlosaEngine
    private lateinit var db: GreenLightDb
    private var overlay: AdviceOverlay? = null
    private var tts: TextToSpeech? = null

    private var lastSpokenAction: GlosaAction? = null
    private var lastSpokenAtSec = 0.0
    private var lastSpokenSpeed = 0.0

    override fun onCreate() {
        super.onCreate()
        db = GreenLightDb(this)
        engine = GlosaEngine(this, db, lifecycleScope)
        Holder.engine = engine
        engine.refreshCounts()

        createChannel()
        startForegroundCompat(buildNotification("Starting", "Acquiring GPS"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            overlay = AdviceOverlay(this).also { it.show() }
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }

        observeAdvice()
        startLocation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SET_DESTINATION -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Destination"
                if (!lat.isNaN() && !lon.isNaN()) {
                    lifecycleScope.launch {
                        val from = engine.status.value.lastFix?.position
                        if (from != null) engine.setDestination(LatLon(lat, lon), label, from)
                    }
                }
            }

            ACTION_CLEAR_DESTINATION -> lifecycleScope.launch { engine.clearDestination() }
        }
        // Restart if the system reclaims us mid-drive; there is nothing to replay.
        return START_STICKY
    }

    private fun startLocation() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            updateNotification("Permission needed", "Grant precise location in the app")
            return
        }
        engine.setRunning(true)
        lifecycleScope.launch {
            LocationEngine(this@GlosaService).fixes().collectLatest { fix ->
                runCatching { engine.onFix(fix) }
            }
        }
    }

    private fun observeAdvice() {
        lifecycleScope.launch {
            engine.advice.collectLatest { advice ->
                val speed = engine.status.value.lastFix?.speedMps ?: 0.0
                overlay?.update(advice, speed)
                updateNotification(notificationTitle(advice), notificationBody(advice))
                maybeSpeak(advice, speed)
            }
        }
    }

    private fun notificationTitle(a: GlosaAdvice): String = when (a.action) {
        GlosaAction.HOLD -> "Hold ${kmh(a.targetMps)} km/h"
        GlosaAction.SPEED_UP -> "Pick up to ${kmh(a.targetMps)} km/h"
        GlosaAction.EASE_OFF -> "Ease off to ${kmh(a.targetMps)} km/h"
        GlosaAction.STOP_EXPECTED -> "Red ahead · green in ${a.timeToGreenSec?.roundToInt() ?: "?"} s"
        GlosaAction.NO_ADVICE -> "GreenLight active"
    }

    private fun notificationBody(a: GlosaAdvice): String {
        if (a.action == GlosaAction.NO_ADVICE) return a.note ?: "Watching for signals"
        val chain = if (a.signalsCleared > 1) ", clears ${a.signalsCleared} lights" else ""
        return "${a.distanceMeters.roundToInt()} m ahead · " +
            "${(a.confidence * 100).roundToInt()}% confidence$chain"
    }

    private fun kmh(mps: Double?) = mps?.let { (it * 3.6).roundToInt().toString() } ?: "--"

    /**
     * Voice prompts are rate limited hard. A GLOSA that narrates every fix is worse than
     * no GLOSA: the driver stops listening, then misses the one prompt that mattered.
     */
    private fun maybeSpeak(advice: GlosaAdvice, currentMps: Double) {
        val now = System.currentTimeMillis() / 1000.0
        if (now - lastSpokenAtSec < MIN_SPEAK_INTERVAL_SEC) return
        if (advice.action == GlosaAction.NO_ADVICE) return
        if (advice.confidence < 0.7) return

        val target = advice.targetMps ?: return
        val changedAction = advice.action != lastSpokenAction
        val changedSpeed = abs(target - lastSpokenSpeed) > 1.4
        if (!changedAction && !changedSpeed) return
        // Nothing to say when the driver is already doing the right thing.
        if (advice.action == GlosaAction.HOLD && !changedAction) return

        val phrase = when (advice.action) {
            GlosaAction.HOLD -> "Hold ${kmh(target)}"
            GlosaAction.SPEED_UP -> "Up to ${kmh(target)}"
            GlosaAction.EASE_OFF -> "Ease to ${kmh(target)}"
            GlosaAction.STOP_EXPECTED ->
                "Red ahead, green in ${advice.timeToGreenSec?.roundToInt() ?: 0} seconds"
            GlosaAction.NO_ADVICE -> return
        }
        tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "glosa")
        lastSpokenAction = advice.action
        lastSpokenAtSec = now
        lastSpokenSpeed = target
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Green light advisory",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Speed advice for catching green lights"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, body: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GlosaService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, body: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, body))
    }

    override fun onDestroy() {
        engine.setRunning(false)
        overlay?.hide()
        tts?.shutdown()
        Holder.engine = null
        super.onDestroy()
    }

    /**
     * Lets the UI observe the running engine's flows without binding.
     * A single foreground service means there is only ever one instance to reach.
     */
    object Holder {
        @Volatile
        var engine: GlosaEngine? = null
    }

    companion object {
        const val CHANNEL_ID = "glosa"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.greenlight.STOP"
        const val ACTION_SET_DESTINATION = "com.greenlight.SET_DESTINATION"
        const val ACTION_CLEAR_DESTINATION = "com.greenlight.CLEAR_DESTINATION"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_LABEL = "label"
        private const val MIN_SPEAK_INTERVAL_SEC = 12.0

        fun start(context: Context) {
            val intent = Intent(context, GlosaService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GlosaService::class.java).setAction(ACTION_STOP)
            )
        }

        fun setDestination(context: Context, lat: Double, lon: Double, label: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlosaService::class.java)
                    .setAction(ACTION_SET_DESTINATION)
                    .putExtra(EXTRA_LAT, lat)
                    .putExtra(EXTRA_LON, lon)
                    .putExtra(EXTRA_LABEL, label),
            )
        }

        fun clearDestination(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlosaService::class.java).setAction(ACTION_CLEAR_DESTINATION),
            )
        }
    }
}
