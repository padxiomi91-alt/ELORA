# ELORA

ELORA is a private camera-sharing project. The repository contains:
- A browser-based prototype in the repository root.
- A native Android app in `android/`.
- A Node.js WebSocket signaling server in `server/`.

## Native Android status

The native app has a camera/viewer screen, six-digit room creation and joining controls, viewer approval/decline, local and remote WebRTC video rendering, front/rear camera switching, and a camera foreground service. The service owns the camera publishing session so it can continue independently of the Activity, with Android's required camera notification and Stop action.

The Android client creates stable owner and viewer identities and encrypts them, together with trusted-device credentials, using an AES key held by Android Keystore. The signaling protocol supports first-time approval, automatic approval of a previously trusted device, and server-side device revocation. The server stores only credential hashes in its trust store. Protocol tests cover initial approval, trusted reconnection, wrong credentials, and revocation.

## Signaling backend deployment

A Cloudflare Worker version is provided in `cloudflare/`. Its source and configuration pass the Wrangler dry-run validation workflow.

To deploy it:
1. Create a Cloudflare API token with Workers Scripts edit permission and note the Cloudflare account ID.
2. Add GitHub repository Actions secrets named `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
3. For manual deployment, run **Actions → Deploy ELORA signaling backend → Run workflow**.
4. The Android build already includes the deployed secure WebSocket address; it can also be changed in the app settings if the Worker hostname changes.

The Cloudflare Worker uses Durable Object SQLite storage for trusted-device hashes and room state. Do not share API tokens. Deployment still requires the repository owner’s Cloudflare account credentials.

## Not yet ready for real-world use

- The foreground service now owns the WebRTC camera session. Streaming with the screen locked still needs physical verification on the target Android phones.
- No TURN relay is configured yet. STUN-only direct connections may fail on restrictive mobile networks or symmetric NAT; a TURN relay is needed for broader connectivity.
- The app does not yet expose a trusted-device management screen, although the signaling server and controller include the revocation operation.
- Physical testing between two Android phones is still required.

Android's camera privacy indicator and required foreground-service notification are intentionally not hidden. The notification's Stop action ends the active camera session and removes the notification.

## Build checks

- The signaling-server protocol test workflow runs on server changes.
- The Android workflow builds a debug APK on Android source changes.

To download a test APK, open the repository's **Actions** tab, select the latest successful **Build ELORA Android APK** run, and download the `ELORA-debug-apk` artifact. A successful build only confirms compilation; it does not establish that live streaming works on physical devices.
