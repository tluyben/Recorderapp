# Recorder

Android app (Kotlin + Jetpack Compose). It listens in the background for voice commands
and records notes, or asks an AI a question and reads the answer aloud.

| Say | What happens |
| --- | --- |
| **“Take note”** … **“Stop stop”** | Native recording (16 kHz WAV) → OpenRouter speech-to-text → saved as a Note (edit / share / archive / delete, replay or share the audio) |
| **“Question”** … **“Stop stop”** | Recording → OpenRouter STT → OpenRouter chat model → answer read aloud with Android TTS, and stored under Questions |
| **“Stop stop”** while it speaks | Stops the answer |

Commands are spotted by two offline decoders: the open-vocabulary one, and a command-only
one (Vosk grammar) that snaps accented or mumbled "take note" / "question" onto the phrase
when it is said on its own. "Question" only triggers at the start of an utterance, or alone.

## How it works
- **Wake words: [Vosk](https://alphacephei.com/vosk/)** (Kaldi, `vosk-model-small-en-us-0.15`),
  bundled in the APK and run fully offline. Picovoice Porcupine would need an AccessKey
  and a custom `.ppn` trained per phrase in the Picovoice console. Vosk needs neither and
  can spot any phrase, including "Stop stop" during a recording.
- `ListenService` is a `microphone` foreground service. A single `AudioRecord` stream
  always feeds the spotter. During a recording the same samples also go to the WAV, with
  0.7 s of pre-roll. The command words are stripped from the transcript
  (`audio/Commands.kt`, unit-tested).
- OpenRouter: `POST /api/v1/audio/transcriptions` (multipart, streamed from disk) and
  `/chat/completions`. The user enters their key in Settings after installing, and it is
  stored only on the phone. The STT model, chat model, language and AI instructions can
  all be changed.
- Notes are kept in `files/notes.json` and `files/audio/*.wav` (app-private).
- Android 14+ only lets a microphone service start while the app is visible. So after a
  reboot or a system kill, listening resumes when the app is opened, and a notification
  asks for a tap. Allow "ignore battery optimisation" in Settings if you want listening
  to survive screen-off.

## Build / release (netcup-2 only — signed with its debug keystore)
    ./scripts/fetch-model.sh                         # once: Vosk model into assets
    ./scripts/publish-apk.sh                         # test + build + upload to sharefiles recorder-app
    ./gradlew :app:testDebugUnitTest                 # unit tests + screenshots → app/build/shots/

Download: https://sharefiles.eu/direct/ANmFHssycH3ZHJDt0YXqgm9zSIfVoDdg/recorder.apk
