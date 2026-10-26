const crypto = require("crypto");

function codeFromUuid(uuid) {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const seed = (uuid || "") + ":" + Date.now() + ":" + crypto.randomInt(1000, 9999);
  const hash = crypto.createHash("sha256").update(seed).digest();
  let s = "";
  for (let i = 0; i < 6; i++) {
    s += chars[hash[i] % chars.length];
  }
  return s;
}

async function purgePairData(admin, db, pairId) {
  if (!pairId) return;
  console.log(`[purgePairData] Starting permanent purge for pairId: ${pairId}`);

  try {
    const bucket = admin.storage().bucket();
    await bucket.deleteFiles({ prefix: `pairs/${pairId}/`, force: true });
    console.log(`[purgePairData] Storage files purged for pair: ${pairId}`);
  } catch (err) {
    console.error(`[purgePairData] Storage delete error for pair ${pairId}:`, err.message || err);
  }

  try {
    while (true) {
      const snap = await db.collection("pairs").doc(pairId).collection("messages").limit(500).get();
      if (snap.empty) break;
      const batch = db.batch();
      snap.docs.forEach((doc) => batch.delete(doc.ref));
      await batch.commit();
    }
    console.log(`[purgePairData] Messages subcollection purged for pair: ${pairId}`);
  } catch (err) {
    console.error(`[purgePairData] Messages batch delete error for pair ${pairId}:`, err.message || err);
  }

  try {
    const gamesSnap = await db.collection("pairs").doc(pairId).collection("games").limit(100).get();
    if (!gamesSnap.empty) {
      const batch = db.batch();
      gamesSnap.docs.forEach((doc) => batch.delete(doc.ref));
      await batch.commit();
    }
  } catch (err) {
    console.error(`[purgePairData] Games batch delete error for pair ${pairId}:`, err.message || err);
  }

  try {
    const pairRef = db.collection("pairs").doc(pairId);
    const pairDoc = await pairRef.get();
    if (pairDoc.exists) {
      const code = pairDoc.get("code");
      if (code) {
        await db.collection("invites").doc(code).delete().catch(() => {});
      }
      const members = pairDoc.get("members") || [];
      for (const m of members) {
        if (m) {
          await db.collection("users").doc(m).set({ pairId: "" }, { merge: true }).catch(() => {});
        }
      }
    }
    await pairRef.delete().catch(() => {});
    console.log(`[purgePairData] Pair doc and invite purged: ${pairId}`);
  } catch (err) {
    console.error(`[purgePairData] Pair doc delete error for pair ${pairId}:`, err.message || err);
  }
}

module.exports = {
  codeFromUuid,
  purgePairData,
};
