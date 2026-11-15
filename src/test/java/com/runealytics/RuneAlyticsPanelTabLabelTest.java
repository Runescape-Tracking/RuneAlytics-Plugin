package com.runealytics;

import net.runelite.client.config.ConfigManager;
import org.junit.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class RuneAlyticsPanelTabLabelTest
{
    private static Map<String, Boolean> flags(boolean matches)
    {
        Map<String, Boolean> f = new HashMap<>();
        f.put(RuneAlyticsPanel.FEATURE_XP_TRACKER, true);
        f.put(RuneAlyticsPanel.FEATURE_MATCHES, matches);
        f.put(RuneAlyticsPanel.FEATURE_ADVISOR, true);
        f.put(RuneAlyticsPanel.FEATURE_VERIFICATION, true);
        return f;
    }

    @Test
    public void lootTabIsShortenedOnlyWhenMoreThanThreeTabsShow() throws Exception
    {
        RuneAlyticsPanel[] panel = new RuneAlyticsPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new RuneAlyticsPanel(mock(ConfigManager.class)));
        panel[0].addTab("Loot Tracker", null, new JPanel());
        panel[0].addTab("XP", RuneAlyticsPanel.FEATURE_XP_TRACKER, new JPanel());
        panel[0].addTab("Matches", RuneAlyticsPanel.FEATURE_MATCHES, new JPanel());
        panel[0].addTab("Settings", RuneAlyticsPanel.FEATURE_VERIFICATION, new JPanel());

        SwingUtilities.invokeAndWait(() -> panel[0].applyFeatureFlags(flags(true)));
        assertEquals(Arrays.asList("Loot", "XP", "Matches", "Settings"), panel[0].visibleTabLabels());

        SwingUtilities.invokeAndWait(() -> panel[0].applyFeatureFlags(flags(false)));
        assertEquals(Arrays.asList("Loot Tracker", "XP", "Settings"), panel[0].visibleTabLabels());
    }
}
