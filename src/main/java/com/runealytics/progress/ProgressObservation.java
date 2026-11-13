package com.runealytics.progress;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything read in one progress scan on the client thread. Immutable.
 *
 * <p>A category whose coverage is not usable (see {@link ProgressCoverage}) must be
 * ignored by consumers; its record map may be empty or partial.</p>
 */
public final class ProgressObservation
{
    private final long capturedAtMs;
    private final String catalogVersion;

    private final Map<Integer, QuestRecord> quests;
    private final String questCoverage;
    private final int questPoints;

    private final Map<String, DiaryRecord> diaries;
    private final String diaryCoverage;

    private final CombatRecord combat;
    private final String combatCoverage;

    public ProgressObservation(long capturedAtMs, String catalogVersion,
                               Map<Integer, QuestRecord> quests, String questCoverage, int questPoints,
                               Map<String, DiaryRecord> diaries, String diaryCoverage,
                               CombatRecord combat, String combatCoverage)
    {
        this.capturedAtMs = capturedAtMs;
        this.catalogVersion = catalogVersion;
        this.quests = Collections.unmodifiableMap(new LinkedHashMap<>(quests));
        this.questCoverage = questCoverage;
        this.questPoints = questPoints;
        this.diaries = Collections.unmodifiableMap(new LinkedHashMap<>(diaries));
        this.diaryCoverage = diaryCoverage;
        this.combat = combat;
        this.combatCoverage = combat == null ? ProgressCoverage.UNAVAILABLE : combatCoverage;
    }

    public long getCapturedAtMs()
    {
        return capturedAtMs;
    }

    public String getCatalogVersion()
    {
        return catalogVersion;
    }

    public Map<Integer, QuestRecord> getQuests()
    {
        return quests;
    }

    public String getQuestCoverage()
    {
        return questCoverage;
    }

    public int getQuestPoints()
    {
        return questPoints;
    }

    public Map<String, DiaryRecord> getDiaries()
    {
        return diaries;
    }

    public String getDiaryCoverage()
    {
        return diaryCoverage;
    }

    public CombatRecord getCombat()
    {
        return combat;
    }

    public String getCombatCoverage()
    {
        return combatCoverage;
    }

    /** True when every category is usable, i.e. the scan passed all readiness checks. */
    public boolean fullyUsable()
    {
        return ProgressCoverage.isUsable(questCoverage)
                && ProgressCoverage.isUsable(diaryCoverage)
                && ProgressCoverage.isUsable(combatCoverage);
    }
}
