# ELORA

ELORA is a private camera-sharing project. The repository currently contains:
- A browser-based prototype in the repository root.
- An early native Android app in `android/`.

## Native Android progress

The Android app now has a CameraX local preview, front/rear camera switching, and an ongoing foreground-service notification with a Stop action. Stopping from the app or its notification removes the notification and closes the local preview.

**Important:** Native peer-to-peer live streaming, secure device pairing, and continued camera capture while the screen is locked are not implemented yet. The current Android build is a development foundation, not a finished remote-viewing app. Android's camera privacy indicator and required foreground-service status remain visible while camera access is active.

## Android APK build

Every push that changes `android/` starts the **Build ELORA Android APK** workflow.

To download a test APK:
1. Open the repository's **Actions** tab.
2. Open the latest successful **Build ELORA Android APK** run.
3. Under **Artifacts**, download `ELORA-debug-apk`.
4. Extract the ZIP and install `app-debug.apk` on an Android test phone.

This is a debug build for testing, not a production release.
