package com.runealytics.progress;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static com.runealytics.progress.ProgressTestData.combat;
import static com.runealytics.progress.ProgressTestData.diaries;
import static com.runealytics.progress.ProgressTestData.diary;
import static com.runealytics.progress.ProgressTestData.ids;
import static com.runealytics.progress.ProgressTestData.obs;
import static com.runealytics.progress.ProgressTestData.quests;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProgressOutboxTest
{
    private static final String COMPLETE = ProgressCoverage.COMPLETE;
    private final Gson gson = new Gson();
    private final Random random = new Random(42);
    private ProgressOutbox box;
    private int batchIds;

    @Before
    public void setUp()
    {
        box = new ProgressOutbox("progress-abc-standard", "STANDARD");
        batchIds = 0;
    }

    private ProgressOutbox.Batch prepare(long now)
    {
        return box.prepare(now, "session-1", "rl_install1", "progress-1", gson, () -> "batch-" + (++batchIds));
    }

    private ProgressDiffer.Result applyObservation(ProgressObservation o, boolean catchUp)
    {
        ProgressDiffer.Result r = ProgressDiffer.diff(box.getBaseline(), o, catchUp, ids());
        box.apply(r, "regular");
        return r;
    }

    private JsonObject body(ProgressOutbox.Batch b)
    {
        return gson.fromJson(b.getBody(), JsonObject.class);
    }

    @Test
    public void nothingIsSentUntilCredentialsAreBound()
    {
        applyObservation(obs(1_000, quests(1, QuestStatus.COMPLETED), COMPLETE, diaries(), null), true);
        box.requestSnapshot();
        assertNull(prepare(2_000));
        box.bind("zezima", "token-a");
        assertNotNull(prepare(2_000));
    }

    @Test
    public void snapshotContainsEverythingAndHistoricalImportHasNoEvents()
    {
        box.bind("zezima", "token-a");
        applyObservation(obs(1_000, quests(1, QuestStatus.COMPLETED, 2, QuestStatus.NOT_STARTED), COMPLETE,
                diaries(diary("VARROCK", "EASY", 1, 0, 14)), combat(3, 3116, Integer.MIN_VALUE)), true);
        box.requestSnapshot();

        ProgressOutbox.Batch b = prepare(2_000);
        JsonObject json = body(b);
        assertEquals("snapshot", json.get("kind").getAsString());
        assertEquals(1, json.get("sequence").getAsLong());
        assertEquals("zezima", json.get("username").getAsString());
        assertEquals("STANDARD", json.get("profile_type").getAsString());
        assertEquals("regular", json.get("world_mode").getAsString());
        assertEquals("rl_install1", json.get("installation_id").getAsString());
        assertEquals(COMPLETE, json.getAsJsonObject("coverage").get("quests").getAsString());
        assertEquals("not_supported", json.getAsJsonObject("coverage").get("diary_tasks").getAsString());
        assertEquals(2, json.getAsJsonArray("quests").size());
        assertEquals(1, json.getAsJsonArray("diaries").size());
        assertEquals(0, json.getAsJsonArray("transitions").size());
        // Bit 31 must be serialised unsigned.
        assertEquals(2147483648L, json.getAsJsonObject("combat_achievements").getAsJsonObject("task_words")
                .get("3116").getAsLong());
        assertFalse("tokens must never be in the body", b.getBody().contains("token-a"));
    }

    @Test
    public void retryResendsTheIdenticalBatch()
    {
        box.bind("zezima", "token-a");
        applyObservation(obs(1_000, quests(1, QuestStatus.NOT_STARTED), COMPLETE, diaries(), null), true);
        box.requestSnapshot();
        ProgressOutbox.Batch first = prepare(2_000);
        box.onResult(first.getBatchId(), ProgressOutbox.Outcome.RETRY, 2_000, random);

        assertNull("backoff must delay the retry", prepare(2_001));
        ProgressOutbox.Batch again = prepare(2_000 + ProgressOutbox.BACKOFF_MAX_MS);
        assertEquals(first.getBatchId(), again.getBatchId());
        assertEquals(first.getSequence(), again.getSequence());
        assertEquals(first.getBody(), again.getBody());
    }

    @Test
    public void changesDuringAnInflightBatchAreNotLostAndGetAHigherSequence()
    {
        box.bind("zezima", "token-a");
        applyObservation(obs(1_000, quests(1, QuestStatus.NOT_STARTED), COMPLETE, diaries(), null), true);
        box.requestSnapshot();
        ProgressOutbox.Batch snapshot = prepare(2_000);

        // Quest completes while the snapshot is in flight.
        ProgressDiffer.Result r = applyObservation(obs(3_000, quests(1, QuestStatus.COMPLETED), COMPLETE, diaries(), null), false);
        assertEquals(1, r.getTransitions().size());
        assertEquals(snapshot.getBatchId(), prepare(3_500).getBatchId()); // still the in-flight one

        box.onResult(snapshot.getBatchId(), ProgressOutbox.Outcome.ACK, 4_000, random);
        ProgressOutbox.Batch delta = prepare(4_000);
        JsonObject json = body(delta);
        assertEquals("delta", json.get("kind").getAsString());
        assertEquals(2, json.get("sequence").getAsLong());
        assertEquals("partial_changed_only", json.getAsJsonObject("coverage").get("quests").getAsString());
        assertEquals("COMPLETED", json.getAsJsonArray("quests").get(0).getAsJsonObject().get("state").getAsString());
        JsonArray transitions = json.getAsJsonArray("transitions");
        assertEquals(1, transitions.size());
        assertEquals("live", transitions.get(0).getAsJsonObject().get("detection").getAsString());

        box.onResult(delta.getBatchId(), ProgressOutbox.Outcome.ACK, 5_000, random);
        assertNull(prepare(5_000));
        assertFalse(box.hasWork());
    }

    @Test
    public void staleResponseForAnOldBatchIsIgnored()
    {
        box.bind("zezima", "token-a");
        applyObservation(obs(1_000, quests(1, QuestStatus.NOT_STARTED), COMPLETE, diaries(), null), true);
        box.requestSnapshot();
        ProgressOutbox.Batch b = prepare(2_000);
        assertEquals(0, box.onResult("some-other-batch", ProgressOutbox.Outcome.ACK, 2_000, random));
        assertTrue(box.hasWork());
        assertEquals(b.getBatchId(), prepare(2_000).getBatchId());
    }

    @Test
    public void rejectedBatchIsDroppedAndASnapshotIsQueued()
    {
        box.bind("zezima", "token-a");
        applyObservation(obs(1_000, quests(1, QuestStatus.NOT_STARTED), COMPLETE, diaries(), null), true);
        box.requestSnapshot();
        box.onResult(prepare(2_000).getBatchId(), ProgressOutbox.Outcome.ACK, 2_000, random);

        applyObservation(obs(3_000, quests(1, QuestStatus.COMPLETED), COMPLETE, diaries(), null), false);
        ProgressOutbox.Batch delta = prepare(3_000);
        int lost = box.onResult(delta.getBatchId(), ProgressOutbox.Outcome.REJECTED, 3_000, random);
        assertEquals(1, lost);
        assertTrue(box.isSnapshotRequested());
        assertNull("cool-down after a rejection", prepare(3_001));

        ProgressOutbox.Batch snap = prepare(3_000 + ProgressOutbox.REJECTED_COOLDOWN_MS);
        assertEquals("snapshot", body(snap).get("kind").getAsString());
        assertNotEquals(delta.getSequence(), snap.getSequence());
    }

    @Test
    public void authFailureUnbindsAndWaitsForReverification()
    {
        box.bind("zezima", "token-a");
        applyObservation(obs(1_000, quests(1, QuestStatus.NOT_STARTED), COMPLETE, diaries(), null), true);
        box.requestSnapshot();
        ProgressOutbox.Batch b = prepare(2_000);
        box.onResult(b.getBatchId(), ProgressOutbox.Outcome.AUTH_FAILED, 2_000, random);
        assertFalse(box.isBound());
        assertNull(prepare(2_000 + ProgressOutbox.BACKOFF_MAX_MS));
        box.bind("zezima", "token-b");
        ProgressOutbox.Batch retry = prepare(2_000 + ProgressOutbox.BACKOFF_MAX_MS);
        assertEquals(b.getBatchId(), retry.getBatchId());
        assertEquals("token-b", retry.getToken());
    }

    @Test
    public void manyTransitionsAreSplitAcrossBatches()
    {
        box.bind("zezima", "token-a");
        int[] before = new int[20];
        int[] after = new int[20];
        for (int i = 0; i < 10; i++)
        {
            before[2 * i] = 7000 + i;
            after[2 * i] = 7000 + i;
            after[2 * i + 1] = -1; // 320 new task bits
        }
        applyObservation(obs(1_000, quests(), COMPLETE, diaries(), combat(0, before)), true);
        box.requestSnapshot();
        box.onResult(prepare(1_500).getBatchId(), ProgressOutbox.Outcome.ACK, 1_500, random);
        applyObservation(obs(2_000, quests(), COMPLETE, diaries(), combat(0, after)), false);

        ProgressOutbox.Batch b1 = prepare(3_000);
        assertEquals(ProgressOutbox.MAX_TRANSITIONS_PER_BATCH, b1.getTransitionCount());
        box.onResult(b1.getBatchId(), ProgressOutbox.Outcome.ACK, 3_000, random);
        ProgressOutbox.Batch b2 = prepare(3_000);
        assertEquals(120, b2.getTransitionCount());
        assertTrue(b2.getSequence() > b1.getSequence());
    }

    @Test
    public void olderScanNeverOverwritesNewerBaseline()
    {
        ProgressDiffer.Result older = ProgressDiffer.diff(ProgressBaseline.empty(),
                obs(1_000, quests(1, QuestStatus.NOT_STARTED), COMPLETE, diaries(), null), true, ids());
        ProgressDiffer.Result newer = ProgressDiffer.diff(older.getBaseline(),
                obs(2_000, quests(1, QuestStatus.COMPLETED), COMPLETE, diaries(), null), false, ids());
        box.apply(newer, "regular");
        box.apply(older, "regular"); // delivered late
        assertEquals(QuestStatus.COMPLETED, box.getBaseline().getQuests().get(1).getStatus());
    }

    @Test
    public void persistenceRoundTripKeepsPendingInflightAndSequence()
    {
        box.bind("zezima", "token-a");
        applyObservation(obs(1_000, quests(1, QuestStatus.NOT_STARTED), COMPLETE,
                diaries(diary("DESERT", "HARD", 0, 0, 2)), combat(0, 3116, -1)), true);
        box.requestSnapshot();
        ProgressOutbox.Batch inflight = prepare(2_000);
        applyObservation(obs(3_000, quests(1, QuestStatus.COMPLETED), COMPLETE,
                diaries(diary("DESERT", "HARD", 0, 0, 2)), combat(0, 3116, -1)), false);

        String json = box.serializeIfDirty(gson);
        assertNotNull(json);
        assertNull("nothing changed since the last write", box.serializeIfDirty(gson));
        assertFalse("credentials are never persisted", json.contains("token-a"));

        ProgressOutbox restored = ProgressOutbox.restore("progress-abc-standard", "STANDARD", json, gson);
        assertEquals(QuestStatus.COMPLETED, restored.getBaseline().getQuests().get(1).getStatus());
        assertEquals(Long.valueOf(4294967295L), restored.getBaseline().getCombat().getTaskWords().get(3116));
        assertEquals(1, restored.pendingTransitionCount());
        assertFalse(restored.isBound());

        restored.bind("zezima", "token-a");
        ProgressOutbox.Batch resent = restored.prepare(10_000, "s2", "rl_install1", "progress-1", gson, () -> "new");
        assertEquals("in-flight batch is resent unchanged after restart", inflight.getBatchId(), resent.getBatchId());
        assertEquals(inflight.getBody(), resent.getBody());
        restored.onResult(resent.getBatchId(), ProgressOutbox.Outcome.ACK, 10_000, random);
        ProgressOutbox.Batch next = restored.prepare(10_000, "s2", "rl_install1", "progress-1", gson, () -> "new2");
        assertEquals(2, next.getSequence());
    }

    @Test
    public void fileForAnotherAccountIsNeverLoaded()
    {
        box.bind("zezima", "token-a");
        applyObservation(obs(1_000, quests(1, QuestStatus.COMPLETED), COMPLETE, diaries(), null), true);
        String json = box.serializeIfDirty(gson);
        ProgressOutbox other = ProgressOutbox.restore("progress-def-standard", "STANDARD", json, gson);
        assertTrue(other.getBaseline().isEmpty());
        assertFalse(other.hasWork());
    }

    @Test
    public void corruptOrEmptyFilesStartFresh()
    {
        assertTrue(ProgressOutbox.restore("k", "STANDARD", null, gson).getBaseline().isEmpty());
        assertTrue(ProgressOutbox.restore("k", "STANDARD", "", gson).getBaseline().isEmpty());
    }

    @Test
    public void backoffIsBoundedWithJitter()
    {
        for (int attempt = 1; attempt < 30; attempt++)
        {
            long d = ProgressOutbox.backoffMs(attempt, random);
            assertTrue(d >= 1_000L);
            assertTrue(d <= ProgressOutbox.BACKOFF_MAX_MS);
        }
        long first = ProgressOutbox.backoffMs(1, new Random(1));
        assertTrue(first >= 3_500 && first <= 6_500);
    }

    @Test
    public void nothingToSendBeforeAnyObservation()
    {
        box.bind("zezima", "token-a");
        box.requestSnapshot();
        assertNull(prepare(1_000));
        assertFalse(box.hasWork());
    }
}
