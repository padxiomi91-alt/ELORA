# ELORA Android development

The Android project currently contains:
- CameraX local preview and front/rear switching.
- A foreground camera-service notification with an explicit Stop action.
- A WebSocket client for the ELORA room/signaling protocol.
- An initial WebRTC peer/media engine.

The WebRTC engine is a foundation only. It is not yet connected to the activity UI or deployed signaling service. Remote video, viewer-side Android UI, TURN relay configuration, and camera capture that continues reliably with the screen locked still need implementation and device testing.

Builds are produced by the GitHub Actions workflow at `.github/workflows/android-apk.yml`.
