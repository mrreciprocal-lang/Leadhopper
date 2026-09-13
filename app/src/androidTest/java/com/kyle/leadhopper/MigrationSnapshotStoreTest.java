package com.kyle.leadhopper;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MigrationSnapshotStoreTest {
    private File directory() {
        File directory=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"snapshot-test-"+System.nanoTime());
        assertTrue(directory.mkdirs());return directory;
    }
    private String state(String label) { return "{\"schemaVersion\":13,\"label\":\""+label+"\",\"leads\":[],\"schedule\":[],\"callLog\":[],\"activityLog\":[]}"; }
    @Test public void committedSnapshotSurvivesNewStoreAndMissingLocalStorage() throws Exception {
        File directory=directory();MigrationSnapshotStore store=new MigrationSnapshotStore(directory);
        String raw=state("saved");store.commit(store.stage(raw));
        assertEquals(raw,new MigrationSnapshotStore(directory).recover(null));
    }
    @Test public void deathAfterStageBeforeLocalWriteRetainsPreviousGeneration() throws Exception {
        File directory=directory();MigrationSnapshotStore store=new MigrationSnapshotStore(directory);
        String old=state("old");store.commit(store.stage(old));store.stage(state("new"));
        assertEquals(old,new MigrationSnapshotStore(directory).recover(old));
        assertEquals(old,new MigrationSnapshotStore(directory).recover(null));
    }
    @Test public void deathAfterLocalWriteCompletesMatchingPendingGeneration() throws Exception {
        File directory=directory();MigrationSnapshotStore store=new MigrationSnapshotStore(directory);
        String raw=state("new");store.stage(raw);
        assertEquals(raw,new MigrationSnapshotStore(directory).recover(raw));
        assertEquals(raw,new MigrationSnapshotStore(directory).recover(null));
    }
    @Test public void staleCommitCannotReplaceNewerPendingGeneration() throws Exception {
        MigrationSnapshotStore store=new MigrationSnapshotStore(directory());long old=store.stage(state("old"));
        long latest=store.stage(state("latest"));
        try{store.commit(old);fail("Stale commit accepted");}catch(IllegalStateException expected){}
        store.commit(latest);assertEquals(state("latest"),store.recover(null));
    }
    @Test public void corruptSnapshotFailsClosed() throws Exception {
        File directory=directory();MigrationSnapshotStore store=new MigrationSnapshotStore(directory);
        store.commit(store.stage(state("old")));
        java.nio.file.Files.write(new File(directory,"leadhopper-migration-v1.json").toPath(),"broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try{new MigrationSnapshotStore(directory).recover(null);fail("Corruption accepted");}catch(Exception expected){}
    }
}
