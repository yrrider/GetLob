const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { defineSecret, defineString } = require("firebase-functions/params");
const admin = require("firebase-admin");
const { AccessToken } = require("livekit-server-sdk");

// Set with: firebase functions:secrets:set LIVEKIT_API_KEY  (and LIVEKIT_API_SECRET)
//           and LIVEKIT_URL (wss://your-project.livekit.cloud) via .env
const LIVEKIT_API_KEY = defineSecret("LIVEKIT_API_KEY");
const LIVEKIT_API_SECRET = defineSecret("LIVEKIT_API_SECRET");
const LIVEKIT_URL = defineString("LIVEKIT_URL");

const db = () => admin.firestore();

/** Returns a short-lived LiveKit token for the caller's pair room (room name = pairId). */
exports.getLiveToken = onCall({ secrets: [LIVEKIT_API_KEY, LIVEKIT_API_SECRET] }, async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

  const user = await db().collection("users").doc(uid).get();
  const pairId = user.exists && user.data().pairId;
  if (!pairId) throw new HttpsError("failed-precondition", "Not paired");
  const pair = await db().collection("pairs").doc(pairId).get();
  if (!pair.exists || !(pair.data().members || []).includes(uid)) {
    throw new HttpsError("permission-denied", "Not a member of this pair");
  }

  // Sanitize key and secret by trimming any trailing whitespace or newlines
  const apiKey = (LIVEKIT_API_KEY.value() || "").trim();
  const apiSecret = (LIVEKIT_API_SECRET.value() || "").trim();

  const at = new AccessToken(apiKey, apiSecret, {
    identity: uid,
    ttl: "10m",
  });
  at.addGrant({ roomJoin: true, room: pairId, canPublish: true, canSubscribe: true });
  return { token: await at.toJwt(), url: (LIVEKIT_URL.value() || "").trim() };
});

/**
 * When someone opens live talk the app writes a message of type "live".
 * Send the partner a visible notification ("Tap to talk"); tapping it opens LiveTalkActivity.
 */
exports.onLiveAlert = onDocumentCreated("pairs/{pairId}/messages/{msgId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const m = snap.data();
  if (m.type !== "live") return;

  const pair = await db().collection("pairs").doc(event.params.pairId).get();
  const target = ((pair.data() || {}).members || []).find((u) => u !== m.from);
  if (!target) return;
  const user = await db().collection("users").doc(target).get();
  const token = user.exists && user.data().fcmToken;
  if (!token) return;

  await admin.messaging().send({
    token,
    notification: { title: "📡 Push to talk", body: "Tap to talk" },
    data: { type: "live", msgId: snap.id, senderUid: String(m.from || "") },
    android: {
      priority: "high",
      ttl: 30 * 1000,
      notification: {
        channelId: "ptt",
        clickAction: "com.yrrider.getlob.OPEN_LIVE",
        defaultSound: true,
      },
    },
  }).catch((e) => console.error(e));
});
