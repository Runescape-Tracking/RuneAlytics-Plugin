package com.runealytics.progress;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static com.runealytics.progress.ProgressTestData.combat;
import static com.runealytics.progress.ProgressTestData.diaries;
import static com.runealytics.progress.ProgressTestData.diary;
import static com.runealytics.progress.ProgressTestData.ids;
import static com.runealytics.progress.ProgressTestData.obs;
import static com.runealytics.progress.ProgressTestData.quests;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProgressDifferTest
{
    private static final String COMPLETE = ProgressCoverage.COMPLETE;

    @Test
    public void historicalImportUploadsEverythingButCreatesNoEvents()
    {
        ProgressObservation first = obs(1_000, quests(1, QuestStatus.COMPLETED, 2, QuestStatus.IN_PROGRESS,
                3, QuestStatus.NOT_STARTED), COMPLETE, diaries(diary("VARROCK", "EASY", 1, 1, 10)), combat(5, 3116, 0b101));

        ProgressDiffer.Result r = ProgressDiffer.diff(ProgressBaseline.empty(), first, true, ids());

        assertEquals(3, r.getChangedQuests().size());
        assertEquals(1, r.getChangedDiaries().size());
        assertTrue(r.isCombatChanged());
        assertTrue("already-complete progress must not look 'just completed'", r.getTransitions().isEmpty());
        assertEquals(Long.valueOf(1_000), r.getBaseline().getQuestsObservedAtMs());
    }

    @Test
    public void questNotStartedToInProgressToCompletedIsDetectedLive()
    {
        ProgressBaseline b = ProgressDiffer.diff(null,
                obs(1_000, quests(7, QuestStatus.NOT_STARTED), COMPLETE, diaries(), null), true, ids()).getBaseline();

        ProgressDiffer.Result started = ProgressDiffer.diff(b,
                obs(2_000, quests(7, QuestStatus.IN_PROGRESS), COMPLETE, diaries(), null), false, ids());
        assertEquals(1, started.getTransitions().size());
        ProgressTransition t = started.getTransitions().get(0);
        assertEquals(ProgressTransition.CATEGORY_QUEST, t.getCategory());
        assertEquals("7", t.getKey());
        assertEquals("NOT_STARTED", t.getFrom());
        assertEquals("IN_PROGRESS", t.getTo());
        assertEquals(ProgressTransition.DETECTION_LIVE, t.getDetection());
        assertEquals(Long.valueOf(1_000), t.getPreviousObservedAtMs());

        ProgressDiffer.Result done = ProgressDiffer.diff(started.getBaseline(),
                obs(3_000, quests(7, QuestStatus.COMPLETED), COMPLETE, diaries(), null), false, ids());
        assertEquals("COMPLETED", done.getTransitions().get(0).getTo());
        assertEquals(3_000, done.getTransitions().get(0).getObservedAtMs());
    }

    @Test
    public void completionWhilePluginWasOffIsLabelledCatchUp()
    {
        ProgressBaseline b = ProgressDiffer.diff(null,
                obs(1_000, quests(7, QuestStatus.IN_PROGRESS), COMPLETE, diaries(), null), true, ids()).getBaseline();
        ProgressDiffer.Result r = ProgressDiffer.diff(b,
                obs(9_000, quests(7, QuestStatus.COMPLETED), COMPLETE, diaries(), null), true, ids());
        ProgressTransition t = r.getTransitions().get(0);
        assertEquals(ProgressTransition.DETECTION_CATCH_UP, t.getDetection());
        assertEquals(Long.valueOf(1_000), t.getPreviousObservedAtMs());
    }

    @Test
    public void unavailableCategoryNeverOverwritesKnownProgress()
    {
        ProgressBaseline b = ProgressDiffer.diff(null,
                obs(1_000, quests(1, QuestStatus.COMPLETED), COMPLETE, diaries(), null), true, ids()).getBaseline();

        // Loading-time read: everything looks NOT_STARTED, but the scan flagged quests unavailable.
        ProgressDiffer.Result r = ProgressDiffer.diff(b,
                obs(2_000, quests(1, QuestStatus.NOT_STARTED), ProgressCoverage.UNAVAILABLE, diaries(), null), false, ids());

        assertTrue(r.getChangedQuests().isEmpty());
        assertTrue(r.getTransitions().isEmpty());
        assertEquals(QuestStatus.COMPLETED, r.getBaseline().getQuests().get(1).getStatus());
        assertEquals(Long.valueOf(1_000), r.getBaseline().getQuestsObservedAtMs());
    }

    @Test
    public void unavailableOnFirstObservationLeavesCategoryUnobserved()
    {
        ProgressDiffer.Result r = ProgressDiffer.diff(null,
                obs(1_000, quests(1, QuestStatus.NOT_STARTED), ProgressCoverage.UNAVAILABLE, diaries(), null), true, ids());
        assertNull(r.getBaseline().getQuests());
        assertTrue(r.getChangedQuests().isEmpty());
    }

    @Test
    public void questRegressionIsUploadedWithoutAnEvent()
    {
        ProgressBaseline b = ProgressDiffer.diff(null,
                obs(1_000, quests(1, QuestStatus.COMPLETED), COMPLETE, diaries(), null), true, ids()).getBaseline();
        ProgressDiffer.Result r = ProgressDiffer.diff(b,
                obs(2_000, quests(1, QuestStatus.NOT_STARTED), COMPLETE, diaries(), null), false, ids());
        assertTrue(r.getChangedQuests().contains(1));
        assertTrue(r.getTransitions().isEmpty());
    }

    @Test
    public void questNewToTheCatalogHasNoEvent()
    {
        ProgressBaseline b = ProgressDiffer.diff(null,
                obs(1_000, quests(1, QuestStatus.COMPLETED), COMPLETE, diaries(), null), true, ids()).getBaseline();
        ProgressDiffer.Result r = ProgressDiffer.diff(b,
                obs(2_000, quests(1, QuestStatus.COMPLETED, 99, QuestStatus.IN_PROGRESS), COMPLETE, diaries(), null),
                false, ids());
        assertEquals(1, r.getChangedQuests().size());
        assertTrue(r.getChangedQuests().contains(99));
        assertTrue(r.getTransitions().isEmpty());
    }

    @Test
    public void unchangedObservationReportsNoChanges()
    {
        ProgressObservation o = obs(1_000, quests(1, QuestStatus.COMPLETED), COMPLETE,
                diaries(diary("FALADOR", "HARD", 0, 0, 3)), combat(0, 3116, 1));
        ProgressBaseline b = ProgressDiffer.diff(null, o, true, ids()).getBaseline();
        ProgressObservation same = obs(5_000, quests(1, QuestStatus.COMPLETED), COMPLETE,
                diaries(diary("FALADOR", "HARD", 0, 0, 3)), combat(0, 3116, 1));
        ProgressDiffer.Result r = ProgressDiffer.diff(b, same, false, ids());
        assertFalse(r.hasChanges());
        assertEquals(Long.valueOf(5_000), r.getBaseline().getQuestsObservedAtMs());
    }

    @Test
    public void diaryTasksCompleteAndRewardClaimedAreSeparateEvents()
    {
        ProgressBaseline b = ProgressDiffer.diff(null, obs(1_000, quests(), COMPLETE,
                diaries(diary("ARDOUGNE", "EASY", 0, 0, 9)), null), true, ids()).getBaseline();

        ProgressDiffer.Result tasksDone = ProgressDiffer.diff(b, obs(2_000, quests(), COMPLETE,
                diaries(diary("ARDOUGNE", "EASY", 1, 0, 10)), null), false, ids());
        List<ProgressTransition> t1 = tasksDone.getTransitions();
        assertEquals(2, t1.size());
        assertEquals("complete", t1.get(0).getField());
        assertEquals("task_count", t1.get(1).getField());
        assertEquals("9", t1.get(1).getFrom());
        assertEquals("10", t1.get(1).getTo());
        assertEquals("ARDOUGNE:EASY", t1.get(0).getKey());

        ProgressDiffer.Result claimed = ProgressDiffer.diff(tasksDone.getBaseline(), obs(3_000, quests(), COMPLETE,
                diaries(diary("ARDOUGNE", "EASY", 1, 1, 10)), null), false, ids());
        assertEquals(1, claimed.getTransitions().size());
        assertEquals("reward", claimed.getTransitions().get(0).getField());
    }

    @Test
    public void combatTaskBitsIncludingBit31AreDetected()
    {
        ProgressBaseline b = ProgressDiffer.diff(null, obs(1_000, quests(), COMPLETE, diaries(),
                combat(0, 3116, 0, 3117, 0)), true, ids()).getBaseline();

        // Bit 31 makes the signed Java int negative.
        ProgressDiffer.Result r = ProgressDiffer.diff(b, obs(2_000, quests(), COMPLETE, diaries(),
                combat(0, 3116, Integer.MIN_VALUE | 1, 3117, 0)), false, ids());

        assertEquals(2, r.getTransitions().size());
        assertEquals("3116:0", r.getTransitions().get(0).getKey());
        assertEquals("3116:31", r.getTransitions().get(1).getKey());
        assertEquals(ProgressTransition.CATEGORY_CA_TASK, r.getTransitions().get(0).getCategory());
        assertEquals(Long.valueOf(0x8000_0001L), r.getBaseline().getCombat().getTaskWords().get(3116));
    }

    @Test
    public void clearedCombatBitsDoNotCreateEvents()
    {
        ProgressBaseline b = ProgressDiffer.diff(null, obs(1_000, quests(), COMPLETE, diaries(),
                combat(0, 3116, 0b11)), true, ids()).getBaseline();
        ProgressDiffer.Result r = ProgressDiffer.diff(b, obs(2_000, quests(), COMPLETE, diaries(),
                combat(0, 3116, 0b01)), false, ids());
        assertTrue(r.isCombatChanged());
        assertTrue(r.getTransitions().isEmpty());
    }

    @Test
    public void transitionsAreCappedPerDiff()
    {
        int[] before = new int[40];
        int[] after = new int[40];
        for (int i = 0; i < 20; i++)
        {
            before[2 * i] = 5000 + i;
            after[2 * i] = 5000 + i;
            after[2 * i + 1] = -1; // all 32 bits newly set → 640 transitions
        }
        ProgressBaseline b = ProgressDiffer.diff(null, obs(1_000, quests(), COMPLETE, diaries(), combat(0, before)),
                true, ids()).getBaseline();
        ProgressDiffer.Result r = ProgressDiffer.diff(b, obs(2_000, quests(), COMPLETE, diaries(), combat(0, after)),
                false, ids());
        assertEquals(ProgressDiffer.MAX_TRANSITIONS_PER_DIFF, r.getTransitions().size());
        assertEquals(640 - ProgressDiffer.MAX_TRANSITIONS_PER_DIFF, r.getDroppedTransitions());
    }

    @Test
    public void questTransitionRules()
    {
        assertTrue(ProgressDiffer.isQuestTransition(QuestStatus.NOT_STARTED, QuestStatus.COMPLETED));
        assertTrue(ProgressDiffer.isQuestTransition(QuestStatus.IN_PROGRESS, QuestStatus.COMPLETED));
        assertFalse(ProgressDiffer.isQuestTransition(QuestStatus.COMPLETED, QuestStatus.IN_PROGRESS));
        assertFalse(ProgressDiffer.isQuestTransition(QuestStatus.UNKNOWN, QuestStatus.COMPLETED));
        assertFalse(ProgressDiffer.isQuestTransition(QuestStatus.COMPLETED, QuestStatus.COMPLETED));
    }

    @Test
    public void baselineMapsAreImmutable()
    {
        Map<Integer, QuestRecord> q = ProgressDiffer.diff(null,
                obs(1, quests(1, QuestStatus.COMPLETED), COMPLETE, diaries(), null), true, ids()).getBaseline().getQuests();
        try
        {
            q.clear();
            throw new AssertionError("expected immutable map");
        }
        catch (UnsupportedOperationException expected)
        {
            // ok
        }
    }
}
