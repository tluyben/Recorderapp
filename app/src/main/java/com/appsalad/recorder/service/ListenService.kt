package com.appsalad.recorder.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.appsalad.recorder.MainActivity
import com.appsalad.recorder.R
import com.appsalad.recorder.RecorderApp
import com.appsalad.recorder.audio.Command
import com.appsalad.recorder.audio.Commands
import com.appsalad.recorder.audio.Spotter
import com.appsalad.recorder.audio.WavWriter
import com.appsalad.recorder.data.NoteKind
import com.appsalad.recorder.data.NoteStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Mode { IDLE, NOTE, QUESTION, SPEAKING }

data class ListenState(
    val running: Boolean = false,
    val loading: Boolean = false,
    val mode: Mode = Mode.IDLE,
    val recordingSince: Long = 0,
    /** What the offline recognizer last heard, so the user can see it working. */
    val heard: String = "",
    val error: String = "",
)

/**
 * Owns the microphone. One AudioRecord stream feeds the offline command spotter all the
 * time; while a note or question is being recorded the same samples also go into a WAV
 * file, so "Stop stop" is heard while recording.
 */
class ListenService : Service() {
    private val app get() = application as RecorderApp
    @Volatile private var thread: Thread? = null
    @Volatile private var pending: Command? = null
    @Volatile private var quit = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_NOTE -> pending = Command.TAKE_NOTE
            ACTION_QUESTION -> pending = Command.QUESTION
            ACTION_STOP_RECORDING -> pending = Command.STOP
            ACTION_SILENCE -> app.speaker.stop()
            ACTION_OFF -> {
                app.settings.update { it.copy(listening = false) }
                if (_state.value.mode == Mode.NOTE || _state.value.mode == Mode.QUESTION) pending = Command.STOP
                else quit = true
            }
            // ACTION_LISTEN, or a sticky restart (null intent): just make sure the loop runs
        }
        if (thread?.isAlive != true) {
            quit = false
            thread = Thread(::loop, "listen").apply { priority = Thread.MAX_PRIORITY; start() }
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(this, NOTIF_ID, notification(_state.value),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        true
    } catch (e: Exception) {
        // Android 14+ only lets a microphone service start while the app is visible
        // (e.g. not after the system killed and restarted us). Ask for a tap instead.
        Log.w(TAG, "cannot start in foreground: $e")
        notifyResume()
        stopSelf()
        false
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recorder:listen").apply { acquire() }
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull()
        set { it.copy(running = true, loading = true, error = "") }
        var spotter: Spotter? = null
        var record: AudioRecord? = null
        var writer: WavWriter? = null
        var noteId = ""
        var kind = NoteKind.NOTE
        try {
            spotter = Spotter.load(this, RATE)
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
            if (record.state != AudioRecord.STATE_INITIALIZED) error("The microphone is not available")
            record.startRecording()
            set { it.copy(loading = false) }

            val preroll = ByteRing(RATE * 2 * PREROLL_MS / 1000)
            val buf = ByteArray(CHUNK)
            var lastHeard = ""

            fun start(k: NoteKind) {
                kind = k
                val f = app.repo.newAudioFile()
                writer = WavWriter(f, RATE).also { w -> preroll.drainTo { b, n -> w.write(b, n) } }
                noteId = app.repo.create(k, f.absolutePath).id
                app.speaker.stop()
                spotter.reset()
                val mode = if (k == NoteKind.NOTE) Mode.NOTE else Mode.QUESTION
                set { it.copy(mode = mode, recordingSince = System.currentTimeMillis(), heard = "") }
                cue(start = true)
            }

            fun finish() {
                val w = writer ?: return
                w.close(); writer = null
                val dur = w.durationMs
                if (dur < MIN_MS) {
                    app.repo.delete(noteId)
                } else {
                    app.repo.update(noteId) { it.copy(durationMs = dur, status = NoteStatus.TRANSCRIBING) }
                    app.processor.process(noteId)
                }
                spotter.reset()
                set { it.copy(mode = Mode.IDLE, recordingSince = 0, heard = "") }
                cue(start = false)
            }

            while (!quit) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) error("Microphone read failed ($n)")
                if (n == 0) continue

                val cmd = pending.also { pending = null }
                val mode = _state.value.mode
                val recording = mode == Mode.NOTE || mode == Mode.QUESTION
                when {
                    cmd == Command.STOP && recording -> { writer?.write(buf, n); finish(); continue }
                    (cmd == Command.TAKE_NOTE || cmd == Command.QUESTION) && !recording ->
                        start(if (cmd == Command.TAKE_NOTE) NoteKind.NOTE else NoteKind.QUESTION)
                }

                val heard = spotter.feed(buf, n)
                if (heard.text.isNotEmpty() && heard.text != lastHeard) {
                    lastHeard = heard.text
                    set { it.copy(heard = heard.text) }
                }
                if (heard.final) lastHeard = ""

                when (_state.value.mode) {
                    Mode.IDLE -> {
                        preroll.write(buf, n)
                        if (app.speaker.speaking.value) {
                            // don't let the answer being read out trigger anything
                            spotter.reset(); set { it.copy(mode = Mode.SPEAKING) }
                        } else if (app.settings.value.listening) {
                            val byGrammar = Commands.fromGrammar(spotter.feedCommands(buf, n))
                            when (Commands.whileIdle(heard.text) ?: byGrammar) {
                                Command.TAKE_NOTE -> start(NoteKind.NOTE)
                                Command.QUESTION -> start(NoteKind.QUESTION)
                                else -> {}
                            }
                        } else if (pending == null) {
                            break // listening is off and the manual recording is done
                        }
                    }
                    Mode.NOTE, Mode.QUESTION -> {
                        writer?.write(buf, n)
                        val maxMs = if (kind == NoteKind.NOTE) app.settings.value.maxNoteMinutes * 60_000L else MAX_QUESTION_MS
                        if (Commands.whileRecording(heard.text) == Command.STOP || (writer?.durationMs ?: 0) >= maxMs) finish()
                    }
                    Mode.SPEAKING -> {
                        if (Commands.whileRecording(heard.text) == Command.STOP) { app.speaker.stop(); spotter.reset() }
                        if (!app.speaker.speaking.value) { spotter.reset(); set { it.copy(mode = Mode.IDLE, heard = "") } }
                    }
                }
            }
            if (writer != null) finish()
        } catch (e: Throwable) {
            Log.e(TAG, "listen loop", e)
            set { it.copy(error = e.message ?: e.toString()) }
            writer?.let { w -> w.close(); app.repo.update(noteId) { it.copy(durationMs = w.durationMs) }; app.processor.process(noteId) }
        } finally {
            runCatching { record?.stop() }; record?.release()
            spotter?.close()
            tone?.release(); tone = null
            wakeLock?.takeIf { it.isHeld }?.release()
            set { it.copy(running = false, loading = false, mode = Mode.IDLE, recordingSince = 0) }
            thread = null
            stopSelf()
        }
    }

    private fun cue(start: Boolean) {
        runCatching { tone?.startTone(if (start) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 150) }
        runCatching {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(if (start) VibrationEffect.createOneShot(60, 200)
                else VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1))
        }
    }

    private fun set(change: (ListenState) -> ListenState) {
        val old = _state.value
        val new = change(old)
        _state.value = new
        if (new.mode != old.mode || new.running != old.running || new.loading != old.loading) {
            if (new.running) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(new))
        }
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, ListenService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(s: ListenState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val listening = app.settings.value.listening
        val b = NotificationCompat.Builder(this, RecorderApp.CH_LISTEN)
            .setSmallIcon(R.drawable.ic_mic_white)
            .setOngoing(true).setSilent(true).setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (s.mode) {
            Mode.NOTE, Mode.QUESTION -> {
                b.setContentTitle(if (s.mode == Mode.NOTE) "Recording a note" else "Listening to your question")
                    .setContentText("Say “Stop stop” when you're done")
                    .setUsesChronometer(true).setWhen(s.recordingSince)
                    .addAction(0, "Stop", pi(ACTION_STOP_RECORDING, 3))
            }
            Mode.SPEAKING -> b.setContentTitle("Reading the answer").setContentText("Say “Stop stop” to interrupt")
                .addAction(0, "Silence", pi(ACTION_SILENCE, 4))
            Mode.IDLE -> {
                b.setContentTitle(if (s.loading) "Starting…" else if (listening) "Listening for “Take note” or “Question”" else "Recorder")
                    .setContentText("Voice commands are recognised on the phone, offline")
                    .addAction(0, "Note", pi(ACTION_NOTE, 1))
                    .addAction(0, "Ask", pi(ACTION_QUESTION, 2))
                if (listening) b.addAction(0, "Turn off", pi(ACTION_OFF, 5))
            }
        }
        return b.build()
    }

    private fun notifyResume() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_RESUME, NotificationCompat.Builder(this, RecorderApp.CH_ALERT)
                .setSmallIcon(R.drawable.ic_mic_white).setContentTitle("Recorder stopped listening")
                .setContentText("Tap to resume listening for voice commands").setAutoCancel(true).setContentIntent(open).build())
        }
    }

    override fun onDestroy() {
        quit = true
        super.onDestroy()
    }

    /** Fixed-size ring holding the last few hundred ms, so a recording starts just before the trigger was recognised. */
    private class ByteRing(size: Int) {
        private val data = ByteArray(size - size % 2)
        private var pos = 0
        private var filled = 0
        fun write(b: ByteArray, n: Int) {
            for (i in 0 until n) { data[pos] = b[i]; pos = (pos + 1) % data.size }
            filled = minOf(data.size, filled + n)
        }
        fun drainTo(sink: (ByteArray, Int) -> Unit) {
            if (filled == 0) return
            val out = ByteArray(filled)
            val start = (pos - filled + data.size) % data.size
            for (i in 0 until filled) out[i] = data[(start + i) % data.size]
            sink(out, filled); filled = 0
        }
    }

    companion object {
        private const val TAG = "ListenService"
        const val RATE = 16000
        private const val CHUNK = 3200 // 100 ms
        private const val PREROLL_MS = 700
        private const val MIN_MS = 800L
        private const val MAX_QUESTION_MS = 3 * 60_000L
        private const val NOTIF_ID = 1
        private const val NOTIF_RESUME = 2
        const val ACTION_LISTEN = "listen"
        const val ACTION_NOTE = "note"
        const val ACTION_QUESTION = "question"
        const val ACTION_STOP_RECORDING = "stop_recording"
        const val ACTION_SILENCE = "silence"
        const val ACTION_OFF = "off"

        private val _state = MutableStateFlow(ListenState())
        val state: StateFlow<ListenState> = _state.asStateFlow()

        fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java).setAction(action))
        }
    }
}
