package com.runealytics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Edge-case coverage for the dependency-free XP formatting helpers that drive
 * every label in the XP Tracker panel (counts, compact accents, durations,
 * time-to-level and "time ago").
 */
public class XpFormatTest
{
    @Test
    public void comma_groupsThousands()
    {
        assertEquals("0", XpFormat.comma(0));
        assertEquals("999", XpFormat.comma(999));
        assertEquals("1,250", XpFormat.comma(1250));
        assertEquals("13,034,431", XpFormat.comma(13_034_431L));
        assertEquals("-1,250", XpFormat.comma(-1250));
    }

    @Test
    public void compact_lowercaseUnitsWithTrimmedDecimals()
    {
        assertEquals("0", XpFormat.compact(0));
        assertEquals("999", XpFormat.compact(999));
        assertEquals("1k", XpFormat.compact(1_000));
        assertEquals("42.1k", XpFormat.compact(42_100));
        assertEquals("42k", XpFormat.compact(42_000));   // trailing .0 trimmed
        assertEquals("1m", XpFormat.compact(1_000_000));
        assertEquals("1.4m", XpFormat.compact(1_400_000));
        assertEquals("1b", XpFormat.compact(1_000_000_000));
        assertEquals("-1.5k", XpFormat.compact(-1_500));  // magnitude via abs
    }

    @Test
    public void compactUpper_wholeKThenTwoDecimalMillions()
    {
        assertEquals("500", XpFormat.compactUpper(500));
        assertEquals("873K", XpFormat.compactUpper(873_000));
        assertEquals("8.86M", XpFormat.compactUpper(8_860_000));
        assertEquals("12.45M", XpFormat.compactUpper(12_450_000));
        assertEquals("1.00B", XpFormat.compactUpper(1_000_000_000));
    }

    @Test
    public void duration_alwaysHhMmSsAndClampsNegative()
    {
        assertEquals("00:00:00", XpFormat.duration(0));
        assertEquals("00:00:00", XpFormat.duration(-5_000));
        assertEquals("00:00:05", XpFormat.duration(5_000));
        assertEquals("02:14:33", XpFormat.duration(8_073_000));
        assertEquals("100:00:00", XpFormat.duration(360_000_000L)); // hours uncapped
    }

    @Test
    public void timeToLevel_humanUnitsWithDashAndCap()
    {
        assertEquals("—", XpFormat.timeToLevel(0));
        assertEquals("—", XpFormat.timeToLevel(-1));
        assertEquals("45s", XpFormat.timeToLevel(45_000));
        assertEquals("24m 10s", XpFormat.timeToLevel(1_450_000));
        assertEquals("3h 24m", XpFormat.timeToLevel(12_240_000));
        assertEquals(">99h", XpFormat.timeToLevel(360_000_000L));
    }

    @Test
    public void ago_bucketsElapsedTime()
    {
        assertEquals("just now", XpFormat.ago(-100));
        assertEquals("just now", XpFormat.ago(3_000));
        assertEquals("6s ago", XpFormat.ago(6_000));
        assertEquals("2 min ago", XpFormat.ago(120_000));
        assertEquals("1h ago", XpFormat.ago(3_600_000));
        assertEquals("2h ago", XpFormat.ago(7_200_000));
    }
}
