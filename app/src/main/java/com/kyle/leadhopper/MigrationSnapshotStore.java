package com.kyle.leadhopper;

import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Two-phase journal: a pending snapshot is committed only when localStorage agrees.
 * All operations are serialized, and AtomicFile retains the previous complete write.
 */
final class MigrationSnapshotStore {
    private final AtomicFile file;
    MigrationSnapshotStore(File directory) {
        file = new AtomicFile(new File(directory, "leadhopper-migration-v1.json"));
    }
    private JSONObject read() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile()+".bak").exists())
            return new JSONObject().put("format", "lead-hopper-migration-journal").put("version", 1).put("generation", 0);
        JSONObject journal = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        if (journal.getInt("version") != 1) throw new IllegalStateException("Unsupported journal version");
        verify(journal.optJSONObject("current"));
        verify(journal.optJSONObject("pending"));
        return journal;
    }
    private static String digest(String raw) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static void verify(JSONObject snapshot) throws Exception {
        if(snapshot != null && !digest(snapshot.getString("state")).equals(snapshot.getString("sha256")))
            throw new IllegalStateException("Snapshot integrity failure");
    }
    private void write(JSONObject journal) throws Exception {
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(journal.toString().getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
            file.finishWrite(stream);
        } catch(Exception e) {
            if(stream != null) file.failWrite(stream);
            throw e;
        }
    }
    synchronized long stage(String raw) throws Exception {
        JSONObject state = new JSONObject(raw);
        state.getJSONArray("leads"); state.getJSONArray("schedule");
        state.getJSONArray("callLog"); state.getJSONArray("activityLog");
        JSONObject journal = read();
        long generation = journal.getLong("generation") + 1;
        journal.put("generation", generation);
        journal.put("pending", new JSONObject().put("generation",generation)
                .put("state",raw).put("sha256",digest(raw)).put("schemaVersion",state.optInt("schemaVersion",13)));
        write(journal);
        return generation;
    }
    synchronized void commit(long generation) throws Exception {
        JSONObject journal = read();
        JSONObject pending = journal.getJSONObject("pending");
        if(pending.getLong("generation") != generation) throw new IllegalStateException("Stale snapshot generation");
        journal.put("current",pending); journal.remove("pending");
        write(journal);
    }
    synchronized String recover(String local) throws Exception {
        JSONObject journal = read();
        JSONObject pending = journal.optJSONObject("pending");
        if(pending != null) {
            // Crash after localStorage completed but before journal commit: matching local bytes
            // prove that this pending generation is the one that became durable in WebView storage.
            if(pending.getString("state").equals(local)) journal.put("current",pending);
            // Otherwise the local write never completed. Keep the previous committed generation.
            journal.remove("pending");
            write(journal);
        }
        JSONObject current = journal.optJSONObject("current");
        String nativeState = current == null ? null : current.getString("state");
        if(local == null) return nativeState;
        if(nativeState == null) return local; // First bridge launch over a legacy localStorage-only install.
        if(!nativeState.equals(local))
            throw new IllegalStateException("Local/native snapshot divergence");
        return local;
    }
}
