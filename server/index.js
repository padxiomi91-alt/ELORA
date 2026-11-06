const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const { randomBytes, randomInt, timingSafeEqual, createHash } = require("node:crypto");
const WebSocket = require("ws");

const PORT = Number(process.env.PORT || 8080);
const ROOM_TTL_MS = 10 * 60 * 1000;
const MAX_VIEWERS_PER_ROOM = 1;
const MAX_MESSAGE_BYTES = 64 * 1024;
const TRUST_FILE = process.env.ELORA_TRUST_FILE || path.join(__dirname, "data", "trusted-devices.json");
const rooms = new Map();
const attempts = new Map();
let trustStore = loadTrustStore();

function loadTrustStore() {
  try {
    const parsed = JSON.parse(fs.readFileSync(TRUST_FILE, "utf8"));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
  } catch (error) {
    if (error.code !== "ENOENT") console.error("Could not read trust store:", error.message);
    return {};
  }
}
function saveTrustStore() {
  fs.mkdirSync(path.dirname(TRUST_FILE), { recursive: true });
  const temp = TRUST_FILE + ".tmp";
  fs.writeFileSync(temp, JSON.stringify(trustStore), { mode: 0o600 });
  fs.renameSync(temp, TRUST_FILE);
}
function trustKey(ownerId, deviceId) { return ownerId + ":" + deviceId; }
function validId(value) { return typeof value === "string" && /^[A-Za-z0-9_-]{16,80}$/.test(value); }
function tokenHash(token) { return createHash("sha256").update(token).digest("hex"); }
function randomCode() { return String(randomInt(100000, 1000000)); }
function randomId() { return randomBytes(24).toString("base64url"); }
function sameSecret(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const aa = Buffer.from(a), bb = Buffer.from(b);
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}
function send(socket, type, data = {}) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type, ...data }));
}
function parseMessage(raw) {
  try { const value = JSON.parse(raw.toString()); return value && typeof value === "object" && !Array.isArray(value) ? value : null; }
  catch { return null; }
}
function allowedOrigin(origin) {
  const configured = (process.env.ALLOWED_ORIGINS || "").split(",").map(x => x.trim()).filter(Boolean);
  return !origin || configured.length === 0 || configured.includes(origin);
}
function rateAllowed(ip) {
  const now = Date.now(), entry = attempts.get(ip);
  if (!entry || now - entry.start > 600000) { attempts.set(ip, { start: now, count: 1 }); return true; }
  return ++entry.count <= 20;
}
function removeRoom(code, reason) {
  const room = rooms.get(code); if (!room) return;
  rooms.delete(code); send(room.owner, "ROOM_CLOSED", { reason });
  for (const viewer of room.viewers.values()) { send(viewer.socket, "ROOM_CLOSED", { reason }); viewer.socket.close(1000, "Room closed"); }
  if (room.owner.readyState === WebSocket.OPEN) room.owner.close(1000, "Room closed");
}
function relay(room, from, payload) {
  const peer = from === room.owner ? [...room.viewers.values()].find(v => v.approved)?.socket : room.owner;
  if (!peer || peer.readyState !== WebSocket.OPEN) return send(from, "PEER_OFFLINE");
  send(peer, "SIGNAL", { payload });
}
const server = http.createServer((req, res) => {
  if (req.url === "/health") { res.writeHead(200, { "content-type": "application/json" }); return res.end(JSON.stringify({ ok: true, service: "ELORA signaling", rooms: rooms.size })); }
  res.writeHead(404); res.end("Not found");
});
const wss = new WebSocket.Server({ noServer: true, maxPayload: MAX_MESSAGE_BYTES });
server.on("upgrade", (req, socket, head) => {
  if (!allowedOrigin(req.headers.origin)) { socket.write("HTTP/1.1 403 Forbidden\r\n\r\n"); return socket.destroy(); }
  wss.handleUpgrade(req, socket, head, ws => wss.emit("connection", ws, req));
});
wss.on("connection", (ws, req) => {
  if (!rateAllowed(req.socket.remoteAddress || "unknown")) { send(ws, "ERROR", { code: "RATE_LIMITED" }); return ws.close(1008, "Rate limited"); }
  ws.role = null; ws.roomCode = null; ws.viewerId = null;
  ws.on("message", raw => {
    const msg = parseMessage(raw);
    if (!msg || typeof msg.type !== "string") return send(ws, "ERROR", { code: "BAD_MESSAGE" });
    if (msg.type === "CREATE_ROOM") {
      if (ws.role) return send(ws, "ERROR", { code: "ALREADY_REGISTERED" });
      const ownerId = validId(msg.ownerId) ? msg.ownerId : randomId();
      let code; do { code = randomCode(); } while (rooms.has(code));
      const secret = randomBytes(32).toString("base64url");
      const room = { code, secret, ownerId, owner: ws, viewers: new Map(), expiresAt: Date.now() + ROOM_TTL_MS };
      rooms.set(code, room); ws.role = "owner"; ws.roomCode = code;
      return send(ws, "ROOM_CREATED", { code, ownerId, ownerSecret: secret, expiresInSeconds: ROOM_TTL_MS / 1000 });
    }
    if (msg.type === "JOIN_ROOM") {
      if (ws.role || typeof msg.code !== "string" || !/^\d{6}$/.test(msg.code)) return send(ws, "ERROR", { code: "INVALID_CODE" });
      const room = rooms.get(msg.code);
      if (!room || room.expiresAt < Date.now()) { if (room) removeRoom(msg.code, "expired"); return send(ws, "ERROR", { code: "ROOM_NOT_FOUND" }); }
      if (room.viewers.size >= MAX_VIEWERS_PER_ROOM) return send(ws, "ERROR", { code: "ROOM_FULL" });
      const viewerId = randomId();
      const deviceId = validId(msg.deviceId) ? msg.deviceId : null;
      const viewer = { socket: ws, approved: false, deviceId, requested: false };
      room.viewers.set(viewerId, viewer);
      ws.role = "viewer"; ws.roomCode = msg.code; ws.viewerId = viewerId;
      // Reveal the stable owner identity only after the short-lived room code has joined.
      // The viewer then presents only the credential belonging to this owner.
      send(ws, "JOINED", { viewerId, ownerId: room.ownerId, deviceId, waitingForApproval: true });
      return;
    }
    const room = rooms.get(ws.roomCode);
    if (!room) return send(ws, "ERROR", { code: "ROOM_NOT_FOUND" });
    if (ws.role === "viewer" && msg.type === "AUTH_TRUST") {
      const viewer = room.viewers.get(ws.viewerId);
      if (!viewer || viewer.approved) return send(ws, "ERROR", { code: "INVALID_PAIRING_STATE" });
      const record = viewer.deviceId && trustStore[trustKey(room.ownerId, viewer.deviceId)];
      const credential = typeof msg.trustCredential === "string" ? msg.trustCredential : "";
      const trusted = !!(record && credential && sameSecret(record.tokenHash, tokenHash(credential)));
      if (trusted) {
        if ([...room.viewers.values()].some(v => v !== viewer && v.approved)) return send(ws, "ERROR", { code: "VIEWER_ALREADY_ACTIVE" });
        viewer.approved = true;
        send(ws, "APPROVED", { trusted: true });
      } else if (!viewer.requested) {
        viewer.requested = true;
        send(room.owner, "VIEW_REQUEST", { viewerId: ws.viewerId, deviceId: viewer.deviceId, returningDevice: !!record });
      }
      return;
    }
    if (ws.role === "owner") {
      if (!sameSecret(msg.ownerSecret, room.secret)) return send(ws, "ERROR", { code: "UNAUTHORIZED" });
      if (msg.type === "APPROVE_VIEWER" || msg.type === "DECLINE_VIEWER") {
        const viewer = room.viewers.get(msg.viewerId);
        if (!viewer) return send(ws, "ERROR", { code: "VIEWER_NOT_FOUND" });
        if (msg.type === "APPROVE_VIEWER" && [...room.viewers.entries()].some(([id, v]) => id !== msg.viewerId && v.approved)) return send(ws, "ERROR", { code: "VIEWER_ALREADY_ACTIVE" });
        if (msg.type === "APPROVE_VIEWER") {
          viewer.approved = true;
          if (viewer.deviceId) {
            const credential = randomBytes(32).toString("base64url");
            trustStore[trustKey(room.ownerId, viewer.deviceId)] = { tokenHash: tokenHash(credential), createdAt: new Date().toISOString() };
            try { saveTrustStore(); send(viewer.socket, "TRUST_CREDENTIAL", { credential, ownerId: room.ownerId, deviceId: viewer.deviceId }); }
            catch (error) { delete trustStore[trustKey(room.ownerId, viewer.deviceId)]; send(ws, "ERROR", { code: "TRUST_STORAGE_FAILED" }); return; }
          }
        } else {
          viewer.approved = false; viewer.socket.close(1000, "Request declined"); room.viewers.delete(msg.viewerId);
        }
        send(viewer.socket, msg.type === "APPROVE_VIEWER" ? "APPROVED" : "DECLINED");
        return;
      }
      if (msg.type === "REVOKE_DEVICE") {
        if (!validId(msg.deviceId)) return send(ws, "ERROR", { code: "INVALID_DEVICE_ID" });
        delete trustStore[trustKey(room.ownerId, msg.deviceId)];
        try { saveTrustStore(); send(ws, "DEVICE_REVOKED", { deviceId: msg.deviceId }); }
        catch (error) { send(ws, "ERROR", { code: "TRUST_STORAGE_FAILED" }); }
        return;
      }
      if (msg.type === "SIGNAL") {
        if (![...room.viewers.values()].some(v => v.approved)) return send(ws, "ERROR", { code: "NO_APPROVED_VIEWER" });
        return relay(room, ws, msg.payload);
      }
      if (msg.type === "CLOSE_ROOM") return removeRoom(room.code, "owner_closed");
    } else if (ws.role === "viewer") {
      const viewer = room.viewers.get(ws.viewerId);
      if (!viewer || !viewer.approved) return send(ws, "ERROR", { code: "NOT_APPROVED" });
      if (msg.type === "SIGNAL") return relay(room, ws, msg.payload);
    }
    send(ws, "ERROR", { code: "UNKNOWN_ACTION" });
  });
  ws.on("close", () => {
    if (!ws.roomCode) return;
    const room = rooms.get(ws.roomCode); if (!room) return;
    if (ws.role === "owner" && room.owner === ws) removeRoom(room.code, "owner_disconnected");
    else if (ws.role === "viewer") room.viewers.delete(ws.viewerId);
  });
});
const expiryTimer = setInterval(() => {
  const now = Date.now();
  for (const [code, room] of rooms) if (room.expiresAt <= now) removeRoom(code, "expired");
  for (const [ip, entry] of attempts) if (now - entry.start > 600000) attempts.delete(ip);
}, 30000);
expiryTimer.unref();
server.listen(PORT, "0.0.0.0", () => console.log(`ELORA signaling listening on port ${PORT}`));
