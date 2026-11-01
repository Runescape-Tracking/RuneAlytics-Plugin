package com.runealytics.progress;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Raw combat-achievement state. Immutable.
 *
 * <p>Individual task completion is stored by the game as bits in the
 * {@code CA_TASK_COMPLETED_n} varplayers. RuneLite publishes no task catalog, so
 * the plugin sends the raw words (as <b>unsigned</b> 32-bit values, so bit 31 is
 * never sign-extended) and the website decodes them with a versioned catalog.</p>
 */
public final class CombatRecord
{
    /** Tier names in game order. */
    public static final String[] TIERS = {"EASY", "MEDIUM", "HARD", "ELITE", "MASTER", "GRANDMASTER"};

    /** varp id → unsigned 32-bit word (0 .. 4294967295). Sorted by varp id. */
    private final Map<Integer, Long> taskWords;
    /** Tier → {@code CA_TOTAL_TASKS_COMPLETED_<TIER>} varbit value. */
    private final Map<String, Integer> tierCompleted;
    /** {@code CA_POINTS} varbit value. */
    private final int points;
    /** Tier → {@code CA_TIER_STATUS_<TIER>} raw varbit value (meaning not yet verified). */
    private final Map<String, Integer> tierStatus;
    /** Tier → {@code CA_LAMP_CLAIMED_<TIER>} raw varbit value. */
    private final Map<String, Integer> lampClaimed;

    public CombatRecord(Map<Integer, Long> taskWords, Map<String, Integer> tierCompleted, int points,
                        Map<String, Integer> tierStatus, Map<String, Integer> lampClaimed)
    {
        this.taskWords = Collections.unmodifiableMap(new TreeMap<>(taskWords));
        this.tierCompleted = Collections.unmodifiableMap(new LinkedHashMap<>(tierCompleted));
        this.points = points;
        this.tierStatus = Collections.unmodifiableMap(new LinkedHashMap<>(tierStatus));
        this.lampClaimed = Collections.unmodifiableMap(new LinkedHashMap<>(lampClaimed));
    }

    /** Converts a signed Java int read from a varp into its unsigned 32-bit value. */
    public static long unsignedWord(int signed)
    {
        return Integer.toUnsignedLong(signed);
    }

    /** True when {@code bit} (0..31) is set in an unsigned 32-bit word. */
    public static boolean isBitSet(long unsignedWord, int bit)
    {
        if (bit < 0 || bit > 31) throw new IllegalArgumentException("bit out of range: " + bit);
        return ((unsignedWord >>> bit) & 1L) == 1L;
    }

    public Map<Integer, Long> getTaskWords()
    {
        return taskWords;
    }

    public Map<String, Integer> getTierCompleted()
    {
        return tierCompleted;
    }

    public int getPoints()
    {
        return points;
    }

    public Map<String, Integer> getTierStatus()
    {
        return tierStatus;
    }

    public Map<String, Integer> getLampClaimed()
    {
        return lampClaimed;
    }

    /** Number of set task bits across all words. */
    public int completedBitCount()
    {
        int total = 0;
        for (Long w : taskWords.values())
        {
            if (w != null) total += Long.bitCount(w & 0xFFFF_FFFFL);
        }
        return total;
    }

    /** Sum of the per-tier completed counters reported by the game. */
    public int tierCompletedSum()
    {
        int total = 0;
        for (Integer v : tierCompleted.values())
        {
            if (v != null) total += v;
        }
        return total;
    }

    /** True when every task word is zero. */
    public boolean allWordsZero()
    {
        for (Long w : taskWords.values())
        {
            if (w != null && w != 0L) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof CombatRecord)) return false;
        CombatRecord that = (CombatRecord) o;
        return points == that.points && taskWords.equals(that.taskWords)
                && tierCompleted.equals(that.tierCompleted) && tierStatus.equals(that.tierStatus)
                && lampClaimed.equals(that.lampClaimed);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(taskWords, tierCompleted, points, tierStatus, lampClaimed);
    }
}
