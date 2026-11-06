# ELORA signaling server

A small WebSocket signaling service for negotiating WebRTC connections between a camera owner and an approved viewer. It relays signaling messages only; media should travel peer-to-peer over WebRTC.

## Run locally

```sh
cd server
npm install
npm start
```

Health check: `http://localhost:8080/health`.

For deployment, put this behind HTTPS/WSS (for example, a managed Node host with TLS). Set `ALLOWED_ORIGINS` to a comma-separated list of exact browser origins. Native Android clients may connect without an Origin header.

## Protocol

- Camera owner sends `{"type":"CREATE_ROOM"}`; server replies `ROOM_CREATED` with a six-digit code and a private owner secret.
- Viewer sends `{"type":"JOIN_ROOM","code":"123456"}`; owner receives `VIEW_REQUEST`.
- Owner sends `APPROVE_VIEWER` or `DECLINE_VIEWER` with the viewer ID and owner secret.
- After approval, peers exchange WebRTC offer, answer, and ICE candidate objects inside `SIGNAL` messages.
- Owner sends `CLOSE_ROOM` to end the session.

Rooms expire after ten minutes, have a three-viewer limit, and join attempts are rate-limited per IP. The six-digit code is a discovery code, not a substitute for the owner secret or user approval.

This is an early signaling component. It does not host or encrypt media itself, does not provide TURN relay, and has not yet been integrated into the Android or browser clients. Deploy only behind TLS and review before production use.
