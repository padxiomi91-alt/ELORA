# ELORA

ELORA is a private camera-sharing project. The repository currently contains:
- A browser-based prototype in the repository root.
- An early native Android foundation in `android/`.

## Android build

Every push that changes `android/` starts the **Build ELORA Android APK** workflow.

To download a test APK:
1. Open the repository's **Actions** tab.
2. Open the latest successful **Build ELORA Android APK** run.
3. Under **Artifacts**, download `ELORA-debug-apk`.
4. Extract the ZIP and install `app-debug.apk` on an Android test phone.

This is an early development build, not a production release. The current native Android screen tests the camera foreground-service lifecycle and its visible stop notification; native live streaming and locked-screen camera operation are not implemented yet.

Android's camera privacy indicator and required foreground-service notification remain visible while camera access is active. Stopping the service removes ELORA's ongoing notification.
