# ELORA signaling server

A small WebSocket signaling service for negotiating WebRTC connections between a camera owner and one approved viewer. It relays signaling messages only; media travels over WebRTC.

## Run locally

```sh
cd server
npm install
npm test
npm start
```

Health check: `http://localhost:8080/health`.

## Deploy safely

Put the service behind HTTPS/WSS. Set `ALLOWED_ORIGINS` to exact browser origins. Native Android clients may connect without an Origin header.

**Persistent trusted-device storage is required.** The server stores hashed pairing credentials in `server/data/trusted-devices.json` by default. Set `ELORA_TRUST_FILE` to a file on a persistent private volume in production. The directory/file must be writable by the Node process and backed up securely. Do not use ephemeral container storage or run multiple server replicas against this JSON file; use a shared transactional database before scaling horizontally. Protect the volume and restrict filesystem access.

## Pair-once protocol

1. Camera owner sends `CREATE_ROOM` with a stable random `ownerId` (16–80 URL-safe characters). The server returns a temporary six-digit room code and a session-only `ownerSecret`.
2. Viewer sends `JOIN_ROOM` with the room code and a stable random `deviceId`.
3. First-time/unrecognized devices receive `VIEW_REQUEST`; the owner must approve. On approval, the server creates a 256-bit random trust credential, stores only its SHA-256 hash against that owner/device pair, and sends the credential to the viewer in `TRUST_CREDENTIAL`.
4. On future joins, the viewer sends the same device ID and trust credential. A matching credential is auto-approved without a new owner prompt.
5. The owner can revoke a device with `REVOKE_DEVICE` and its `deviceId` plus the current `ownerSecret`. The next join from that device requires fresh approval.

The Android client must keep the owner identity/device identity stable and store trust credentials encrypted with Android Keystore. Those client-side identity storage and protocol integrations are still pending; the server-side pairing protocol alone does not yet provide end-to-end lifetime pairing in the app. Reinstalling the viewer app should lose its local credential and require approval again.

The six-digit code is only a temporary discovery code, never an authentication credential. Rooms expire after ten minutes and allow one active viewer. Connection attempts are rate-limited per IP.

## Current limitations

This service is an early signaling component. It does not relay media, provide TURN, or make WebRTC work through every NAT/firewall. Use WSS in deployment. The JSON trust store is suitable only for a single server instance on persistent storage; use a transactional shared database before horizontal scaling.