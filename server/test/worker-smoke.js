const WebSocket = require("ws");

const url = process.env.ELORA_WORKER_URL;
function connect() {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    const timer = setTimeout(() => reject(new Error("WebSocket connection timed out")), 10000);
    socket.once("open", () => { clearTimeout(timer); resolve(socket); });
    socket.once("error", error => { clearTimeout(timer); reject(error); });
  });
}

function waitFor(socket, expected, timeoutMs = 8000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.off("message", listener);
      reject(new Error("Timed out waiting for " + expected));
    }, timeoutMs);
    const listener = raw => {
      let message;
      try { message = JSON.parse(raw.toString()); } catch { return; }
      if (message.type === "ERROR") {
        clearTimeout(timer);
        socket.off("message", listener);
        reject(new Error("Worker error: " + (message.code || message.message || "unknown")));
      } else if (message.type === expected) {
        clearTimeout(timer);
        socket.off("message", listener);
        resolve(message);
      }
    };
    socket.on("message", listener);
  });
}

async function main() {
  let owner, viewer;
  try {
    owner = await connect();
    const createdPromise = waitFor(owner, "ROOM_CREATED");
    owner.send(JSON.stringify({ type: "CREATE_ROOM", ownerId: "worker_smoke_owner_000001" }));
    const created = await createdPromise;

    viewer = await connect();
    const joinedPromise = waitFor(viewer, "JOINED");
    viewer.send(JSON.stringify({
      type: "JOIN_ROOM",
      code: created.code,
      deviceId: "worker_smoke_viewer_00001"
    }));
    await joinedPromise;

    const requestPromise = waitFor(owner, "VIEW_REQUEST");
    viewer.send(JSON.stringify({ type: "AUTH_TRUST", trustCredential: "" }));
    const request = await requestPromise;

    const credentialPromise = waitFor(viewer, "TRUST_CREDENTIAL");
    const approvedPromise = waitFor(viewer, "APPROVED");
    owner.send(JSON.stringify({
      type: "APPROVE_VIEWER",
      viewerId: request.viewerId,
      ownerSecret: created.ownerSecret
    }));
    await Promise.all([credentialPromise, approvedPromise]);

    const signalPromise = waitFor(owner, "SIGNAL");
    viewer.send(JSON.stringify({ type: "SIGNAL", payload: { kind: "smoke", ok: true } }));
    const signal = await signalPromise;
    if (!signal.payload || signal.payload.kind !== "smoke") throw new Error("Signaling relay returned the wrong payload.");

    const pongPromise = waitFor(owner, "PONG");
    owner.send(JSON.stringify({ type: "PING" }));
    await pongPromise;

    const closedPromise = waitFor(viewer, "ROOM_CLOSED");
    owner.send(JSON.stringify({ type: "CLOSE_ROOM", ownerSecret: created.ownerSecret }));
    await closedPromise;
    console.log("ELORA Worker WebSocket smoke test passed.");
  } finally {
    if (viewer && viewer.readyState === WebSocket.OPEN) viewer.close();
    if (owner && owner.readyState === WebSocket.OPEN) owner.close();
  }
}

if (!url || !url.startsWith("wss://")) {
  console.log("Skipping deployed Worker smoke test; ELORA_WORKER_URL is not set.");
} else {
  main().catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
