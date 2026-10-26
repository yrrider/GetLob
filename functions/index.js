const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");
const { codeFromUuid, purgePairData } = require("./purge");

admin.initializeApp();
const db = admin.firestore();

exports.createPair = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

  const deviceUuid = String((req.data && req.data.deviceUuid) || "").trim();

  const userDoc = await db.collection("users").doc(uid).get();
  const oldPairId = userDoc.exists ? userDoc.get("pairId") : "";
  if (oldPairId) {
    await purgePairData(admin, db, oldPairId);
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

  const joinResult = await db.runTransaction(async (tx) => {
    const [invite, user] = await Promise.all([tx.get(inviteRef), tx.get(userRef)]);

    const failedAttempts = (user.exists && user.get("failedJoinAttempts")) || 0;
    const lastAttempt = (user.exists && user.get("lastJoinAttempt")) || 0;
    if (failedAttempts >= 5 && Date.now() - lastAttempt < 60 * 1000) {
      throw new HttpsError("resource-exhausted", "Too many failed attempts. Please wait 1 minute.");
    }

    if (!invite.exists) {
      tx.set(userRef, { failedJoinAttempts: failedAttempts + 1, lastJoinAttempt: Date.now() }, { merge: true });
      throw new HttpsError("not-found", "Invalid or expired code");
    }

    const isPairingMode = invite.get("isPairingMode");
    if (isPairingMode !== true) {
      throw new HttpsError("failed-precondition", "Partner device is not currently in pairing mode. Partner must keep the pairing screen open.");
    }

    const expiresAt = invite.get("expiresAt");
    if (expiresAt && expiresAt < Date.now()) {
      tx.delete(inviteRef);
      throw new HttpsError("failed-precondition", "Invite code has expired. Please request a new code.");
    }

    const pairId = invite.get("pairId");
    const pairRef = db.collection("pairs").doc(pairId);
    const pair = await tx.get(pairRef);
    if (!pair.exists) throw new HttpsError("not-found", "Pair not found");

    if (pair.get("isPairingMode") !== true) {
      throw new HttpsError("failed-precondition", "Partner device is not in pairing mode.");
    }

    const members = pair.get("members") || [];
    if (members.length >= 2 && !members.includes(uid)) {
      throw new HttpsError("failed-precondition", "This pair is already full");
    }

    const fcmToken = user.exists ? user.get("fcmToken") : null;
    const updatePair = {
      members: admin.firestore.FieldValue.arrayUnion(uid),
      partnerUuid: deviceUuid,
      isPairingMode: false
    };
    if (fcmToken) updatePair[`tokens.${uid}`] = fcmToken;
    tx.update(pairRef, updatePair);
    tx.set(userRef, { pairId, deviceUuid, failedJoinAttempts: 0 }, { merge: true });
    tx.delete(inviteRef);

    return { pairId, oldPairIdToPurge: (oldPairId && oldPairId !== pairId) ? oldPairId : null };
  });

  if (joinResult.oldPairIdToPurge) {
    await purgePairData(admin, db, joinResult.oldPairIdToPurge);
  }

  return { pairId: joinResult.pairId };
});

exports.exitPairingMode = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

  const userDoc = await db.collection("users").doc(uid).get();
  const pairId = userDoc.exists ? userDoc.get("pairId") : "";
  if (pairId) {
    const pair = await db.collection("pairs").doc(pairId).get();
    if (pair.exists && (pair.get("members") || []).length <= 1) {
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
  const pairId = (userDoc.exists && userDoc.get("pairId")) || (req.data && req.data.pairId) || "";

  if (pairId) {
    await purgePairData(admin, db, pairId);
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

exports.onMessageCreated = onDocumentCreated("pairs/{pairId}/messages/{msgId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const m = snap.data();
  const pair = await db.collection("pairs").doc(event.params.pairId).get();
  const pairData = pair.data() || {};
  const target = (pairData.members || []).find((u) => u !== m.from);
  if (!target) return;

  let token = pairData.tokens && pairData.tokens[target];
  if (!token) {
    const user = await db.collection("users").doc(target).get();
    token = user.exists && user.data().fcmToken;
    if (token) {
      pair.ref.update({ [`tokens.${target}`]: token }).catch(() => {});
    }
  }
  if (!token) return;

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
    replyToId: String(m.replyToId || ""),
    replyText: String(m.replyText || ""),
    replySender: String(m.replySender || ""),
  };

  const urgent = ["sos", "ptt", "vibe", "live"].includes(data.type);
  try {
    await admin.messaging().send({
      token,
      data,
      android: {
        priority: "high",
        directBootOk: true,
        ttl: urgent ? 60 * 1000 : 24 * 3600 * 1000,
      },
    });
  } catch (e) {
    if (e.code === "messaging/registration-token-not-registered") {
      await Promise.all([
        db.collection("users").doc(target).update({ fcmToken: admin.firestore.FieldValue.delete() }),
        pair.ref.update({ [`tokens.${target}`]: admin.firestore.FieldValue.delete() }),
      ]).catch(() => {});
    } else {
      console.error(e);
    }
  }
});

Object.assign(exports, require("./live"), require("./cleanup"));
