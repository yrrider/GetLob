const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineString, defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const { AccessToken } = require("livekit-server-sdk");

const LIVEKIT_URL = defineString("LIVEKIT_URL", { default: "wss://getlob-hlyajc4h.livekit.cloud" });
const LIVEKIT_API_KEY = defineSecret("LIVEKIT_API_KEY");
const LIVEKIT_API_SECRET = defineSecret("LIVEKIT_API_SECRET");

const db = () => admin.firestore();

exports.getLiveToken = onCall(
  {
    region: "us-central1",
    minInstances: 1,
    concurrency: 80,
    memory: "256MiB",
    secrets: [LIVEKIT_API_KEY, LIVEKIT_API_SECRET],
  },
  async (req) => {
    const uid = req.auth && req.auth.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in first");

    let pairId = req.data && req.data.pairId;
    if (!pairId) {
      const user = await db().collection("users").doc(uid).get();
      pairId = user.exists && user.data().pairId;
    }
    if (!pairId) throw new HttpsError("failed-precondition", "Not paired");

    const pair = await db().collection("pairs").doc(pairId).get();
    if (!pair.exists || !(pair.data().members || []).includes(uid)) {
      throw new HttpsError("permission-denied", "Not a member of this pair");
    }

    const apiKey = (LIVEKIT_API_KEY.value() || process.env.LIVEKIT_API_KEY || "").trim();
    const apiSecret = (LIVEKIT_API_SECRET.value() || process.env.LIVEKIT_API_SECRET || "").trim();
    if (!apiKey || !apiSecret) {
      throw new HttpsError("failed-precondition", "LiveKit credentials not configured on server");
    }

    const at = new AccessToken(apiKey, apiSecret, {
      identity: uid,
      ttl: "30m",
    });
    at.addGrant({ roomJoin: true, room: pairId, canPublish: true, canSubscribe: true });
    return {
      token: await at.toJwt(),
      url: (LIVEKIT_URL.value() || process.env.LIVEKIT_URL || "").trim(),
      expiresAt: Date.now() + 28 * 60 * 1000,
    };
  }
);
