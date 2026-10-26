const { onSchedule } = require("firebase-functions/v2/scheduler");
const admin = require("firebase-admin");
const { purgePairData } = require("./purge");

const RETENTION_HOURS = 48;

exports.cleanupOldData = onSchedule("every 2 hours", async () => {
  const cutoffMillis = Date.now() - RETENTION_HOURS * 3600 * 1000;
  const cutoffTimestamp = admin.firestore.Timestamp.fromMillis(cutoffMillis);
  const db = admin.firestore();

  try {
    const bucket = admin.storage().bucket();
    const [files] = await bucket.getFiles({ prefix: "pairs/" });
    const expiredFiles = files.filter((f) => {
      const time = f.metadata && f.metadata.timeCreated ? new Date(f.metadata.timeCreated).getTime() : 0;
      return time > 0 && time < cutoffMillis;
    });

    if (expiredFiles.length > 0) {
      await Promise.all(expiredFiles.map((f) => f.delete().catch(() => {})));
      console.log(`Successfully deleted ${expiredFiles.length} media files older than 48 hours.`);
    }
  } catch (err) {
    console.error("Storage auto-delete error:", err);
  }

  try {
    const pairsSnap = await db.collection("pairs").get();
    for (const pairDoc of pairsSnap.docs) {
      const messagesRef = pairDoc.ref.collection("messages");

      const [tsDocs, numDocs] = await Promise.all([
        messagesRef.where("createdAt", "<", cutoffTimestamp).limit(250).get().catch(() => ({ docs: [] })),
        messagesRef.where("createdAt", "<", cutoffMillis).limit(250).get().catch(() => ({ docs: [] })),
      ]);

      const toDelete = new Map();
      tsDocs.docs.forEach((d) => toDelete.set(d.id, d.ref));
      numDocs.docs.forEach((d) => toDelete.set(d.id, d.ref));

      if (toDelete.size > 0) {
        const batch = db.batch();
        toDelete.forEach((ref) => batch.delete(ref));
        await batch.commit();
        console.log(`Pair ${pairDoc.id}: deleted ${toDelete.size} expired messages.`);
      }

      const members = pairDoc.get("members") || [];
      const createdAt = pairDoc.get("createdAt") || 0;
      if (members.length < 2 && createdAt > 0 && Date.now() - createdAt > 24 * 3600 * 1000) {
        console.log(`[cleanupOldData] Purging abandoned pair: ${pairDoc.id}`);
        await purgePairData(admin, db, pairDoc.id);
      }
    }
  } catch (err) {
    console.error("Messages auto-delete error:", err);
  }
});
