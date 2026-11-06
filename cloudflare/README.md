# ELORA Cloudflare signaling backend

This Worker provides the WebSocket signaling endpoint and uses a SQLite-backed Durable Object for temporary rooms and trusted-device credential hashes.

## Deploy

1. Install Node.js and Wrangler.
2. From this directory, run `npx wrangler login`.
3. Run `npx wrangler deploy`.
4. Copy the deployed `workers.dev` URL and use it as the app's secure WebSocket endpoint (`wss://...`).

The Durable Object binding and first SQLite migration are declared in `wrangler.toml`. Keep the Cloudflare account private and do not publish API tokens.

## Endpoints

- `/health` — simple health check.
- `/` — WebSocket signaling endpoint (WSS).

The Worker stores room metadata and hashed trust credentials in Durable Object SQLite storage. Video media is not proxied by this Worker; WebRTC carries media peer-to-peer. A TURN relay is still needed for reliable connectivity on restrictive mobile networks.

## Important

The Worker is source code only until deployed to a Cloudflare account. ELORA's Android build must be configured with the actual deployed WSS URL before it can connect. The Worker must be deployed and tested with two physical Android devices before treating pairing or video as production-ready.
