const { setGlobalOptions } = require("firebase-functions/v2");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");
const { codeFromUuid, purgePairData } = require("./purge");

setGlobalOptions({ region: "us-central1", maxInstances: 10, memory: "256MiB" });

admin.initializeApp();
const db = admin.firestore();

exports.createPair = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

  const deviceUuid = String((req.data && req.data.deviceUuid) || "").trim();

  const userDoc = await db.collection("users").doc(uid).get();
  const oldPairId = userDoc.exists ? userDoc.get("pairId") : "";
  if (oldPairId) {
    const oldPair = await db.collection("pairs").doc(oldPairId).get();
    if (oldPair.exists && (oldPair.get("members") || []).includes(uid)) {
      await purgePairData(admin, db, oldPairId);
    }
  }

  for (let attempt = 0; attempt < 5; attempt++) {
    const code = codeFromUuid(deviceUuid);
    const pairRef = db.collection("pairs").doc();
    const inviteRef = db.collection("invites").doc(code);
    const userRef = db.collection("users").doc(uid);
    const expiresAt = Date.now() + 24 * 3600 * 1000;

    const result = await db.runTransaction(async (tx) => {
      const [invite, user] = await Promise.all([tx.get(inviteRef), tx.get(userRef)]);
      if (invite.exists) return null;

      const fcmToken = user.exists ? user.get("fcmToken") : null;
      tx.set(pairRef, {
        members: [uid],
        creatorUid: uid,
        creatorUuid: deviceUuid,
        createdAt: Date.now(),
        code,
        isPairingMode: true,
        tokens: fcmToken ? { [uid]: fcmToken } : {}
      });
      tx.set(inviteRef, {
        pairId: pairRef.id,
        creatorUid: uid,
        creatorUuid: deviceUuid,
        isPairingMode: true,
        createdAt: Date.now(),
        expiresAt
      });
      tx.set(userRef, { pairId: pairRef.id, deviceUuid }, { merge: true });
      return { code, pairId: pairRef.id };
    });
    if (result) return result;
  }
  throw new HttpsError("aborted", "Could not reserve an invite code; please retry");
});

exports.joinPair = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

  const code = String((req.data && req.data.code) || "").trim().toUpperCase();
  const deviceUuid = String((req.data && req.data.deviceUuid) || "").trim();
  if (!code || code.length < 6) throw new HttpsError("invalid-argument", "Invalid invite code");

  const userDoc = await db.collection("users").doc(uid).get();
  const oldPairId = userDoc.exists ? userDoc.get("pairId") : "";

  const inviteRef = db.collection("invites").doc(code);
  const userRef = db.collection("users").doc(uid);

  const outcome = await db.runTransaction(async (tx) => {
    const [invite, user] = await Promise.all([tx.get(inviteRef), tx.get(userRef)]);

    const failedAttempts = (user.exists && user.get("failedJoinAttempts")) || 0;
    const lastAttempt = (user.exists && user.get("lastJoinAttempt")) || 0;
    if (failedAttempts >= 5 && Date.now() - lastAttempt < 60 * 1000) {
      return { ok: false, errCode: "resource-exhausted", errMsg: "Too many failed attempts. Please wait 1 minute." };
    }

    if (!invite.exists) {
      tx.set(userRef, { failedJoinAttempts: failedAttempts + 1, lastJoinAttempt: Date.now() }, { merge: true });
      return { ok: false, errCode: "not-found", errMsg: "Invalid or expired code" };
    }

    const isPairingMode = invite.get("isPairingMode");
    if (isPairingMode !== true) {
      return { ok: false, errCode: "failed-precondition", errMsg: "Partner device is not currently in pairing mode. Partner must keep the pairing screen open." };
    }

    const expiresAt = invite.get("expiresAt");
    if (expiresAt && expiresAt < Date.now()) {
      tx.delete(inviteRef);
      return { ok: false, errCode: "failed-precondition", errMsg: "Invite code has expired. Please request a new code." };
    }

    const pairId = invite.get("pairId");
    const pairRef = db.collection("pairs").doc(pairId);
    const pair = await tx.get(pairRef);
    if (!pair.exists) return { ok: false, errCode: "not-found", errMsg: "Pair not found" };

    if (pair.get("isPairingMode") !== true) {
      return { ok: false, errCode: "failed-precondition", errMsg: "Partner device is not in pairing mode." };
    }

    const members = pair.get("members") || [];
    if (members.length >= 2 && !members.includes(uid)) {
      return { ok: false, errCode: "failed-precondition", errMsg: "This pair is already full" };
    }

    const fcmToken = user.exists ? user.get("fcmToken") : null;
    const updatePair = {
      members: admin.firestore.FieldValue.arrayUnion(uid),
      partnerUuid: deviceUuid,
      isPairingMode: false
    };
    if (fcmToken) updatePair[`tokens.${uid}`] = fcmToken;
    tx.update(pairRef, updatePair);
    tx.set(userRef, { pairId, deviceUuid, failedJoinAttempts: 0, lastJoinAttempt: 0 }, { merge: true });
    tx.delete(inviteRef);

    return { ok: true, pairId, oldPairIdToPurge: (oldPairId && oldPairId !== pairId) ? oldPairId : null };
  });

  if (!outcome.ok) {
    throw new HttpsError(outcome.errCode, outcome.errMsg);
  }

  if (outcome.oldPairIdToPurge) {
    const oldPair = await db.collection("pairs").doc(outcome.oldPairIdToPurge).get();
    if (oldPair.exists && (oldPair.get("members") || []).includes(uid)) {
      await purgePairData(admin, db, outcome.oldPairIdToPurge);
    }
  }

  return { pairId: outcome.pairId };
});

exports.exitPairingMode = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

  const userDoc = await db.collection("users").doc(uid).get();
  const pairId = userDoc.exists ? userDoc.get("pairId") : "";
  if (pairId) {
    const pair = await db.collection("pairs").doc(pairId).get();
    if (pair.exists && (pair.get("members") || []).includes(uid) && (pair.get("members") || []).length <= 1) {
      await purgePairData(admin, db, pairId);
    }
  }
  await db.collection("users").doc(uid).set({ pairId: "" }, { merge: true });
  return { ok: true };
});

exports.leavePair = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

  const userDoc = await db.collection("users").doc(uid).get();
  const pairId = userDoc.exists ? userDoc.get("pairId") : "";

  if (pairId) {
    const pairDoc = await db.collection("pairs").doc(pairId).get();
    if (pairDoc.exists && (pairDoc.get("members") || []).includes(uid)) {
      await purgePairData(admin, db, pairId);
    }
  }
  await db.collection("users").doc(uid).set({ pairId: "" }, { merge: true });
  return { ok: true };
});

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

exports.onMessageCreated = onDocumentCreated(
  {
    document: "pairs/{pairId}/messages/{msgId}",
    region: "us-central1",
    minInstances: 1,
    concurrency: 80,
    memory: "256MiB",
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const m = snap.data();

    const pairRef = db.collection("pairs").doc(event.params.pairId);
    const pairSnap = await pairRef.get();
    if (!pairSnap.exists) return;
    const pairData = pairSnap.data() || {};
    const members = pairData.members || [];
    if (!members.includes(m.from)) return;

    const target = members.find((u) => u !== m.from);
    if (!target) return;

    let token = pairData.tokens && pairData.tokens[target];
    if (!token) {
      const user = await db.collection("users").doc(target).get();
      token = user.exists && user.data().fcmToken;
      if (token) {
        pairRef.update({ [`tokens.${target}`]: token }).catch(() => {});
      }
    }
    if (!token) return;

    const serverMs = m.createdAt && typeof m.createdAt.toMillis === "function"
      ? m.createdAt.toMillis()
      : (typeof m.createdAt === "number" ? m.createdAt : 0);
    const createdAt = Number(m.clientCreatedAt) || serverMs || Date.now();

    const data = {
      msgId: snap.id,
      pairId: event.params.pairId,
      type: String(m.type || ""),
      text: String(m.text || ""),
      mediaUrl: String(m.mediaUrl || ""),
      thumbB64: String(m.thumbB64 || ""),
      senderUid: String(m.from || ""),
      createdAt: String(createdAt),
      replyToId: String(m.replyToId || ""),
      replyText: String(m.replyText || ""),
      replySender: String(m.replySender || ""),
      fetchFull: "0",
    };

    const MAX_FCM_DATA_BYTES = 3400;
    function calcByteSize(obj) {
      return Buffer.byteLength(JSON.stringify(obj), "utf8");
    }

    if (calcByteSize(data) > MAX_FCM_DATA_BYTES) {
      data.thumbB64 = "";
    }
    if (calcByteSize(data) > MAX_FCM_DATA_BYTES) {
      data.replyText = "";
    }
    if (calcByteSize(data) > MAX_FCM_DATA_BYTES) {
      data.text = "";
      data.fetchFull = "1";
    }

    const urgent = ["sos", "ptt", "vibe", "live"].includes(data.type);
    const collapseKey = data.type === "vibe" ? "getlob_vibe" : (data.type === "game" ? "getlob_game" : undefined);

    try {
      await admin.messaging().send({
        token,
        data,
        android: {
          priority: "high",
          directBootOk: true,
          collapseKey,
          ttl: urgent ? 60 * 1000 : 24 * 3600 * 1000,
        },
      });
    } catch (e) {
      if (
        e.code === "messaging/registration-token-not-registered" ||
        e.code === "messaging/invalid-registration-token"
      ) {
        await Promise.all([
          db.collection("users").doc(target).update({ fcmToken: admin.firestore.FieldValue.delete() }),
          pairRef.update({ [`tokens.${target}`]: admin.firestore.FieldValue.delete() }),
        ]).catch(() => {});
      } else {
        console.error("FCM send error:", e);
      }
    }
  }
);

Object.assign(exports, require("./live"), require("./cleanup"));
