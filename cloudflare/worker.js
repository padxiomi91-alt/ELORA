import { DurableObject } from "cloudflare:workers";

const ROOM_TTL = 10 * 60 * 1000;
const MAX_VIEWERS = 1;
const MAX_MESSAGE = 64 * 1024;
const ALLOWED_ORIGINS = new Set(["https://padxiomi91-alt.github.io"]);

const json = (type, fields = {}) => JSON.stringify({ type, ...fields });
const validId = value => typeof value === "string" && /^[A-Za-z0-9_-]{16,80}$/.test(value);
const bytesToHex = bytes => [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, "0")).join("");
const randomBytes = size => crypto.getRandomValues(new Uint8Array(size));
const randomToken = (size = 32) => {
  const bytes = randomBytes(size);
  let binary = "";
  bytes.forEach(b => binary += String.fromCharCode(b));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
};
const digest = async value => bytesToHex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
const same = (a, b) => {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
};
const base64Url = bytes => {
  let binary = "";
  new Uint8Array(bytes).forEach(b => binary += String.fromCharCode(b));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
};
const base64UrlText = value => base64Url(new TextEncoder().encode(value));
const mintLiveKitToken = async (env, roomName, identity, role) => {
  const apiKey = env.LIVEKIT_API_KEY;
  const apiSecret = env.LIVEKIT_API_SECRET;
  const livekitUrl = env.LIVEKIT_URL;
  if (!apiKey || !apiSecret || !livekitUrl) throw new Error("LIVEKIT_NOT_CONFIGURED");
  const now = Math.floor(Date.now() / 1000);
  const header = base64UrlText(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const claims = {
    iss: apiKey,
    sub: identity,
    name: role === "owner" ? "ELORA Camera" : "ELORA Viewer",
    nbf: now - 5,
    iat: now,
    exp: now + 300,
    video: {
      roomJoin: true,
      room: roomName,
      canPublish: role === "owner",
      canSubscribe: role === "viewer",
      canPublishData: false,
      ...(role === "owner" ? { canPublishSources: ["camera"] } : {})
    }
  };
  const payload = base64UrlText(JSON.stringify(claims));
  const signingInput = header + "." + payload;
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(apiSecret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(signingInput));
  return { url: livekitUrl, token: signingInput + "." + base64Url(signature) };
};
const send = (ws, type, fields = {}) => {
  if (ws && ws.readyState === WebSocket.OPEN) {
    try { ws.send(json(type, fields)); } catch {}
  }
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return Response.json({ status: "ok", service: "elora-signaling" });
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("ELORA signaling endpoint. Use WebSocket at /.", { status: 426 });
    }
    const origin = request.headers.get("Origin");
    if (origin && !ALLOWED_ORIGINS.has(origin)) return new Response("Origin not allowed", { status: 403 });
    const id = env.ELORA_SIGNALLING.idFromName("elora-global");
    return env.ELORA_SIGNALLING.get(id).fetch(request);
  }
};

export class EloraSignaling extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.connections = new Map();
    this.ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS rooms (code TEXT PRIMARY KEY, owner_id TEXT NOT NULL, secret_hash TEXT NOT NULL, expires_at INTEGER NOT NULL)");
    this.ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS trusted_devices (owner_id TEXT NOT NULL, device_id TEXT NOT NULL, token_hash TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(owner_id, device_id))");
    this.ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS rate_limits (ip TEXT PRIMARY KEY, window_start INTEGER NOT NULL, attempts INTEGER NOT NULL)");
    for (const ws of this.ctx.getWebSockets()) {
      const attachment = ws.deserializeAttachment();
      if (attachment) this.connections.set(ws, attachment);
    }
    this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request) {
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const now = Date.now();
    const row = this.ctx.storage.sql.exec("SELECT window_start, attempts FROM rate_limits WHERE ip = ?", ip).toArray()[0];
    if (row && now - row.window_start < 600000 && row.attempts >= 20) {
      return new Response("Rate limit exceeded", { status: 429 });
    }
    if (!row || now - row.window_start >= 600000) {
      this.ctx.storage.sql.exec("INSERT OR REPLACE INTO rate_limits (ip, window_start, attempts) VALUES (?, ?, 1)", ip, now);
    } else {
      this.ctx.storage.sql.exec("UPDATE rate_limits SET attempts = attempts + 1 WHERE ip = ?", ip);
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    const attachment = { role: null, roomCode: null, viewerId: null, deviceId: null, approved: false, requested: false };
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(attachment);
    this.connections.set(server, attachment);
    return new Response(null, { status: 101, webSocket: client });
  }

  getAttachment(ws) {
    return ws.deserializeAttachment() || this.connections.get(ws) || {};
  }

  setAttachment(ws, attachment) {
    ws.serializeAttachment(attachment);
    this.connections.set(ws, attachment);
  }

  room(code) {
    return this.ctx.storage.sql.exec("SELECT code, owner_id, secret_hash, expires_at FROM rooms WHERE code = ?", code).toArray()[0] || null;
  }

  socketsFor(code) {
    return [...this.ctx.getWebSockets()].filter(ws => {
      const a = this.getAttachment(ws);
      return a.roomCode === code && ws.readyState === WebSocket.OPEN;
    });
  }

  ownerSocket(code) {
    return this.socketsFor(code).find(ws => this.getAttachment(ws).role === "owner") || null;
  }

  async webSocketMessage(ws, message) {
    const raw = typeof message === "string" ? message : new TextDecoder().decode(message);
    if (raw.length > MAX_MESSAGE) return send(ws, "ERROR", { code: "MESSAGE_TOO_LARGE" });
    let msg;
    try { msg = JSON.parse(raw); } catch { return send(ws, "ERROR", { code: "INVALID_JSON" }); }
    if (!msg || typeof msg.type !== "string") return send(ws, "ERROR", { code: "INVALID_MESSAGE" });
    const a = this.getAttachment(ws);

    if (msg.type === "CREATE_ROOM") {
      if (a.role) return send(ws, "ERROR", { code: "ALREADY_IN_ROOM" });
      let code;
      do { code = String(100000 + (new DataView(randomBytes(4).buffer).getUint32(0) % 900000)); } while (this.room(code));
      const ownerId = validId(msg.ownerId) ? msg.ownerId : randomToken(24);
      const secret = randomToken(32);
      const expiresAt = Date.now() + ROOM_TTL;
      this.ctx.storage.sql.exec("INSERT INTO rooms (code, owner_id, secret_hash, expires_at) VALUES (?, ?, ?, ?)", code, ownerId, await digest(secret), expiresAt);
      this.setAttachment(ws, { ...a, role: "owner", roomCode: code });
      await this.ctx.storage.setAlarm(expiresAt);
      return send(ws, "ROOM_CREATED", { code, ownerSecret: secret, ownerId, expiresAt });
    }

    if (msg.type === "JOIN_ROOM") {
      if (a.role || typeof msg.code !== "string" || !/^\d{6}$/.test(msg.code)) return send(ws, "ERROR", { code: "INVALID_CODE" });
      const room = this.room(msg.code);
      if (!room || room.expires_at < Date.now()) {
        if (room) await this.removeRoom(msg.code, "expired");
        return send(ws, "ERROR", { code: "ROOM_NOT_FOUND" });
      }
      const viewers = this.socketsFor(msg.code).filter(s => this.getAttachment(s).role === "viewer");
      if (viewers.length >= MAX_VIEWERS) return send(ws, "ERROR", { code: "ROOM_FULL" });
      const viewerId = randomToken(18);
      const deviceId = validId(msg.deviceId) ? msg.deviceId : null;
      this.setAttachment(ws, { ...a, role: "viewer", roomCode: msg.code, viewerId, deviceId, approved: false, requested: false });
      return send(ws, "JOINED", { viewerId, ownerId: room.owner_id, deviceId, waitingForApproval: true });
    }

    if (!a.roomCode) return send(ws, "ERROR", { code: "NOT_IN_ROOM" });
    const room = this.room(a.roomCode);
    if (!room || room.expires_at < Date.now()) {
      if (room) await this.removeRoom(room.code, "expired");
      return send(ws, "ERROR", { code: "ROOM_NOT_FOUND" });
    }

    if (msg.type === "PING") {
      const expiresAt = Date.now() + ROOM_TTL;
      this.ctx.storage.sql.exec("UPDATE rooms SET expires_at = ? WHERE code = ?", expiresAt, room.code);
      await this.ctx.storage.setAlarm(expiresAt);
      return send(ws, "PONG");
    }

    if (a.role === "viewer" && msg.type === "AUTH_TRUST") {
      if (a.approved) return send(ws, "ERROR", { code: "INVALID_PAIRING_STATE" });
      const record = a.deviceId ? this.ctx.storage.sql.exec(
        "SELECT token_hash FROM trusted_devices WHERE owner_id = ? AND device_id = ?", room.owner_id, a.deviceId
      ).toArray()[0] : null;
      const credential = typeof msg.trustCredential === "string" ? msg.trustCredential : "";
      const trusted = !!(record && credential && same(record.token_hash, await digest(credential)));
      if (trusted) {
        if (this.socketsFor(room.code).some(s => s !== ws && this.getAttachment(s).role === "viewer" && this.getAttachment(s).approved)) {
          return send(ws, "ERROR", { code: "VIEWER_ALREADY_ACTIVE" });
        }
        this.setAttachment(ws, { ...a, approved: true });
        try {
          const [viewerGrant, ownerGrant] = await Promise.all([
            mintLiveKitToken(this.env, room.code, a.viewerId, "viewer"),
            mintLiveKitToken(this.env, room.code, room.owner_id, "owner")
          ]);
          send(ws, "APPROVED", { trusted: true, livekitUrl: viewerGrant.url, livekitToken: viewerGrant.token });
          send(this.ownerSocket(room.code), "VIEWER_AUTO_APPROVED", {
            viewerId: a.viewerId,
            deviceId: a.deviceId,
            livekitUrl: ownerGrant.url,
            livekitToken: ownerGrant.token
          });
        } catch (error) {
          send(ws, "ERROR", { code: "LIVEKIT_TOKEN_FAILED", message: error.message });
          send(this.ownerSocket(room.code), "ERROR", { code: "LIVEKIT_TOKEN_FAILED", message: error.message });
        }
        return;
      }
      if (!a.requested) {
        this.setAttachment(ws, { ...a, requested: true });
        send(this.ownerSocket(room.code), "VIEW_REQUEST", { viewerId: a.viewerId, deviceId: a.deviceId, returningDevice: !!record });
      }
      return;
    }

    if (a.role === "owner") {
      if (!same(room.secret_hash, await digest(typeof msg.ownerSecret === "string" ? msg.ownerSecret : ""))) {
        return send(ws, "ERROR", { code: "UNAUTHORIZED" });
      }
      if (msg.type === "APPROVE_VIEWER" || msg.type === "DECLINE_VIEWER") {
        const viewer = this.socketsFor(room.code).find(s => {
          const v = this.getAttachment(s);
          return v.role === "viewer" && v.viewerId === msg.viewerId;
        });
        if (!viewer) return send(ws, "ERROR", { code: "VIEWER_NOT_FOUND" });
        const v = this.getAttachment(viewer);
        if (msg.type === "APPROVE_VIEWER") {
          if (this.socketsFor(room.code).some(s => s !== viewer && this.getAttachment(s).role === "viewer" && this.getAttachment(s).approved)) {
            return send(ws, "ERROR", { code: "VIEWER_ALREADY_ACTIVE" });
          }
          this.setAttachment(viewer, { ...v, approved: true });
          if (v.deviceId) {
            const credential = randomToken(32);
            this.ctx.storage.sql.exec(
              "INSERT OR REPLACE INTO trusted_devices (owner_id, device_id, token_hash, created_at) VALUES (?, ?, ?, ?)",
              room.owner_id, v.deviceId, await digest(credential), Date.now()
            );
            send(viewer, "TRUST_CREDENTIAL", { credential, ownerId: room.owner_id, deviceId: v.deviceId });
          }
          try {
            const [viewerGrant, ownerGrant] = await Promise.all([
              mintLiveKitToken(this.env, room.code, v.viewerId, "viewer"),
              mintLiveKitToken(this.env, room.code, room.owner_id, "owner")
            ]);
            send(viewer, "APPROVED", { livekitUrl: viewerGrant.url, livekitToken: viewerGrant.token });
            send(ws, "APPROVAL_CONFIRMED", {
              viewerId: v.viewerId,
              livekitUrl: ownerGrant.url,
              livekitToken: ownerGrant.token
            });
          } catch (error) {
            send(viewer, "ERROR", { code: "LIVEKIT_TOKEN_FAILED", message: error.message });
            send(ws, "ERROR", { code: "LIVEKIT_TOKEN_FAILED", message: error.message });
          }
        } else {
          send(viewer, "DECLINED");
          viewer.close(1000, "Request declined");
        }
        return;
      }
      if (msg.type === "REVOKE_DEVICE") {
        if (!validId(msg.deviceId)) return send(ws, "ERROR", { code: "INVALID_DEVICE_ID" });
        this.ctx.storage.sql.exec("DELETE FROM trusted_devices WHERE owner_id = ? AND device_id = ?", room.owner_id, msg.deviceId);
        return send(ws, "DEVICE_REVOKED", { deviceId: msg.deviceId });
      }
      if (msg.type === "SIGNAL") {
        const viewer = this.socketsFor(room.code).find(s => this.getAttachment(s).role === "viewer" && this.getAttachment(s).approved);
        if (!viewer) return send(ws, "ERROR", { code: "NO_APPROVED_VIEWER" });
        return send(viewer, "SIGNAL", { payload: msg.payload });
      }
      if (msg.type === "CLOSE_ROOM") return this.removeRoom(room.code, "owner_closed");
    }

    if (a.role === "viewer") {
      if (!a.approved) return send(ws, "ERROR", { code: "NOT_APPROVED" });
      if (msg.type === "SIGNAL") return send(this.ownerSocket(room.code), "SIGNAL", { payload: msg.payload });
    }
    send(ws, "ERROR", { code: "UNKNOWN_ACTION" });
  }

  async removeRoom(code, reason) {
    const sockets = this.socketsFor(code);
    this.ctx.storage.sql.exec("DELETE FROM rooms WHERE code = ?", code);
    for (const ws of sockets) {
      if (this.getAttachment(ws).role === "viewer") send(ws, "ROOM_CLOSED", { reason });
      try { ws.close(1000, reason); } catch {}
      this.connections.delete(ws);
    }
    return true;
  }

  async alarm() {
    const now = Date.now();
    const expired = this.ctx.storage.sql.exec("SELECT code FROM rooms WHERE expires_at <= ?", now).toArray();
    for (const row of expired) await this.removeRoom(row.code, "expired");
    const next = this.ctx.storage.sql.exec("SELECT MIN(expires_at) AS next_expiry FROM rooms").toArray()[0]?.next_expiry;
    if (next) await this.ctx.storage.setAlarm(next);
  }

  async webSocketClose(ws) {
    const a = this.getAttachment(ws);
    this.connections.delete(ws);
    if (!a.roomCode) return;
    if (a.role === "owner") await this.removeRoom(a.roomCode, "owner_disconnected");
    else if (a.role === "viewer") send(this.ownerSocket(a.roomCode), "PEER_OFFLINE");
  }

  async webSocketError(ws) {
    await this.webSocketClose(ws);
  }
}
