package com.runealytics.progress;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Small builders shared by the progress tests. */
final class ProgressTestData
{
    private ProgressTestData()
    {
    }

    static Supplier<String> ids()
    {
        int[] n = {0};
        return () -> "t" + (++n[0]);
    }

    static Map<Integer, QuestRecord> quests(Object... idStatusPairs)
    {
        Map<Integer, QuestRecord> m = new LinkedHashMap<>();
        for (int i = 0; i < idStatusPairs.length; i += 2)
        {
            int id = (Integer) idStatusPairs[i];
            QuestStatus s = (QuestStatus) idStatusPairs[i + 1];
            m.put(id, new QuestRecord(id, "Q" + id, "Quest " + id, s));
        }
        return m;
    }

    static DiaryRecord diary(String region, String tier, int complete, int reward, int count)
    {
        return new DiaryRecord(region, tier, complete, reward, count, 1, 2, 3);
    }

    static Map<String, DiaryRecord> diaries(DiaryRecord... records)
    {
        Map<String, DiaryRecord> m = new LinkedHashMap<>();
        for (DiaryRecord d : records) m.put(d.key(), d);
        return m;
    }

    /** Combat record with the given words (varp id → signed int as read from the client). */
    static CombatRecord combat(int points, int... varpThenSignedWord)
    {
        Map<Integer, Long> words = new LinkedHashMap<>();
        int bits = 0;
        for (int i = 0; i < varpThenSignedWord.length; i += 2)
        {
            long w = CombatRecord.unsignedWord(varpThenSignedWord[i + 1]);
            words.put(varpThenSignedWord[i], w);
            bits += Long.bitCount(w);
        }
        Map<String, Integer> tiers = new LinkedHashMap<>();
        Map<String, Integer> zero = new LinkedHashMap<>();
        for (String t : CombatRecord.TIERS)
        {
            tiers.put(t, 0);
            zero.put(t, 0);
        }
        tiers.put("EASY", bits); // keep the cross-check consistent
        return new CombatRecord(words, tiers, points, zero, zero);
    }

    static ProgressObservation obs(long at, Map<Integer, QuestRecord> quests, String questCoverage,
                                   Map<String, DiaryRecord> diaries, CombatRecord combat)
    {
        return new ProgressObservation(at, "test-catalog", quests, questCoverage, 0,
                diaries, ProgressCoverage.COMPLETE, combat,
                combat == null ? ProgressCoverage.UNAVAILABLE : ProgressCoverage.COMPLETE);
    }
}
