# Heard Real-Time Assistive Captioning

This repository contains a production-oriented starter implementation of a real-time assistive captioning platform for distance-readable live text.

## What this project includes

- Android app that captures microphone audio and performs speech-to-text with selectable `ONLINE` and `OFFLINE` recognition modes.
- Local Wi-Fi broadcast server hosted directly on the phone.
- Browser caption screen optimized for low-vision distance reading.
- Stable text behavior with separate partial and finalized lines.
- Multilingual profiles for English, Urdu, and Roman Urdu (Urdu recognition + transliterated display).
- Auto bilingual AI post-processing for Urdu/English mixed speech and English token cleanup.
- N-best candidate re-ranking for cleaner final word selection.
- Custom vocabulary controls on mobile UI for names and domain-specific terms.
- Adaptive on-device self-learning that accumulates correction rules from continued usage.

## Architecture

1. Speaker wears a wireless lapel microphone paired to Android phone.
2. Android app transcribes speech (`ONLINE` or `OFFLINE` mode).
3. Android app hosts an embedded HTTP/WebSocket server at `http://<phone-ip>:8765`.
4. Browser client on tablet/TV subscribes to `/ws` and renders large, high-contrast live captions.

## Project layout

- `android/`: Android application and embedded caption web server
- `android/app/src/main/assets/web/`: Browser-based caption display client

## Android setup

1. Open `android/` in Android Studio.
2. Let Gradle sync and install dependencies.
3. Connect phone and run the app.
4. Grant microphone permission.
5. Keep phone and display device on the same Wi-Fi network.
6. Open the shown URL from the display browser.

## Runtime notes

- `ONLINE` mode uses platform online speech recognition.
- `OFFLINE` mode uses platform offline speech recognition where supported by the device/language packs.
- Roman Urdu mode uses Urdu STT (`ur-PK`) and transliterates output for Latin-script display.
- Captions are kept as rolling finalized lines while partial speech stays in a dedicated line to avoid visual jitter.
- Default language profile is `English` (you can switch to `Urdu` or `Roman Urdu` from the mobile controls).
- Use **Custom Vocabulary** in the mobile app to inject names/terms into recognition biasing for better accuracy.
- Self-learning is automatic in-app for English/mixed utterances and persists locally between sessions.

## AI token model training

- Seed data: `ai/training/seed_pairs.tsv`
- Trainer: `ai/training/train_token_model.py`
- Generate model JSON:
  - `python ai/training/train_token_model.py`
- Generated file:
  - `ai/training/token_model.json`
- Runtime model asset used by app:
  - `android/app/src/main/assets/token_model.json`
- After retraining, sync runtime model:
  - `copy ai\\training\\token_model.json android\\app\\src\\main\\assets\\token_model.json`

## Permissions used

- `RECORD_AUDIO`
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`

## Next extension points

- Add speaker diarization or multiple mic channels.
- Add optional cloud STT provider adapter for higher online accuracy.
- Persist session transcripts with export options.
- Add remote control page for changing language/mode from browser.
