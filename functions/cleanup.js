const { onSchedule } = require("firebase-functions/v2/scheduler");
const admin = require("firebase-admin");

// 48 hours data retention for instant express feelings app
const RETENTION_HOURS = 48;

/**
 * Runs every 2 hours: automatically deletes all uploaded media files and messages
 * older than 48 hours from Firebase Storage and Firestore.
 */
exports.cleanupOldData = onSchedule("every 2 hours", async () => {
  const cutoff = Date.now() - RETENTION_HOURS * 3600 * 1000;

  // 1) Auto-delete expired media from Firebase Storage
  try {
    const bucket = admin.storage().bucket();
    const [files] = await bucket.getFiles({ prefix: "pairs/" });
    const expiredFiles = files.filter((f) => {
      const time = f.metadata && f.metadata.timeCreated ? new Date(f.metadata.timeCreated).getTime() : 0;
      return time > 0 && time < cutoff;
    });

    if (expiredFiles.length > 0) {
      await Promise.all(expiredFiles.map((f) => f.delete().catch(() => {})));
      console.log(`Successfully deleted ${expiredFiles.length} media files older than 48 hours.`);
    }
  } catch (err) {
    console.error("Storage auto-delete error:", err);
  }

  // 2) Auto-delete expired messages from Firestore
  try {
    const db = admin.firestore();
    const oldMessages = await db.collectionGroup("messages").where("createdAt", "<", cutoff).limit(500).get();
    if (!oldMessages.empty) {
      const batch = db.batch();
      oldMessages.docs.forEach((d) => batch.delete(d.ref));
      await batch.commit();
      console.log(`Successfully deleted ${oldMessages.size} expired messages older than 48 hours.`);
    }
  } catch (err) {
    console.error("Messages auto-delete error:", err);
  }
});
