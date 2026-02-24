package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Root plugin panel rendered in the RuneLite sidebar.
 *
 * <h2>Tab persistence</h2>
 * The index of the active tab is saved to {@link ConfigManager} under the key
 * {@code "runealytics:lastTab"} whenever the user switches tabs, and restored
 * on the next call to {@link #restoreLastTab()}.  The plugin calls
 * {@link #restoreLastTab()} from {@link RuneAlyticsPlugin#onPlayerSpawned}
 * after the player has fully logged in.
 *
 * <h2>Feature-flag driven tab visibility</h2>
 * {@link #applyFeatureFlags(Map)} accepts a map of {@code featureName → enabled}
 * received from the RuneAlytics server (fetched in
 * {@link RuneAlyticsPlugin#onGameStateChanged}).  Any tab whose feature flag is
 * {@code false} is hidden; tabs that become enabled are shown again.  The
 * active-tab index is clamped so the panel never shows a blank screen after a
 * tab is hidden.
 *
 * <h2>Integration in the plugin</h2>
 * <pre>
 * // In startUp():
 * mainPanel = injector.getInstance(RuneAlyticsPanel.class);
 * mainPanel.addTab("Loot Tracker",  FEATURE_LOOT,   lootPanel);
 * mainPanel.addTab("Match Finder",  FEATURE_MATCHES, matchPanel);
 * clientToolbar.addNavigation(navButton);
 *
 * // In onGameStateChanged(LOGGED_IN):
 * executor.submit(() -> {
 *     Map<String,Boolean> flags = apiClient.fetchFeatureFlags(username);
 *     SwingUtilities.invokeLater(() -> mainPanel.applyFeatureFlags(flags));
 * });
 *
 * // In onPlayerSpawned():
 * SwingUtilities.invokeLater(() -> mainPanel.restoreLastTab());
 * </pre>
 */
@Slf4j
@Singleton
public class RuneAlyticsPanel extends PluginPanel
{
    // ── Feature-flag key constants (must match keys returned by the server) ───
    public static final String FEATURE_LOOT    = "loot_tracker";
    public static final String FEATURE_MATCHES = "match_finder";

    // ── Config keys ───────────────────────────────────────────────────────────
    private static final String CONFIG_GROUP  = "runealytics";
    private static final String CONFIG_LAST_TAB = "lastTab";

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final ConfigManager configManager;
    private final JPanel lootPanel = new JPanel();
    private final JPanel matchFinderPanel = new JPanel(); // same for match finder

    // ── Internal tab registry ─────────────────────────────────────────────────
    /**
     * Holds the metadata for each registered tab so we can show/hide them
     * without losing their components or their feature-flag associations.
     */
    private static class TabEntry
    {
        final String       title;
        final String       featureKey; // null = always visible
        final JComponent   content;
        boolean            visible;

        TabEntry(String title, String featureKey, JComponent content)
        {
            this.title      = title;
            this.featureKey = featureKey;
            this.content    = content;
            this.visible    = true;
        }
    }

    private final java.util.List<TabEntry> tabRegistry = new java.util.ArrayList<>();

    // ── Swing components ──────────────────────────────────────────────────────
    private final JTabbedPane tabbedPane;

    // ── Constructor ───────────────────────────────────────────────────────────
    @Inject
    public RuneAlyticsPanel(ConfigManager configManager)
    {
        super(false); // we manage our own layout
        this.configManager = configManager;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        tabbedPane.setForeground(Color.WHITE);
        tabbedPane.setFont(tabbedPane.getFont().deriveFont(Font.PLAIN, 11f));
        tabbedPane.setBorder(new EmptyBorder(0, 0, 0, 0));

        // Save the active tab whenever the user switches tabs
        tabbedPane.addChangeListener(e -> saveLastTab());

        add(tabbedPane, BorderLayout.CENTER);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Tab registration
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Adds a new tab that is always visible (no feature gate).
     *
     * @param title   label shown on the tab
     * @param content panel to display when the tab is selected
     */
    public void addTab(String title, JComponent content)
    {
        addTab(title, null, content);
    }

    /**
     * Adds a new tab that can be shown or hidden by a server feature flag.
     *
     * @param title      label shown on the tab
     * @param featureKey the key this tab is gated behind (e.g. {@link #FEATURE_LOOT})
     * @param content    panel to display when the tab is selected
     */
    public void addTab(String title, String featureKey, JComponent content)
    {
        SwingUtilities.invokeLater(() -> {
            TabEntry entry = new TabEntry(title, featureKey, content);
            tabRegistry.add(entry);
            tabbedPane.addTab(title, content);
            log.debug("Tab added: '{}' (feature={})", title, featureKey);
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Feature flags
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Applies a map of {@code featureKey → enabled} received from the
     * RuneAlytics server.
     *
     * <p>Tabs whose {@code featureKey} maps to {@code false} are removed from
     * the {@link JTabbedPane}; tabs that map to {@code true} (or whose key is
     * absent, meaning server didn't mention them) are kept / re-added.
     *
     * <p>Tabs registered with {@code featureKey = null} are always visible
     * and are not affected by this call.
     *
     * <p><b>Must be called on the EDT.</b>
     *
     * @param flags map from feature key to enabled state
     */
    public void applyFeatureFlags(Map<String, Boolean> flags)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(() -> applyFeatureFlags(flags));
            return;
        }

        log.info("Applying feature flags: {}", flags);

        for (TabEntry entry : tabRegistry)
        {
            if (entry.featureKey == null)
            {
                // Always-visible tab — ensure it's in the pane
                ensureTabVisible(entry);
                continue;
            }

            boolean shouldBeVisible = flags.getOrDefault(entry.featureKey, true);

            if (shouldBeVisible && !entry.visible)
            {
                // Re-enable tab: insert it at its logical position
                insertTabAtLogicalPosition(entry);
                entry.visible = true;
                log.info("Tab '{}' enabled (flag '{}')", entry.title, entry.featureKey);
            }
            else if (!shouldBeVisible && entry.visible)
            {
                // Disable tab: remove it from the pane
                int idx = findTabIndex(entry);
                if (idx >= 0)
                {
                    tabbedPane.removeTabAt(idx);
                    entry.visible = false;
                    log.info("Tab '{}' disabled (flag '{}')", entry.title, entry.featureKey);
                }
            }
        }

        // Clamp selected index so we never land on a now-removed tab
        int count = tabbedPane.getTabCount();
        if (count > 0 && tabbedPane.getSelectedIndex() >= count)
            tabbedPane.setSelectedIndex(count - 1);

        tabbedPane.revalidate();
        tabbedPane.repaint();
    }

    public void showLoggedOutState()
    {
        removeMatchFinderTab();
        tabbedPane.revalidate();
        tabbedPane.repaint();
    }

    public void showMainFeatures(boolean lootEnabled, boolean matchEnabled)
    {
        if (lootEnabled)
            addLootTab();
        else
            removeLootTab();

        if (matchEnabled)
            addMatchFinderTab();
        else
            removeMatchFinderTab();

        tabbedPane.revalidate();
        tabbedPane.repaint();
    }

    private void addLootTab()
    {
        if (tabbedPane.indexOfTab("Loot Tracker") == -1)
        {
            tabbedPane.addTab("Loot Tracker", lootPanel);
        }
    }

    private void removeLootTab()
    {
        int index = tabbedPane.indexOfTab("Loot Tracker");
        if (index != -1)
        {
            tabbedPane.removeTabAt(index);
        }
    }

    private void addMatchFinderTab()
    {
        if (tabbedPane.indexOfTab("Match Finder") == -1)
        {
            tabbedPane.addTab("Match Finder", matchFinderPanel);
        }
    }

    private void removeMatchFinderTab()
    {
        int index = tabbedPane.indexOfTab("Match Finder");
        if (index != -1)
        {
            tabbedPane.removeTabAt(index);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Tab persistence
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Saves the index of the currently selected tab to {@link ConfigManager}.
     * Called automatically on every tab-change event.
     */
    private void saveLastTab()
    {
        int idx = tabbedPane.getSelectedIndex();
        if (idx >= 0)
        {
            configManager.setConfiguration(CONFIG_GROUP, CONFIG_LAST_TAB, String.valueOf(idx));
            log.debug("Saved last tab index: {}", idx);
        }
    }

    public void showVerificationOnly()
    {
        Map<String, Boolean> flags = new HashMap<>();
        flags.put(FEATURE_LOOT, false);
        flags.put(FEATURE_MATCHES, false);

        applyFeatureFlags(flags);
    }

    /**
     * Restores the previously selected tab.
     *
     * <p>Call this from {@link RuneAlyticsPlugin#onPlayerSpawned} (on the EDT)
     * after login so the user always returns to the same tab they were on.
     */
    public void restoreLastTab()
    {
        SwingUtilities.invokeLater(() -> {
            String saved = configManager.getConfiguration(CONFIG_GROUP, CONFIG_LAST_TAB);
            if (saved == null) return;

            try
            {
                int idx = Integer.parseInt(saved.trim());
                int count = tabbedPane.getTabCount();
                if (idx >= 0 && idx < count)
                {
                    tabbedPane.setSelectedIndex(idx);
                    log.debug("Restored last tab index: {}", idx);
                }
            }
            catch (NumberFormatException ex)
            {
                log.warn("Invalid saved tab index: '{}'", saved);
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Private helpers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns the current position of a tab in the {@link JTabbedPane}, or
     * {@code -1} if it is not currently added (i.e. has been hidden).
     */
    private int findTabIndex(TabEntry entry)
    {
        for (int i = 0; i < tabbedPane.getTabCount(); i++)
        {
            if (tabbedPane.getComponentAt(i) == entry.content) return i;
        }
        return -1;
    }

    /**
     * Ensures a tab is present in the pane, adding it if it was previously
     * hidden.  No-op if already present.
     */
    private void ensureTabVisible(TabEntry entry)
    {
        if (findTabIndex(entry) < 0)
        {
            insertTabAtLogicalPosition(entry);
            entry.visible = true;
        }
    }

    /**
     * Re-inserts a tab at the correct logical position relative to the other
     * tabs in {@link #tabRegistry}.  This preserves the original tab order
     * when flags change multiple times.
     */
    private void insertTabAtLogicalPosition(TabEntry entry)
    {
        int logicalIndex = tabRegistry.indexOf(entry);

        // Count how many registry entries before this one are currently visible
        int insertAt = 0;
        for (int i = 0; i < logicalIndex; i++)
        {
            if (tabRegistry.get(i).visible) insertAt++;
        }

        // insertAt is now the correct position in the live JTabbedPane
        int clampedAt = Math.min(insertAt, tabbedPane.getTabCount());
        tabbedPane.insertTab(entry.title, null, entry.content, null, clampedAt);
    }
}