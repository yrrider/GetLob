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

  // Retry on the very unlikely invite-code collision instead of overwriting another invite.
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = randomCode();
    const pairRef = db.collection("pairs").doc();
    const inviteRef = db.collection("invites").doc(code);
    const userRef = db.collection("users").doc(uid);
    const result = await db.runTransaction(async (tx) => {
      const [invite, user] = await Promise.all([tx.get(inviteRef), tx.get(userRef)]);
      if (invite.exists) return null;
      const oldPairId = user.exists ? user.get("pairId") : "";
      const oldPair = oldPairId ? await tx.get(db.collection("pairs").doc(oldPairId)) : null;
      if (oldPair && oldPair.exists) {
        tx.update(oldPair.ref, { members: admin.firestore.FieldValue.arrayRemove(uid) });
        const oldCode = oldPair.get("code");
        if (oldCode) tx.delete(db.collection("invites").doc(oldCode));
      }
      tx.set(pairRef, { members: [uid], createdAt: Date.now(), code });
      tx.set(inviteRef, { pairId: pairRef.id, createdAt: Date.now() });
      tx.set(userRef, { pairId: pairRef.id }, { merge: true });
      return { code, pairId: pairRef.id };
    });
    if (result) return result;
  }
  throw new HttpsError("aborted", "Could not reserve an invite code; please retry");
});

/**
 * Joins an existing pair via a 6-character code.
 * Adds the caller to the pair's members.
 */
exports.joinPair = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

  const code = String(req.data.code || "").trim().toUpperCase();
  const inviteRef = db.collection("invites").doc(code);
  const userRef = db.collection("users").doc(uid);
  return db.runTransaction(async (tx) => {
    const invite = await tx.get(inviteRef);
    if (!invite.exists) throw new HttpsError("not-found", "Invalid or expired code");
    const pairId = invite.get("pairId");
    const pairRef = db.collection("pairs").doc(pairId);
    const [pair, user] = await Promise.all([tx.get(pairRef), tx.get(userRef)]);
    if (!pair.exists) throw new HttpsError("not-found", "Pair not found");
    const members = pair.get("members") || [];
    if (members.length >= 2 && !members.includes(uid)) {
      throw new HttpsError("failed-precondition", "This pair is already full");
    }
    const oldPairId = user.exists ? user.get("pairId") : "";
    const oldPair = oldPairId && oldPairId !== pairId ? await tx.get(db.collection("pairs").doc(oldPairId)) : null;
    if (oldPair && oldPair.exists) {
      tx.update(oldPair.ref, { members: admin.firestore.FieldValue.arrayRemove(uid) });
      const oldCode = oldPair.get("code");
      if (oldCode) tx.delete(db.collection("invites").doc(oldCode));
    }
    tx.update(pairRef, { members: admin.firestore.FieldValue.arrayUnion(uid) });
    tx.set(userRef, { pairId }, { merge: true });
    tx.delete(inviteRef);
    return { pairId };
  });
});

/** Removes the caller from their pair so their old membership cannot retain access. */
exports.leavePair = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");
  const userRef = db.collection("users").doc(uid);
  await db.runTransaction(async (tx) => {
    const user = await tx.get(userRef);
    const pairId = user.exists ? user.get("pairId") : "";
    const pair = pairId ? await tx.get(db.collection("pairs").doc(pairId)) : null;
    if (pair && pair.exists) {
      tx.update(pair.ref, { members: admin.firestore.FieldValue.arrayRemove(uid) });
      const code = pair.get("code");
      if (code) tx.delete(db.collection("invites").doc(code));
    }
    tx.set(userRef, { pairId: "" }, { merge: true });
  });
  return { ok: true };
});

/** Deletes the paired canvas and uploads for both members. */
exports.clearPairHistory = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");
  const user = await db.collection("users").doc(uid).get();
  const pairId = user.exists && user.get("pairId");
  const pair = pairId && await db.collection("pairs").doc(pairId).get();
  if (!pair || !pair.exists || !(pair.get("members") || []).includes(uid)) {
    throw new HttpsError("permission-denied", "Not a member of this pair");
  }
  while (true) {
    const messages = await db.collection("pairs").doc(pairId).collection("messages").limit(500).get();
    if (messages.empty) break;
    const batch = db.batch();
    messages.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }
  await admin.storage().bucket().deleteFiles({ prefix: `pairs/${pairId}/media/`, force: true });
  await pair.ref.update({ canvasClearedAt: Date.now() });
  return { ok: true };
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
  const createdAt = m.createdAt && typeof m.createdAt.toMillis === "function"
    ? m.createdAt.toMillis()
    : Date.now();
  const data = {
    msgId: snap.id,
    type: String(m.type || ""),
    text: String(m.text || ""),
    mediaUrl: String(m.mediaUrl || ""),
    senderUid: String(m.from || ""),
    createdAt: String(createdAt),
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
