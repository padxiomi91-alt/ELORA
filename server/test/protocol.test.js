const { spawn } = require("node:child_process");
const path = require("node:path");
const os = require("node:os");
const fs = require("node:fs");
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
        if (message.type === "ERROR" && expectedType !== "ERROR") reject(new Error(message.code));
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
  const trustDir = fs.mkdtempSync(path.join(os.tmpdir(), "elora-trust-"));
  const trustFile = path.join(trustDir, "trusted-devices.json");
  child = spawn(process.execPath, [path.join(__dirname, "..", "index.js")], {
    env: { ...process.env, PORT: String(port), ALLOWED_ORIGINS: "", ELORA_TRUST_FILE: trustFile },
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
  t.after(() => { child.kill(); fs.rmSync(trustDir, { recursive: true, force: true }); });

  const owner = await connect();
  const createdPromise = waitForMessage(owner, "ROOM_CREATED");
  const ownerId = "owner_device_identity_000001";
  owner.send(JSON.stringify({ type: "CREATE_ROOM", ownerId }));
  const created = await createdPromise;
  assert.match(created.code, /^\d{6}$/);
  assert.ok(created.ownerSecret);

  const pongPromise = waitForMessage(owner, "PONG");
  owner.send(JSON.stringify({ type: "PING" }));
  await pongPromise;

  const viewer = await connect();
  const joinedPromise = waitForMessage(viewer, "JOINED");
  viewer.send(JSON.stringify({ type: "JOIN_ROOM", code: created.code, deviceId: "viewer_device_identity_0001" }));
  const joined = await joinedPromise;
  assert.equal(joined.ownerId, ownerId);
  const requestPromise = waitForMessage(owner, "VIEW_REQUEST");
  viewer.send(JSON.stringify({ type: "AUTH_TRUST", trustCredential: "" }));
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

  const credentialPromise = waitForMessage(viewer, "TRUST_CREDENTIAL");
  const approvedPromise = waitForMessage(viewer, "APPROVED");
  owner.send(JSON.stringify({
    type: "APPROVE_VIEWER",
    viewerId: joined.viewerId,
    ownerSecret: created.ownerSecret
  }));
  const trust = await credentialPromise;
  assert.equal(trust.ownerId, ownerId);
  assert.equal(trust.deviceId, "viewer_device_identity_0001");
  assert.match(trust.credential, /^[A-Za-z0-9_-]{40,}$/);
  await approvedPromise;

  const relayedPromise = waitForMessage(owner, "SIGNAL");
  viewer.send(JSON.stringify({ type: "SIGNAL", payload: { kind: "offer", sdp: "test-sdp" } }));
  const relayed = await relayedPromise;
  assert.deepEqual(relayed.payload, { kind: "offer", sdp: "test-sdp" });

  owner.close();
  viewer.close();
  await new Promise(resolve => setTimeout(resolve, 100));

  const owner2 = await connect();
  const created2Promise = waitForMessage(owner2, "ROOM_CREATED");
  owner2.send(JSON.stringify({ type: "CREATE_ROOM", ownerId }));
  const created2 = await created2Promise;
  const returningViewer = await connect();
  const joined2Promise = waitForMessage(returningViewer, "JOINED");
  const approved2Promise = waitForMessage(returningViewer, "APPROVED");
  const ownerAutoApprovedPromise = waitForMessage(owner2, "VIEWER_AUTO_APPROVED");
  returningViewer.send(JSON.stringify({ type: "JOIN_ROOM", code: created2.code, deviceId: "viewer_device_identity_0001" }));
  const joined2 = await joined2Promise;
  assert.equal(joined2.ownerId, ownerId);
  returningViewer.send(JSON.stringify({ type: "AUTH_TRUST", trustCredential: trust.credential }));
  assert.equal((await approved2Promise).trusted, true);
  const autoApproved = await ownerAutoApprovedPromise;
  assert.equal(autoApproved.deviceId, "viewer_device_identity_0001");
  assert.equal(autoApproved.viewerId, joined2.viewerId);

  returningViewer.close();
  const untrustedViewer = await connect();
  const joined3Promise = waitForMessage(untrustedViewer, "JOINED");
  untrustedViewer.send(JSON.stringify({ type: "JOIN_ROOM", code: created2.code, deviceId: "another_viewer_device_00001" }));
  assert.equal((await joined3Promise).waitingForApproval, true);
  const request3Promise = waitForMessage(owner2, "VIEW_REQUEST");
  untrustedViewer.send(JSON.stringify({ type: "AUTH_TRUST", trustCredential: "wrong-token" }));
  assert.equal((await request3Promise).returningDevice, false);

  const revokePromise = waitForMessage(owner2, "DEVICE_REVOKED");
  owner2.send(JSON.stringify({ type: "REVOKE_DEVICE", deviceId: "viewer_device_identity_0001", ownerSecret: created2.ownerSecret }));
  await revokePromise;
  untrustedViewer.close();
  await new Promise(resolve => setTimeout(resolve, 100));
  const afterRevoke = await connect();
  const joined4Promise = waitForMessage(afterRevoke, "JOINED");
  afterRevoke.send(JSON.stringify({ type: "JOIN_ROOM", code: created2.code, deviceId: "viewer_device_identity_0001" }));
  await joined4Promise;
  const revokedRequestPromise = waitForMessage(owner2, "VIEW_REQUEST");
  afterRevoke.send(JSON.stringify({ type: "AUTH_TRUST", trustCredential: trust.credential }));
  const revokedRequest = await revokedRequestPromise;
  assert.equal(revokedRequest.returningDevice, false);
  owner2.close();
  untrustedViewer.close();
  afterRevoke.close();
});
