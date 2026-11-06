const { spawn } = require("node:child_process");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const WebSocket = require("ws");

const port = 18137;
let child;
let baseUrl;

function waitForMessage(socket, expectedType, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timed out waiting for ${expectedType}`)), timeoutMs);
    const onMessage = raw => {
      const message = JSON.parse(raw.toString());
      if (message.type === expectedType || message.type === "ERROR") {
        clearTimeout(timer);
        socket.off("message", onMessage);
        if (message.type === "ERROR") reject(new Error(message.code));
        else resolve(message);
      }
    };
    socket.on("message", onMessage);
  });
}

function connect() {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(baseUrl);
    socket.once("open", () => resolve(socket));
    socket.once("error", reject);
  });
}

test("owner creates a room, approves viewer, and relays signaling", async t => {
  child = spawn(process.execPath, [path.join(__dirname, "..", "index.js")], {
    env: { ...process.env, PORT: String(port), ALLOWED_ORIGINS: "" },
    stdio: "ignore"
  });
  baseUrl = `ws://127.0.0.1:${port}`;

  let ready = false;
  for (let i = 0; i < 40; i++) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/health`);
      if (response.ok) { ready = true; break; }
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.equal(ready, true, "signaling server should start");
  t.after(() => child.kill());

  const owner = await connect();
  const createdPromise = waitForMessage(owner, "ROOM_CREATED");
  owner.send(JSON.stringify({ type: "CREATE_ROOM" }));
  const created = await createdPromise;
  assert.match(created.code, /^\d{6}$/);
  assert.ok(created.ownerSecret);

  const viewer = await connect();
  const joinedPromise = waitForMessage(viewer, "JOINED");
  const requestPromise = waitForMessage(owner, "VIEW_REQUEST");
  viewer.send(JSON.stringify({ type: "JOIN_ROOM", code: created.code }));
  const joined = await joinedPromise;
  const request = await requestPromise;
  assert.equal(request.viewerId, joined.viewerId);

  const blockedSignal = waitForMessage(viewer, "ERROR");
  viewer.send(JSON.stringify({ type: "SIGNAL", payload: { kind: "offer", sdp: "must-not-pass" } }));
  assert.equal((await blockedSignal).code, "NOT_APPROVED");

  const rejectedApproval = waitForMessage(owner, "ERROR");
  owner.send(JSON.stringify({
    type: "APPROVE_VIEWER",
    viewerId: joined.viewerId,
    ownerSecret: "incorrect-secret"
  }));
  assert.equal((await rejectedApproval).code, "UNAUTHORIZED");

  const approvedPromise = waitForMessage(viewer, "APPROVED");
  owner.send(JSON.stringify({
    type: "APPROVE_VIEWER",
    viewerId: joined.viewerId,
    ownerSecret: created.ownerSecret
  }));
  await approvedPromise;

  const relayedPromise = waitForMessage(owner, "SIGNAL");
  viewer.send(JSON.stringify({ type: "SIGNAL", payload: { kind: "offer", sdp: "test-sdp" } }));
  const relayed = await relayedPromise;
  assert.deepEqual(relayed.payload, { kind: "offer", sdp: "test-sdp" });

  owner.close();
  viewer.close();
});
