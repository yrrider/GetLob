const { onSchedule } = require("firebase-functions/v2/scheduler");
const admin = require("firebase-admin");

const KEEP_DAYS = 30;

/** Daily: delete uploaded media and messages older than KEEP_DAYS to save storage and protect privacy. */
exports.cleanupOldData = onSchedule("every 24 hours", async () => {
  const cutoff = Date.now() - KEEP_DAYS * 24 * 3600 * 1000;

  // 1) Storage files
  const [files] = await admin.storage().bucket().getFiles({ prefix: "pairs/" });
  await Promise.all(
    files
      .filter((f) => new Date(f.metadata.timeCreated).getTime() < cutoff)
      .map((f) => f.delete().catch(() => {}))
  );

  // 2) Old messages (needs a collection-group index on messages.createdAt; the first
  //    run logs a link to create it).
  const db = admin.firestore();
  const old = await db.collectionGroup("messages").where("createdAt", "<", cutoff).limit(400).get();
  if (!old.empty) {
    const batch = db.batch();
    old.docs.forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
});
