package com.appsalad.recorder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.appsalad.recorder.data.NotesRepo
import com.appsalad.recorder.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RecorderApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: NotesRepo
    lateinit var settings: Settings
    lateinit var speaker: Speaker
    lateinit var processor: Processor

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = NotesRepo(filesDir)
        settings = Settings(this)
        speaker = Speaker(this)
        processor = Processor(scope, repo, settings, speaker)
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CH_LISTEN, "Listening", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while Recorder listens for voice commands" })
            createNotificationChannel(NotificationChannel(CH_ALERT, "Alerts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Listening stopped and needs a tap to resume" })
        }
    }

    companion object {
        const val CH_LISTEN = "listen"
        const val CH_ALERT = "alert"
        lateinit var instance: RecorderApp
            private set
    }
}
