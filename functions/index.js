const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

/** Generates a 6-character random alphanumeric code. */
function randomCode() {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let s = "";
  for (let i = 0; i < 6; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

/**
 * Creates a pending pair invite.
 * Returns { code, pairId }.
 */
exports.createPair = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

  const code = randomCode();
  const pairRef = db.collection("pairs").doc();
  const batch = db.batch();

  batch.set(pairRef, { members: [uid], createdAt: Date.now(), code });
  batch.set(db.collection("invites").doc(code), { pairId: pairRef.id, createdAt: Date.now() });
  batch.set(db.collection("users").doc(uid), { pairId: pairRef.id }, { merge: true });

  await batch.commit();
  return { code, pairId: pairRef.id };
});

/**
 * Joins an existing pair via a 6-character code.
 * Adds the caller to the pair's members.
 */
exports.joinPair = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

  const code = String(req.data.code || "").trim().toUpperCase();
  const invite = await db.collection("invites").doc(code).get();
  if (!invite.exists) throw new HttpsError("not-found", "Invalid or expired code");

  const pairId = invite.data().pairId;
  const pairRef = db.collection("pairs").doc(pairId);
  const pair = await pairRef.get();
  if (!pair.exists) throw new HttpsError("not-found", "Pair not found");

  const members = pair.data().members || [];
  if (members.length >= 2 && !members.includes(uid)) {
    throw new HttpsError("failed-precondition", "This pair is already full");
  }

  const batch = db.batch();
  if (!members.includes(uid)) {
    batch.update(pairRef, { members: admin.firestore.FieldValue.arrayUnion(uid) });
  }
  batch.set(db.collection("users").doc(uid), { pairId }, { merge: true });
  batch.delete(db.collection("invites").doc(code)); // one-time use

  await batch.commit();
  return { pairId };
});

/** On every new message, push a high-priority data message to the OTHER member's device. */
exports.onMessageCreated = onDocumentCreated("pairs/{pairId}/messages/{msgId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const m = snap.data();
  const pair = await db.collection("pairs").doc(event.params.pairId).get();
  const target = ((pair.data() || {}).members || []).find((u) => u !== m.from);
  if (!target) return;

  const user = await db.collection("users").doc(target).get();
  const token = user.exists && user.data().fcmToken;
  if (!token) return;

  // Notice: 'from' is a reserved FCM key in data payload, renamed to senderUid
  const data = {
    msgId: snap.id,
    type: String(m.type || ""),
    text: String(m.text || ""),
    mediaUrl: String(m.mediaUrl || ""),
    senderUid: String(m.from || ""),
    createdAt: String(m.createdAt || Date.now()),
  };
  // Data-only + high priority: wakes the app so it can update the widget and vibrate.
  const urgent = ["sos", "ptt", "vibe"].includes(data.type);
  try {
    await admin.messaging().send({
      token,
      data,
      android: { priority: "high", ttl: urgent ? 60 * 1000 : 24 * 3600 * 1000 },
    });
  } catch (e) {
    if (e.code === "messaging/registration-token-not-registered") {
      await db.collection("users").doc(target).update({ fcmToken: admin.firestore.FieldValue.delete() });
    } else {
      console.error(e);
    }
  }
});

Object.assign(exports, require("./live"), require("./cleanup"));
