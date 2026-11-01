package com.runealytics.progress;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Last known-good progress for ONE account (account hash + RuneScape profile type).
 * Immutable; every update produces a new instance.
 *
 * <p>Each category is kept independently: a scan where a category was unavailable
 * leaves that category's previous values (and timestamp) untouched. A {@code null}
 * category map means the category has never been observed for this account, which
 * is what turns the next observation into a historical import (no activity events).</p>
 */
public final class ProgressBaseline
{
    private final String catalogVersion;

    private final Map<Integer, QuestRecord> quests;
    private final Long questsObservedAtMs;

    private final Map<String, DiaryRecord> diaries;
    private final Long diariesObservedAtMs;

    private final CombatRecord combat;
    private final String combatCoverage;
    private final Long combatObservedAtMs;

    public ProgressBaseline(String catalogVersion,
                            Map<Integer, QuestRecord> quests, Long questsObservedAtMs,
                            Map<String, DiaryRecord> diaries, Long diariesObservedAtMs,
                            CombatRecord combat, String combatCoverage, Long combatObservedAtMs)
    {
        this.catalogVersion = catalogVersion;
        this.quests = quests == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(quests));
        this.questsObservedAtMs = questsObservedAtMs;
        this.diaries = diaries == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(diaries));
        this.diariesObservedAtMs = diariesObservedAtMs;
        this.combat = combat;
        this.combatCoverage = combatCoverage;
        this.combatObservedAtMs = combatObservedAtMs;
    }

    /** A baseline with nothing observed yet. */
    public static ProgressBaseline empty()
    {
        return new ProgressBaseline(null, null, null, null, null, null, null, null);
    }

    public String getCatalogVersion()
    {
        return catalogVersion;
    }

    public Map<Integer, QuestRecord> getQuests()
    {
        return quests;
    }

    public Long getQuestsObservedAtMs()
    {
        return questsObservedAtMs;
    }

    public Map<String, DiaryRecord> getDiaries()
    {
        return diaries;
    }

    public Long getDiariesObservedAtMs()
    {
        return diariesObservedAtMs;
    }

    public CombatRecord getCombat()
    {
        return combat;
    }

    public String getCombatCoverage()
    {
        return combatCoverage;
    }

    public Long getCombatObservedAtMs()
    {
        return combatObservedAtMs;
    }

    /** True when no category has ever been observed. */
    public boolean isEmpty()
    {
        return quests == null && diaries == null && combat == null;
    }
}
