package com.example.autocallmanager

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class VoiceService : Service() {
    companion object {
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_LANGUAGE = "extra_language"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_PITCH = "extra_pitch"
        const val ACTION_STOP = "com.example.autocallmanager.ACTION_STOP_VOICE"
        private const val CHANNEL_ID = "voice_service_v104"
        private const val NOTIFICATION_ID = 7604
    }

    private var tts: TextToSpeech? = null
    private var message: String = ""
    private var taskId: Long = -1L
    private var serviceStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification("Preparing voice message…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceStartId = startId
        if (intent?.action == ACTION_STOP) {
            tts?.stop()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        message = intent?.getStringExtra(EXTRA_MESSAGE)?.trim().orEmpty()
        taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L
        val language = intent?.getStringExtra(EXTRA_LANGUAGE) ?: SettingsStore.voiceLanguage(this)
        val speed = intent?.getFloatExtra(EXTRA_SPEED, SettingsStore.voiceSpeed(this)) ?: SettingsStore.voiceSpeed(this)
        val pitch = intent?.getFloatExtra(EXTRA_PITCH, SettingsStore.voicePitch(this)) ?: SettingsStore.voicePitch(this)

        if (message.isBlank()) {
            updateHistory("VOICE_EMPTY")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        tts?.shutdown()
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                updateHistory("VOICE_INIT_FAILED")
                stopSelfResult(serviceStartId)
                return@TextToSpeech
            }

            val engine = tts ?: run {
                stopSelfResult(serviceStartId)
                return@TextToSpeech
            }

            val selected = when (language.lowercase(Locale.getDefault())) {
                "hindi" -> Locale("hi", "IN")
                "english" -> Locale.US
                else -> if (message.any { it in '\u0900'..'\u097F' }) Locale("hi", "IN") else Locale.US
            }
            val languageResult = engine.setLanguage(selected)
            if (languageResult < TextToSpeech.LANG_AVAILABLE) {
                engine.setLanguage(Locale.US)
            }
            engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
            engine.setPitch(pitch.coerceIn(0.5f, 1.5f))
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    updateHistory("VOICE_STARTED")
                }

                override fun onDone(utteranceId: String?) {
                    updateHistory("VOICE_COMPLETED")
                    stopSelfResult(serviceStartId)
                }

                override fun onError(utteranceId: String?) {
                    updateHistory("VOICE_ERROR")
                    stopSelfResult(serviceStartId)
                }
            })

            updateNotification("Speaking scheduled message…")
            val spoken = message.take(TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(100))
            val accepted = engine.speak(
                spoken,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "task-$taskId-${System.currentTimeMillis()}"
            )
            if (accepted != TextToSpeech.SUCCESS) {
                updateHistory("VOICE_REQUEST_FAILED")
                stopSelfResult(serviceStartId)
            }
        }
        return START_NOT_STICKY
    }

    private fun updateHistory(result: String) {
        if (taskId <= 0L) return
        val tasks = TaskStore.load(this)
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        task.lastResult = result
        task.liveStatus = result
        TaskStore.save(this, tasks)
        TaskStore.addHistory(this, task, "VOICE", result)
    }

    private fun updateNotification(text: String) {
        // Same lint requirement as CallManager.showNotification(): this is a
        // foreground service's own status notification, so functionally it
        // degrades fine with no permission (the service keeps running,
        // it just won't show an updated text) -- but lintDebug still wants
        // an explicit checkSelfPermission()/SDK-gate immediately guarding
        // this exact notify() call.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setContentTitle("Auto Call Manager")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Voice playback",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
