const http = require("node:http");
const { randomBytes, randomInt, timingSafeEqual } = require("node:crypto");
const WebSocket = require("ws");

const PORT = Number(process.env.PORT || 8080);
const ROOM_TTL_MS = 10 * 60 * 1000;
// ELORA's first release allows one private viewer per camera session.
const MAX_VIEWERS_PER_ROOM = 1;
const MAX_MESSAGE_BYTES = 64 * 1024;
const rooms = new Map();
const attempts = new Map();

function randomCode() {
  return String(randomInt(100000, 1000000));
}
function randomId() {
  return randomBytes(16).toString("hex");
}
function sameSecret(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const aa = Buffer.from(a);
  const bb = Buffer.from(b);
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}
function send(socket, type, data = {}) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify({ type, ...data }));
  }
}
function parseMessage(raw) {
  try {
    const value = JSON.parse(raw.toString());
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    return value;
  } catch {
    return null;
  }
}
function allowedOrigin(origin) {
  const configured = (process.env.ALLOWED_ORIGINS || "").split(",").map(x => x.trim()).filter(Boolean);
  if (!origin) return true; // Native Android clients do not send an Origin header.
  return configured.length === 0 || configured.includes(origin);
}
function rateAllowed(ip) {
  const now = Date.now();
  const entry = attempts.get(ip);
  if (!entry || now - entry.start > 10 * 60 * 1000) {
    attempts.set(ip, { start: now, count: 1 });
    return true;
  }
  entry.count += 1;
  return entry.count <= 20;
}
function removeRoom(code, reason) {
  const room = rooms.get(code);
  if (!room) return;
  rooms.delete(code);
  send(room.owner, "ROOM_CLOSED", { reason });
  for (const viewer of room.viewers.values()) {
    send(viewer.socket, "ROOM_CLOSED", { reason });
    viewer.socket.close(1000, "Room closed");
  }
  if (room.owner.readyState === WebSocket.OPEN) room.owner.close(1000, "Room closed");
}
function relay(room, from, payload) {
  const peer = from === room.owner ? [...room.viewers.values()].find(v => v.approved)?.socket : room.owner;
  if (!peer || peer.readyState !== WebSocket.OPEN) {
    send(from, "PEER_OFFLINE");
    return;
  }
  send(peer, "SIGNAL", { payload });
}

const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: true, service: "ELORA signaling", rooms: rooms.size }));
    return;
  }
  res.writeHead(404);
  res.end("Not found");
});
const wss = new WebSocket.Server({ noServer: true, maxPayload: MAX_MESSAGE_BYTES });

server.on("upgrade", (req, socket, head) => {
  if (!allowedOrigin(req.headers.origin)) {
    socket.write("HTTP/1.1 403 Forbidden\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, ws => wss.emit("connection", ws, req));
});

wss.on("connection", (ws, req) => {
  const ip = req.socket.remoteAddress || "unknown";
  if (!rateAllowed(ip)) {
    send(ws, "ERROR", { code: "RATE_LIMITED", message: "Too many connection attempts. Try again later." });
    ws.close(1008, "Rate limited");
    return;
  }
  ws.role = null;
  ws.roomCode = null;
  ws.viewerId = null;

  ws.on("message", raw => {
    const msg = parseMessage(raw);
    if (!msg || typeof msg.type !== "string") {
      send(ws, "ERROR", { code: "BAD_MESSAGE" });
      return;
    }

    if (msg.type === "CREATE_ROOM") {
      if (ws.role) return send(ws, "ERROR", { code: "ALREADY_REGISTERED" });
      let code;
      do { code = randomCode(); } while (rooms.has(code));
      const secret = randomBytes(32).toString("base64url");
      const room = { code, secret, owner: ws, viewers: new Map(), expiresAt: Date.now() + ROOM_TTL_MS };
      rooms.set(code, room);
      ws.role = "owner";
      ws.roomCode = code;
      send(ws, "ROOM_CREATED", { code, ownerSecret: secret, expiresInSeconds: ROOM_TTL_MS / 1000 });
      return;
    }

    if (msg.type === "JOIN_ROOM") {
      if (ws.role || typeof msg.code !== "string" || !/^\d{6}$/.test(msg.code)) {
        return send(ws, "ERROR", { code: "INVALID_CODE" });
      }
      const room = rooms.get(msg.code);
      if (!room || room.expiresAt < Date.now()) {
        if (room) removeRoom(msg.code, "expired");
        return send(ws, "ERROR", { code: "ROOM_NOT_FOUND" });
      }
      if (room.viewers.size >= MAX_VIEWERS_PER_ROOM) {
        return send(ws, "ERROR", { code: "ROOM_FULL" });
      }
      const viewerId = randomId();
      room.viewers.set(viewerId, { socket: ws, approved: false });
      ws.role = "viewer";
      ws.roomCode = msg.code;
      ws.viewerId = viewerId;
      send(ws, "JOINED", { viewerId, waitingForApproval: true });
      send(room.owner, "VIEW_REQUEST", { viewerId });
      return;
    }

    const room = rooms.get(ws.roomCode);
    if (!room) return send(ws, "ERROR", { code: "ROOM_NOT_FOUND" });

    if (ws.role === "owner") {
      if (!sameSecret(msg.ownerSecret, room.secret)) {
        return send(ws, "ERROR", { code: "UNAUTHORIZED" });
      }
      if (msg.type === "APPROVE_VIEWER" || msg.type === "DECLINE_VIEWER") {
        const viewer = room.viewers.get(msg.viewerId);
        if (!viewer) return send(ws, "ERROR", { code: "VIEWER_NOT_FOUND" });
        if (msg.type === "APPROVE_VIEWER" && [...room.viewers.entries()].some(([id, item]) => id !== msg.viewerId && item.approved)) {
          return send(ws, "ERROR", { code: "VIEWER_ALREADY_ACTIVE" });
        }
        viewer.approved = msg.type === "APPROVE_VIEWER";
        send(viewer.socket, viewer.approved ? "APPROVED" : "DECLINED");
        if (!viewer.approved) {
          viewer.socket.close(1000, "Request declined");
          room.viewers.delete(msg.viewerId);
        }
        return;
      }
      if (msg.type === "SIGNAL") {
        const approved = [...room.viewers.values()].find(v => v.approved);
        if (!approved) return send(ws, "ERROR", { code: "NO_APPROVED_VIEWER" });
        relay(room, ws, msg.payload);
        return;
      }
      if (msg.type === "CLOSE_ROOM") {
        removeRoom(room.code, "owner_closed");
        return;
      }
    } else if (ws.role === "viewer") {
      const viewer = room.viewers.get(ws.viewerId);
      if (!viewer || !viewer.approved) return send(ws, "ERROR", { code: "NOT_APPROVED" });
      if (msg.type === "SIGNAL") {
        relay(room, ws, msg.payload);
        return;
      }
    }
    send(ws, "ERROR", { code: "UNKNOWN_ACTION" });
  });

  ws.on("close", () => {
    if (!ws.roomCode) return;
    const room = rooms.get(ws.roomCode);
    if (!room) return;
    if (ws.role === "owner" && room.owner === ws) {
      removeRoom(room.code, "owner_disconnected");
    } else if (ws.role === "viewer") {
      room.viewers.delete(ws.viewerId);
    }
  });
});

const expiryTimer = setInterval(() => {
  const now = Date.now();
  for (const [code, room] of rooms) {
    if (room.expiresAt <= now) removeRoom(code, "expired");
  }
  for (const [ip, entry] of attempts) {
    if (now - entry.start > 10 * 60 * 1000) attempts.delete(ip);
  }
}, 30_000);
expiryTimer.unref();

server.listen(PORT, "0.0.0.0", () => {
  console.log(`ELORA signaling listening on port ${PORT}`);
});
