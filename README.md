# Tanvir Voice Assistant

Native Android Kotlin project for Bengali voice communication.

## Implemented foundations
- sherpa-onnx streaming Bengali ASR engine wrapper.
- Bengali offline model download, safe archive extraction and delete manager.
- SQLite local history and personal vocabulary storage.
- ML Kit Bengali-English on-device translation dependency.
- Android native TTS and share sheet in the current activity.

## Bengali ASR model
The selected model is sherpa-onnx streaming Zipformer Bengali (Vosk-derived). It is downloaded separately by the app; after installation inference is local. The model is Bengali-focused; mixed Bengali-English recognition is not guaranteed. Punctuation and recognition quality require real-device testing.

## Build
GitHub Actions downloads the official sherpa-onnx Android AAR and builds the debug APK with JDK 17 and Android SDK 35.

## Remaining work
The current launcher UI has not yet been wired to every new engine class. Audio import transcription, full recorder library controls, mode-aware clean/AI correction, online provider integration, and complete UI navigation still require implementation and testing. Do not treat this revision as the finished app.
