package com.runealytics.progress;

import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProgressReaderTest
{
    /** A client whose quest script returns {@code scriptResults.get(questId)} (default 1 = not started). */
    static Client fakeClient(Map<Integer, Integer> scriptResults, Map<Integer, Integer> varbits, Map<Integer, Integer> varps)
    {
        Client client = mock(Client.class);
        int[] stack = new int[1];
        doAnswer(inv ->
        {
            Object[] args = inv.getArguments();
            assertEquals(ScriptID.QUEST_STATUS_GET, ((Integer) args[0]).intValue());
            stack[0] = scriptResults.getOrDefault((Integer) args[1], 1);
            return null;
        }).when(client).runScript(any(Object[].class));
        when(client.getIntStack()).thenReturn(stack);
        when(client.getVarbitValue(anyInt())).thenAnswer(inv -> varbits.getOrDefault((Integer) inv.getArgument(0), 0));
        when(client.getVarpValue(anyInt())).thenAnswer(inv -> varps.getOrDefault((Integer) inv.getArgument(0), 0));
        return client;
    }

    @Test
    public void readsEveryQuestAndMapsScriptResults()
    {
        Map<Integer, Integer> results = new HashMap<>();
        results.put(Quest.COOKS_ASSISTANT.getId(), 2);
        results.put(Quest.DRAGON_SLAYER_I.getId(), 0);
        Map<Integer, Integer> varps = new HashMap<>();
        varps.put(VarPlayerID.QP, 1);

        ProgressObservation o = ProgressReader.read(fakeClient(results, new HashMap<>(), varps), 5L, "c", null);

        assertEquals(ProgressCoverage.COMPLETE, o.getQuestCoverage());
        assertEquals(Quest.values().length, o.getQuests().size());
        assertEquals(QuestStatus.COMPLETED, o.getQuests().get(Quest.COOKS_ASSISTANT.getId()).getStatus());
        assertEquals(QuestStatus.IN_PROGRESS, o.getQuests().get(Quest.DRAGON_SLAYER_I.getId()).getStatus());
        assertEquals(QuestStatus.NOT_STARTED, o.getQuests().get(Quest.ANIMAL_MAGNETISM.getId()).getStatus());
        assertEquals("COOKS_ASSISTANT", o.getQuests().get(Quest.COOKS_ASSISTANT.getId()).getKey());
    }

    @Test
    public void questsStillLoadingAreUnavailable()
    {
        Map<Integer, Integer> varps = new HashMap<>();
        varps.put(VarPlayerID.QP, 150); // server says 150 QP, scripts say nothing finished
        ProgressObservation o = ProgressReader.read(fakeClient(new HashMap<>(), new HashMap<>(), varps), 5L, "c", null);
        assertEquals(ProgressCoverage.UNAVAILABLE, o.getQuestCoverage());
    }

    @Test
    public void brandNewAccountWithNoQuestPointsIsComplete()
    {
        ProgressObservation o = ProgressReader.read(fakeClient(new HashMap<>(), new HashMap<>(), new HashMap<>()),
                5L, "c", null);
        assertEquals(ProgressCoverage.COMPLETE, o.getQuestCoverage());
    }

    @Test
    public void diaryAndCombatVariablesAreRead()
    {
        Map<Integer, Integer> varbits = new HashMap<>();
        varbits.put(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1);
        varbits.put(VarbitID.VARROCK_EASY_REWARD, 1);
        varbits.put(VarbitID.VARROCK_EASY_COUNT, 14);
        varbits.put(VarbitID.ATJUN_EASY_DONE, 1);
        varbits.put(VarbitID.CA_TOTAL_TASKS_COMPLETED_EASY, 2);
        varbits.put(VarbitID.CA_POINTS, 2);
        Map<Integer, Integer> varps = new HashMap<>();
        varps.put(VarPlayerID.CA_TASK_COMPLETED_0, Integer.MIN_VALUE | 1); // bits 0 and 31

        ProgressObservation o = ProgressReader.read(fakeClient(new HashMap<>(), varbits, varps), 5L, "c", null);

        DiaryRecord v = o.getDiaries().get("VARROCK:EASY");
        assertEquals(1, v.getCompleteVar());
        assertEquals(1, v.getRewardVar());
        assertEquals(14, v.getTaskCount());
        assertEquals(VarbitID.VARROCK_EASY_COUNT, v.getCountVarbit());
        assertEquals(1, o.getDiaries().get("KARAMJA:EASY").getCompleteVar());
        assertEquals(48, o.getDiaries().size());

        assertEquals(Long.valueOf(0x8000_0001L), o.getCombat().getTaskWords().get(VarPlayerID.CA_TASK_COMPLETED_0));
        assertEquals(ProgressCoverage.COMPLETE, o.getCombatCoverage());
    }

    @Test
    public void diaryTableHasTwelveRegionsAndDistinctVarbits()
    {
        assertEquals(12, ProgressReader.DIARY_VARBITS.size());
        Set<Integer> seen = new HashSet<>();
        for (int[][] tiers : ProgressReader.DIARY_VARBITS.values())
        {
            assertEquals(4, tiers.length);
            for (int[] t : tiers)
            {
                for (int id : t) assertTrue("duplicate varbit " + id, seen.add(id));
            }
        }
        assertEquals(21, ProgressReader.CA_TASK_VARPS.length);
    }

    @Test
    public void combatCoverageCrossCheck()
    {
        assertEquals(ProgressCoverage.COMPLETE, ProgressReader.combatCoverage(ProgressTestData.combat(0, 1, 0b111)));

        CombatRecord loading = new CombatRecord(java.util.Collections.singletonMap(1, 0L),
                java.util.Collections.singletonMap("EASY", 5), 10,
                java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
        assertEquals(ProgressCoverage.UNAVAILABLE, ProgressReader.combatCoverage(loading));

        CombatRecord mismatch = new CombatRecord(java.util.Collections.singletonMap(1, 0b1L),
                java.util.Collections.singletonMap("EASY", 5), 10,
                java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
        assertEquals(ProgressCoverage.RAW_UNVERIFIED, ProgressReader.combatCoverage(mismatch));
    }

    @Test
    public void questStateMapping()
    {
        assertEquals(QuestStatus.COMPLETED, ProgressReader.map(QuestState.FINISHED));
        assertEquals(QuestStatus.IN_PROGRESS, ProgressReader.map(QuestState.IN_PROGRESS));
        assertEquals(QuestStatus.NOT_STARTED, ProgressReader.map(QuestState.NOT_STARTED));
        assertEquals(QuestStatus.UNKNOWN, ProgressReader.map(null));
        assertTrue(ProgressReader.questsLookLoaded(0, 0));
        assertFalse(ProgressReader.questsLookLoaded(0, 3));
    }

    @Test
    public void catalogVersionIsStable()
    {
        assertEquals(ProgressReader.catalogVersion(), ProgressReader.catalogVersion());
        assertTrue(ProgressReader.catalogVersion().startsWith("q" + Quest.values().length + "-d48-ca21-"));
    }

    @Test
    public void unsignedWordHandling()
    {
        assertEquals(4294967295L, CombatRecord.unsignedWord(-1));
        assertTrue(CombatRecord.isBitSet(CombatRecord.unsignedWord(Integer.MIN_VALUE), 31));
        assertFalse(CombatRecord.isBitSet(CombatRecord.unsignedWord(Integer.MIN_VALUE), 30));
        try
        {
            CombatRecord.isBitSet(0, 32);
            throw new AssertionError("expected range check");
        }
        catch (IllegalArgumentException expected)
        {
            // ok
        }
    }
}
