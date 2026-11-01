package com.runealytics.progress;

import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads quest, diary and combat-achievement progress from the client.
 *
 * <p><b>Client thread only.</b> {@link Quest#getState} runs client script 4029
 * ({@code QUEST_STATUS_GET}) via {@code Client#runScript}, which RuneLite documents
 * as client-thread-only and not reentrant. Never call {@link #read} from inside a
 * {@code VarbitChanged} handler; the tracker calls it from {@code GameTick}.</p>
 *
 * <p>All constants are RuneLite generated gameval ids, checked against RuneLite
 * 1.13.4. If a future RuneLite renames one, the plugin fails to compile rather than
 * silently reading the wrong variable.</p>
 */
public final class ProgressReader
{
    /** Diary tier names in game order. */
    static final String[] DIARY_TIERS = {"EASY", "MEDIUM", "HARD", "ELITE"};

    /**
     * Region → per tier {completeVarbit, rewardVarbit, countVarbit}.
     * Karamja easy–hard use the older ATJUN_* varbits (DONE / REWARD); its elite
     * tier follows the shared naming.
     */
    static final Map<String, int[][]> DIARY_VARBITS;

    static
    {
        Map<String, int[][]> m = new LinkedHashMap<>();
        m.put("ARDOUGNE", new int[][]{
            {VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_EASY_REWARD, VarbitID.ARDOUGNE_EASY_COUNT},
            {VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_MEDIUM_REWARD, VarbitID.ARDOUGNE_MED_COUNT},
            {VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_HARD_REWARD, VarbitID.ARDOUGNE_HARD_COUNT},
            {VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, VarbitID.ARDOUGNE_ELITE_REWARD, VarbitID.ARDOUGNE_ELITE_COUNT}});
        m.put("DESERT", new int[][]{
            {VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_EASY_REWARD, VarbitID.DESERT_EASY_COUNT},
            {VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_MEDIUM_REWARD, VarbitID.DESERT_MED_COUNT},
            {VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_HARD_REWARD, VarbitID.DESERT_HARD_COUNT},
            {VarbitID.DESERT_DIARY_ELITE_COMPLETE, VarbitID.DESERT_ELITE_REWARD, VarbitID.DESERT_ELITE_COUNT}});
        m.put("FALADOR", new int[][]{
            {VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_EASY_REWARD, VarbitID.FALADOR_EASY_COUNT},
            {VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_MEDIUM_REWARD, VarbitID.FALADOR_MED_COUNT},
            {VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_HARD_REWARD, VarbitID.FALADOR_HARD_COUNT},
            {VarbitID.FALADOR_DIARY_ELITE_COMPLETE, VarbitID.FALADOR_ELITE_REWARD, VarbitID.FALADOR_ELITE_COUNT}});
        m.put("FREMENNIK", new int[][]{
            {VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_EASY_REWARD, VarbitID.FREMENNIK_EASY_COUNT},
            {VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_MEDIUM_REWARD, VarbitID.FREMENNIK_MED_COUNT},
            {VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_HARD_REWARD, VarbitID.FREMENNIK_HARD_COUNT},
            {VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE, VarbitID.FREMENNIK_ELITE_REWARD, VarbitID.FREMENNIK_ELITE_COUNT}});
        m.put("KANDARIN", new int[][]{
            {VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_EASY_REWARD, VarbitID.KANDARIN_EASY_COUNT},
            {VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_MEDIUM_REWARD, VarbitID.KANDARIN_MED_COUNT},
            {VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_HARD_REWARD, VarbitID.KANDARIN_HARD_COUNT},
            {VarbitID.KANDARIN_DIARY_ELITE_COMPLETE, VarbitID.KANDARIN_ELITE_REWARD, VarbitID.KANDARIN_ELITE_COUNT}});
        m.put("KARAMJA", new int[][]{
            {VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_EASY_REWARD, VarbitID.KARAMJA_EASY_COUNT},
            {VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_MED_REWARD, VarbitID.KARAMJA_MED_COUNT},
            {VarbitID.ATJUN_HARD_DONE, VarbitID.ATJUN_HARD_REWARD, VarbitID.KARAMJA_HARD_COUNT},
            {VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, VarbitID.KARAMJA_ELITE_REWARD, VarbitID.KARAMJA_ELITE_COUNT}});
        m.put("KOUREND", new int[][]{
            {VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_EASY_REWARD, VarbitID.KOUREND_EASY_COUNT},
            {VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_MEDIUM_REWARD, VarbitID.KOUREND_MED_COUNT},
            {VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_HARD_REWARD, VarbitID.KOUREND_HARD_COUNT},
            {VarbitID.KOUREND_DIARY_ELITE_COMPLETE, VarbitID.KOUREND_ELITE_REWARD, VarbitID.KOUREND_ELITE_COUNT}});
        m.put("LUMBRIDGE", new int[][]{
            {VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_EASY_REWARD, VarbitID.LUMBRIDGE_EASY_COUNT},
            {VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_MEDIUM_REWARD, VarbitID.LUMBRIDGE_MED_COUNT},
            {VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_HARD_REWARD, VarbitID.LUMBRIDGE_HARD_COUNT},
            {VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE, VarbitID.LUMBRIDGE_ELITE_REWARD, VarbitID.LUMBRIDGE_ELITE_COUNT}});
        m.put("MORYTANIA", new int[][]{
            {VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_EASY_REWARD, VarbitID.MORYTANIA_EASY_COUNT},
            {VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_MEDIUM_REWARD, VarbitID.MORYTANIA_MED_COUNT},
            {VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_HARD_REWARD, VarbitID.MORYTANIA_HARD_COUNT},
            {VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE, VarbitID.MORYTANIA_ELITE_REWARD, VarbitID.MORYTANIA_ELITE_COUNT}});
        m.put("VARROCK", new int[][]{
            {VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_EASY_REWARD, VarbitID.VARROCK_EASY_COUNT},
            {VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_MEDIUM_REWARD, VarbitID.VARROCK_MED_COUNT},
            {VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_HARD_REWARD, VarbitID.VARROCK_HARD_COUNT},
            {VarbitID.VARROCK_DIARY_ELITE_COMPLETE, VarbitID.VARROCK_ELITE_REWARD, VarbitID.VARROCK_ELITE_COUNT}});
        m.put("WESTERN", new int[][]{
            {VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_EASY_REWARD, VarbitID.WESTERN_EASY_COUNT},
            {VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_MEDIUM_REWARD, VarbitID.WESTERN_MED_COUNT},
            {VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_HARD_REWARD, VarbitID.WESTERN_HARD_COUNT},
            {VarbitID.WESTERN_DIARY_ELITE_COMPLETE, VarbitID.WESTERN_ELITE_REWARD, VarbitID.WESTERN_ELITE_COUNT}});
        m.put("WILDERNESS", new int[][]{
            {VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_EASY_REWARD, VarbitID.WILDERNESS_EASY_COUNT},
            {VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_MEDIUM_REWARD, VarbitID.WILDERNESS_MED_COUNT},
            {VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_HARD_REWARD, VarbitID.WILDERNESS_HARD_COUNT},
            {VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE, VarbitID.WILDERNESS_ELITE_REWARD, VarbitID.WILDERNESS_ELITE_COUNT}});
        DIARY_VARBITS = Collections.unmodifiableMap(m);
    }

    /** Packed combat-achievement completion varplayers, in task-index order. */
    static final int[] CA_TASK_VARPS = {
        VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
        VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
        VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
        VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
        VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
        VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
        VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20,
    };

    /** Per tier: {totalCompleted, tierStatus, lampClaimed} varbits, in {@link CombatRecord#TIERS} order. */
    static final int[][] CA_TIER_VARBITS = {
        {VarbitID.CA_TOTAL_TASKS_COMPLETED_EASY, VarbitID.CA_TIER_STATUS_EASY, VarbitID.CA_LAMP_CLAIMED_EASY},
        {VarbitID.CA_TOTAL_TASKS_COMPLETED_MEDIUM, VarbitID.CA_TIER_STATUS_MEDIUM, VarbitID.CA_LAMP_CLAIMED_MEDIUM},
        {VarbitID.CA_TOTAL_TASKS_COMPLETED_HARD, VarbitID.CA_TIER_STATUS_HARD, VarbitID.CA_LAMP_CLAIMED_HARD},
        {VarbitID.CA_TOTAL_TASKS_COMPLETED_ELITE, VarbitID.CA_TIER_STATUS_ELITE, VarbitID.CA_LAMP_CLAIMED_ELITE},
        {VarbitID.CA_TOTAL_TASKS_COMPLETED_MASTER, VarbitID.CA_TIER_STATUS_MASTER, VarbitID.CA_LAMP_CLAIMED_MASTER},
        {VarbitID.CA_TOTAL_TASKS_COMPLETED_GRANDMASTER, VarbitID.CA_TIER_STATUS_GRANDMASTER, VarbitID.CA_LAMP_CLAIMED_GRANDMASTER},
    };

    /**
     * Catalog version string: changes whenever RuneLite's quest list or our variable
     * mapping changes, so the website can tell which mapping produced a batch.
     */
    public static String catalogVersion()
    {
        long h = 1125899906842597L;
        for (Quest q : Quest.values())
        {
            h = 31 * h + q.getId();
        }
        for (int v : CA_TASK_VARPS) h = 31 * h + v;
        return "q" + Quest.values().length + "-d" + (DIARY_VARBITS.size() * DIARY_TIERS.length)
                + "-ca" + CA_TASK_VARPS.length + "-" + Long.toHexString(h & 0xFFFF_FFFFL);
    }

    private ProgressReader()
    {
    }

    /** Snapshot timing for instrumentation. */
    static final class Timing
    {
        long questNanos;
        long totalNanos;
    }

    /**
     * Reads every category. Never throws for a single bad quest: a failing quest
     * marks the whole quest category unavailable for this scan.
     */
    public static ProgressObservation read(Client client, long nowMs, String catalogVersion, Timing timing)
    {
        long t0 = System.nanoTime();

        // ── Quests ───────────────────────────────────────────────────────────
        Map<Integer, QuestRecord> quests = new LinkedHashMap<>();
        String questCoverage = ProgressCoverage.COMPLETE;
        int finished = 0;
        try
        {
            for (Quest q : Quest.values())
            {
                QuestStatus status = map(q.getState(client));
                if (status == QuestStatus.COMPLETED) finished++;
                quests.put(q.getId(), new QuestRecord(q.getId(), q.name(), q.getName(), status));
            }
        }
        catch (RuntimeException e)
        {
            questCoverage = ProgressCoverage.UNAVAILABLE;
        }
        long t1 = System.nanoTime();

        int questPoints = client.getVarpValue(VarPlayerID.QP);
        if (!questsLookLoaded(finished, questPoints))
        {
            questCoverage = ProgressCoverage.UNAVAILABLE;
        }

        // ── Diaries ──────────────────────────────────────────────────────────
        Map<String, DiaryRecord> diaries = new LinkedHashMap<>();
        for (Map.Entry<String, int[][]> e : DIARY_VARBITS.entrySet())
        {
            int[][] tiers = e.getValue();
            for (int i = 0; i < DIARY_TIERS.length; i++)
            {
                int[] vb = tiers[i];
                DiaryRecord d = new DiaryRecord(e.getKey(), DIARY_TIERS[i],
                        client.getVarbitValue(vb[0]), client.getVarbitValue(vb[1]), client.getVarbitValue(vb[2]),
                        vb[0], vb[1], vb[2]);
                diaries.put(d.key(), d);
            }
        }
        // There is no independent total to cross-check diaries against; they are
        // accepted once the readiness gate's quiet period has passed.
        String diaryCoverage = ProgressCoverage.COMPLETE;

        // ── Combat achievements ──────────────────────────────────────────────
        Map<Integer, Long> words = new LinkedHashMap<>();
        for (int varp : CA_TASK_VARPS)
        {
            words.put(varp, CombatRecord.unsignedWord(client.getVarpValue(varp)));
        }
        Map<String, Integer> tierCompleted = new LinkedHashMap<>();
        Map<String, Integer> tierStatus = new LinkedHashMap<>();
        Map<String, Integer> lamps = new LinkedHashMap<>();
        for (int i = 0; i < CombatRecord.TIERS.length; i++)
        {
            tierCompleted.put(CombatRecord.TIERS[i], client.getVarbitValue(CA_TIER_VARBITS[i][0]));
            tierStatus.put(CombatRecord.TIERS[i], client.getVarbitValue(CA_TIER_VARBITS[i][1]));
            lamps.put(CombatRecord.TIERS[i], client.getVarbitValue(CA_TIER_VARBITS[i][2]));
        }
        CombatRecord combat = new CombatRecord(words, tierCompleted, client.getVarbitValue(VarbitID.CA_POINTS),
                tierStatus, lamps);
        String combatCoverage = combatCoverage(combat);

        long t2 = System.nanoTime();
        if (timing != null)
        {
            timing.questNanos = t1 - t0;
            timing.totalNanos = t2 - t0;
        }

        return new ProgressObservation(nowMs, catalogVersion, quests, questCoverage, questPoints,
                diaries, diaryCoverage, combat, combatCoverage);
    }

    static QuestStatus map(QuestState state)
    {
        if (state == null) return QuestStatus.UNKNOWN;
        switch (state)
        {
            case FINISHED:    return QuestStatus.COMPLETED;
            case IN_PROGRESS: return QuestStatus.IN_PROGRESS;
            case NOT_STARTED: return QuestStatus.NOT_STARTED;
            default:          return QuestStatus.UNKNOWN;
        }
    }

    /**
     * Quest variables still loading look like "nothing finished" while the server
     * has already sent a non-zero quest point total. A genuinely new account has
     * QP 0 and 0 finished quests, which passes.
     */
    static boolean questsLookLoaded(int finishedQuests, int questPoints)
    {
        return !(finishedQuests == 0 && questPoints > 0);
    }

    /**
     * Combat achievements are COMPLETE when the number of set task bits equals the sum
     * of the game's per-tier completed counters; RAW_UNVERIFIED when they disagree
     * (the mapping may have changed); UNAVAILABLE when the bits are all zero but the
     * counters say tasks are done (still loading).
     */
    static String combatCoverage(CombatRecord c)
    {
        int bits = c.completedBitCount();
        int counters = c.tierCompletedSum();
        if (c.allWordsZero() && (counters > 0 || c.getPoints() > 0)) return ProgressCoverage.UNAVAILABLE;
        return bits == counters ? ProgressCoverage.COMPLETE : ProgressCoverage.RAW_UNVERIFIED;
    }
}
