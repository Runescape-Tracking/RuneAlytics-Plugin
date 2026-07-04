package com.runealytics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Coverage for the {@link RewardSources} widget-to-source lookup table, the
 * single source of truth for reward-chest reading.
 */
public class RewardSourcesTest
{
    @Test
    public void containerBackedSource_hasNameAndContainer()
    {
        RewardSources.Source cox = RewardSources.BY_WIDGET.get(RewardSources.WIDGET_COX);
        assertEquals("Chambers of Xeric", cox.displayName);
        assertEquals(Integer.valueOf(RewardSources.CONTAINER_COX), cox.containerId);
        assertEquals(0, cox.widgetMaxChildren);
    }

    @Test
    public void widgetWalkedSource_hasNullContainerAndChildBudget()
    {
        RewardSources.Source titans = RewardSources.BY_WIDGET.get(RewardSources.WIDGET_ROYAL_TITANS);
        assertNull(titans.containerId);
        assertEquals(100, titans.widgetMaxChildren);
    }

    @Test
    public void sourcesHandledInline_areNotInTheMap()
    {
        // The clue-scroll reward interface is handled inline, not via BY_WIDGET.
        assertFalse(RewardSources.BY_WIDGET.containsKey(RewardSources.WIDGET_CLUE));
        assertNull(RewardSources.BY_WIDGET.get(RewardSources.WIDGET_CLUE));
    }

    @Test
    public void mapIsPopulatedWithExpectedSources()
    {
        assertTrue(RewardSources.BY_WIDGET.containsKey(RewardSources.WIDGET_BARROWS));
        assertTrue(RewardSources.BY_WIDGET.containsKey(RewardSources.WIDGET_TOA));
        assertTrue(RewardSources.BY_WIDGET.containsKey(RewardSources.WIDGET_GAUNTLET));
        assertEquals("Corrupted Gauntlet",
                RewardSources.BY_WIDGET.get(RewardSources.WIDGET_CORRUPTED_GAUNTLET).displayName);
    }
}
