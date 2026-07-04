package com.runealytics;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Coverage for the XpSparkline axis-scaling math (percentile clamp, gridline
 * increment selection, round-up, and axis labels) that determines how the XP/hr
 * chart is drawn. Pure arithmetic — no Swing painting involved.
 */
public class XpSparklineTest
{
    @Test
    public void percentile_handlesEmptyNullAndClampsIndex()
    {
        assertEquals(0L, XpSparkline.percentile(null, 0.9));
        assertEquals(0L, XpSparkline.percentile(Collections.emptyList(), 0.9));
        assertEquals(42L, XpSparkline.percentile(Collections.singletonList(42L), 0.5));
    }

    @Test
    public void percentile_picksFlooredIndex()
    {
        // (size-1)*pct, floored, clamped to [0, size-1].
        assertEquals(10L, XpSparkline.percentile(Arrays.asList(10L, 20L, 30L, 40L, 50L), 0.0));
        assertEquals(40L, XpSparkline.percentile(Arrays.asList(10L, 20L, 30L, 40L, 50L), 0.9));
        assertEquals(50L, XpSparkline.percentile(Arrays.asList(10L, 20L, 30L, 40L, 50L), 1.0));
    }

    @Test
    public void incrementFor_selectsBandByMagnitude()
    {
        assertEquals(25_000L, XpSparkline.incrementFor(50_000));
        assertEquals(25_000L, XpSparkline.incrementFor(100_000));   // boundary: not > 100k
        assertEquals(50_000L, XpSparkline.incrementFor(100_001));
        assertEquals(50_000L, XpSparkline.incrementFor(250_000));   // boundary: not > 250k
        assertEquals(100_000L, XpSparkline.incrementFor(250_001));
    }

    @Test
    public void roundUpToIncrement_reachesOrExceedsValue()
    {
        // top starts at increment*(GRID_LINES-1) = increment*4.
        assertEquals(100_000L, XpSparkline.roundUpToIncrement(0, 25_000));
        assertEquals(100_000L, XpSparkline.roundUpToIncrement(100_000, 25_000));
        assertEquals(125_000L, XpSparkline.roundUpToIncrement(120_000, 25_000));
        assertEquals(600_000L, XpSparkline.roundUpToIncrement(550_000, 100_000));
    }

    @Test
    public void axisLabel_formatsUnitsWithTrimmedMillions()
    {
        assertEquals("999", XpSparkline.axisLabel(999));
        assertEquals("1k", XpSparkline.axisLabel(1_000));
        assertEquals("25k", XpSparkline.axisLabel(25_000));
        assertEquals("1M", XpSparkline.axisLabel(1_000_000));
        assertEquals("2M", XpSparkline.axisLabel(2_000_000));
        assertEquals("2.5M", XpSparkline.axisLabel(2_500_000));
    }
}
