package com.runealytics.progress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Pure comparison of a new {@link ProgressObservation} against an account's
 * {@link ProgressBaseline}. No RuneLite types, no I/O; safe to unit test.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>A category whose coverage is not usable is skipped: its baseline values and
 *       timestamp are kept, nothing is reported as changed.</li>
 *   <li>A category never observed before is a <b>historical import</b>: every record
 *       is reported as changed (so it is uploaded) but <b>no transitions</b> are made.</li>
 *   <li>A record that is new to an existing category (e.g. a quest added in a game
 *       update) is uploaded without a transition: we never saw its previous value.</li>
 *   <li>Quest regressions away from COMPLETED are uploaded but never produce an
 *       event; the website decides whether to accept them.</li>
 * </ul>
 */
public final class ProgressDiffer
{
    /** Upper bound on transitions from a single scan (protects the outbox and payload size). */
    static final int MAX_TRANSITIONS_PER_DIFF = 500;

    private ProgressDiffer()
    {
    }

    /** Result of one diff. Immutable. */
    public static final class Result
    {
        private final ProgressBaseline baseline;
        private final Set<Integer> changedQuests;
        private final Set<String> changedDiaries;
        private final boolean combatChanged;
        private final List<ProgressTransition> transitions;
        private final int droppedTransitions;

        Result(ProgressBaseline baseline, Set<Integer> changedQuests, Set<String> changedDiaries,
               boolean combatChanged, List<ProgressTransition> transitions, int droppedTransitions)
        {
            this.baseline = baseline;
            this.changedQuests = Collections.unmodifiableSet(changedQuests);
            this.changedDiaries = Collections.unmodifiableSet(changedDiaries);
            this.combatChanged = combatChanged;
            this.transitions = Collections.unmodifiableList(transitions);
            this.droppedTransitions = droppedTransitions;
        }

        /** The updated baseline to keep for the next diff. */
        public ProgressBaseline getBaseline()
        {
            return baseline;
        }

        public Set<Integer> getChangedQuests()
        {
            return changedQuests;
        }

        public Set<String> getChangedDiaries()
        {
            return changedDiaries;
        }

        public boolean isCombatChanged()
        {
            return combatChanged;
        }

        public List<ProgressTransition> getTransitions()
        {
            return transitions;
        }

        public int getDroppedTransitions()
        {
            return droppedTransitions;
        }

        public boolean hasChanges()
        {
            return !changedQuests.isEmpty() || !changedDiaries.isEmpty() || combatChanged || !transitions.isEmpty();
        }
    }

    /**
     * @param old       the account's baseline (use {@link ProgressBaseline#empty()} when none)
     * @param obs       the new observation
     * @param catchUp   true for the first scan after login / plugin start
     * @param idFactory transition id generator (UUIDs in production; deterministic in tests)
     */
    public static Result diff(ProgressBaseline old, ProgressObservation obs, boolean catchUp,
                              Supplier<String> idFactory)
    {
        if (old == null) old = ProgressBaseline.empty();
        final String detection = catchUp ? ProgressTransition.DETECTION_CATCH_UP : ProgressTransition.DETECTION_LIVE;
        final long now = obs.getCapturedAtMs();

        List<ProgressTransition> transitions = new ArrayList<>();
        Set<Integer> changedQuests = new LinkedHashSet<>();
        Set<String> changedDiaries = new LinkedHashSet<>();
        boolean combatChanged = false;

        // ── Quests ───────────────────────────────────────────────────────────
        Map<Integer, QuestRecord> quests = old.getQuests();
        Long questsAt = old.getQuestsObservedAtMs();
        if (ProgressCoverage.COMPLETE.equals(obs.getQuestCoverage()))
        {
            Map<Integer, QuestRecord> prev = old.getQuests();
            for (QuestRecord q : obs.getQuests().values())
            {
                QuestRecord before = prev == null ? null : prev.get(q.getId());
                if (before != null && before.equals(q)) continue;
                changedQuests.add(q.getId());
                if (before == null) continue; // historical import or quest new to the catalog
                if (isQuestTransition(before.getStatus(), q.getStatus()))
                {
                    transitions.add(new ProgressTransition(idFactory.get(), ProgressTransition.CATEGORY_QUEST,
                            String.valueOf(q.getId()), q.getName(), "state",
                            before.getStatus().name(), q.getStatus().name(), now, detection, questsAt));
                }
            }
            quests = obs.getQuests();
            questsAt = now;
        }

        // ── Diaries ──────────────────────────────────────────────────────────
        Map<String, DiaryRecord> diaries = old.getDiaries();
        Long diariesAt = old.getDiariesObservedAtMs();
        if (ProgressCoverage.COMPLETE.equals(obs.getDiaryCoverage()))
        {
            Map<String, DiaryRecord> prev = old.getDiaries();
            for (DiaryRecord d : obs.getDiaries().values())
            {
                DiaryRecord before = prev == null ? null : prev.get(d.key());
                if (before != null && before.equals(d)) continue;
                changedDiaries.add(d.key());
                if (before == null) continue;
                String name = d.getRegion() + " " + d.getTier();
                if (before.getCompleteVar() == 0 && d.getCompleteVar() != 0)
                {
                    transitions.add(diaryTransition(idFactory, d, name, "complete",
                            before.getCompleteVar(), d.getCompleteVar(), now, detection, diariesAt));
                }
                if (before.getRewardVar() == 0 && d.getRewardVar() != 0)
                {
                    transitions.add(diaryTransition(idFactory, d, name, "reward",
                            before.getRewardVar(), d.getRewardVar(), now, detection, diariesAt));
                }
                if (d.getTaskCount() > before.getTaskCount())
                {
                    transitions.add(diaryTransition(idFactory, d, name, "task_count",
                            before.getTaskCount(), d.getTaskCount(), now, detection, diariesAt));
                }
            }
            diaries = obs.getDiaries();
            diariesAt = now;
        }

        // ── Combat achievements ──────────────────────────────────────────────
        CombatRecord combat = old.getCombat();
        String combatCoverage = old.getCombatCoverage();
        Long combatAt = old.getCombatObservedAtMs();
        if (ProgressCoverage.isUsable(obs.getCombatCoverage()) && obs.getCombat() != null)
        {
            CombatRecord before = old.getCombat();
            CombatRecord now2 = obs.getCombat();
            if (before == null || !before.equals(now2) || !Objects.equals(combatCoverage, obs.getCombatCoverage()))
            {
                combatChanged = true;
            }
            if (before != null)
            {
                addCombatTransitions(transitions, idFactory, before, now2, now, detection, combatAt);
            }
            combat = now2;
            combatCoverage = obs.getCombatCoverage();
            combatAt = now;
        }

        int dropped = 0;
        if (transitions.size() > MAX_TRANSITIONS_PER_DIFF)
        {
            dropped = transitions.size() - MAX_TRANSITIONS_PER_DIFF;
            transitions = new ArrayList<>(transitions.subList(0, MAX_TRANSITIONS_PER_DIFF));
        }

        String catalog = obs.getCatalogVersion() != null ? obs.getCatalogVersion() : old.getCatalogVersion();
        ProgressBaseline updated = new ProgressBaseline(catalog, quests, questsAt, diaries, diariesAt,
                combat, combatCoverage, combatAt);
        return new Result(updated, changedQuests, changedDiaries, combatChanged, transitions, dropped);
    }

    /** Forward movement only: NOT_STARTED→IN_PROGRESS, NOT_STARTED→COMPLETED, IN_PROGRESS→COMPLETED. */
    static boolean isQuestTransition(QuestStatus from, QuestStatus to)
    {
        if (from == null || to == null || from == to) return false;
        if (from == QuestStatus.UNKNOWN || to == QuestStatus.UNKNOWN) return false;
        return rank(to) > rank(from);
    }

    private static int rank(QuestStatus s)
    {
        switch (s)
        {
            case NOT_STARTED: return 0;
            case IN_PROGRESS: return 1;
            case COMPLETED:   return 2;
            default:          return -1;
        }
    }

    private static ProgressTransition diaryTransition(Supplier<String> ids, DiaryRecord d, String name, String field,
                                                      int from, int to, long now, String detection, Long prevAt)
    {
        return new ProgressTransition(ids.get(), ProgressTransition.CATEGORY_DIARY, d.key(), name, field,
                String.valueOf(from), String.valueOf(to), now, detection, prevAt);
    }

    private static void addCombatTransitions(List<ProgressTransition> out, Supplier<String> ids,
                                             CombatRecord before, CombatRecord after,
                                             long now, String detection, Long prevAt)
    {
        // Newly set task bits. Key = "<varpId>:<bit>"; the website maps it to a task.
        for (Map.Entry<Integer, Long> e : after.getTaskWords().entrySet())
        {
            long newWord = e.getValue() == null ? 0L : e.getValue();
            Long oldBoxed = before.getTaskWords().get(e.getKey());
            if (oldBoxed == null) continue; // varp new to the catalog: no previous value seen
            long gained = newWord & ~oldBoxed & 0xFFFF_FFFFL;
            for (int bit = 0; bit < 32 && gained != 0; bit++)
            {
                if (CombatRecord.isBitSet(gained, bit))
                {
                    out.add(new ProgressTransition(ids.get(), ProgressTransition.CATEGORY_CA_TASK,
                            e.getKey() + ":" + bit, null, "completed", "0", "1", now, detection, prevAt));
                }
            }
        }

        Map<String, Integer> status = new LinkedHashMap<>(after.getTierStatus());
        for (Map.Entry<String, Integer> e : status.entrySet())
        {
            Integer prev = before.getTierStatus().get(e.getKey());
            if (prev != null && !prev.equals(e.getValue()))
            {
                out.add(new ProgressTransition(ids.get(), ProgressTransition.CATEGORY_CA_TIER, e.getKey(),
                        e.getKey(), "status", String.valueOf(prev), String.valueOf(e.getValue()), now, detection, prevAt));
            }
        }
        for (Map.Entry<String, Integer> e : after.getLampClaimed().entrySet())
        {
            Integer prev = before.getLampClaimed().get(e.getKey());
            if (prev != null && prev == 0 && e.getValue() != null && e.getValue() != 0)
            {
                out.add(new ProgressTransition(ids.get(), ProgressTransition.CATEGORY_CA_TIER, e.getKey(),
                        e.getKey(), "lamp_claimed", "0", String.valueOf(e.getValue()), now, detection, prevAt));
            }
        }
    }
}
