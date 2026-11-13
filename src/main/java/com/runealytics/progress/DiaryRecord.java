package com.runealytics.progress;

import java.util.Objects;

/**
 * One achievement-diary tier, as raw game values. Immutable.
 *
 * <p>Values are kept raw on purpose. The varbit names suggest "all tasks complete",
 * "reward claimed" and "tasks completed count", but those meanings have not been
 * verified in game yet, so the website interprets them (and can reinterpret them
 * later) using the source varbit ids included here.</p>
 */
public final class DiaryRecord
{
    private final String region;
    private final String tier;
    private final int completeVar;
    private final int rewardVar;
    private final int taskCount;
    private final int completeVarbit;
    private final int rewardVarbit;
    private final int countVarbit;

    public DiaryRecord(String region, String tier, int completeVar, int rewardVar, int taskCount,
                       int completeVarbit, int rewardVarbit, int countVarbit)
    {
        this.region = region;
        this.tier = tier;
        this.completeVar = completeVar;
        this.rewardVar = rewardVar;
        this.taskCount = taskCount;
        this.completeVarbit = completeVarbit;
        this.rewardVarbit = rewardVarbit;
        this.countVarbit = countVarbit;
    }

    /** Stable key, e.g. {@code ARDOUGNE:EASY}. */
    public static String key(String region, String tier)
    {
        return region + ":" + tier;
    }

    public String key()
    {
        return key(region, tier);
    }

    public String getRegion()
    {
        return region;
    }

    public String getTier()
    {
        return tier;
    }

    public int getCompleteVar()
    {
        return completeVar;
    }

    public int getRewardVar()
    {
        return rewardVar;
    }

    public int getTaskCount()
    {
        return taskCount;
    }

    public int getCompleteVarbit()
    {
        return completeVarbit;
    }

    public int getRewardVarbit()
    {
        return rewardVarbit;
    }

    public int getCountVarbit()
    {
        return countVarbit;
    }

    /** True when only the observed values differ (varbit ids are the same mapping). */
    boolean sameValues(DiaryRecord other)
    {
        return other != null && completeVar == other.completeVar
                && rewardVar == other.rewardVar && taskCount == other.taskCount;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof DiaryRecord)) return false;
        DiaryRecord that = (DiaryRecord) o;
        return completeVar == that.completeVar && rewardVar == that.rewardVar
                && taskCount == that.taskCount && completeVarbit == that.completeVarbit
                && rewardVarbit == that.rewardVarbit && countVarbit == that.countVarbit
                && Objects.equals(region, that.region) && Objects.equals(tier, that.tier);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(region, tier, completeVar, rewardVar, taskCount);
    }
}
