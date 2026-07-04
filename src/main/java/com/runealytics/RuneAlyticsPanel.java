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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root plugin panel rendered in the RuneLite sidebar.
 *
 * <h2>Tab persistence</h2>
 * The index of the active tab is saved to {@link ConfigManager} under the key
 * {@code "runealytics:lastTab"} whenever the user switches tabs, and restored
 * on the next call to {@link #restoreLastTab()}.
 *
 * <h2>Feature-flag driven tab visibility</h2>
 * {@link #applyFeatureFlags(Map)} is the single authoritative method for showing
 * and hiding tabs.  All helper methods ({@link #showVerificationOnly},
 * {@link #showMainFeatures}, {@link #showLoggedOutState}) delegate to it so that
 * {@link #onTabShownCallbacks tab-shown callbacks} are always fired when a tab
 * becomes visible.
 *
 * <h2>Verification tab</h2>
 * {@link #FEATURE_VERIFICATION} is a locally-controlled pseudo-flag (not from the
 * server).  The Settings tab is shown whenever the account is not yet verified so
 * the player always sees something actionable, and hidden once verification succeeds.
 */
@Slf4j
@Singleton
public class RuneAlyticsPanel extends PluginPanel
{
    // ── Feature-flag key constants ────────────────────────────────────────────
    /** Server-controlled: shows the Loot Tracker tab. */
    public static final String FEATURE_LOOT         = "loot_tracker";
    /** Server-controlled: shows the Match Finder tab. */
    public static final String FEATURE_MATCHES      = "match_finder";
    /**
     * Server-controlled: gates whether the plugin POSTs full XP-session snapshots
     * to {@code /api/plugin/xp/session}. Does NOT control a tab — the XP Tracker
     * tab is always visible; this only enables the server-side session sync.
     */
    public static final String FEATURE_XP_SESSION   = "xp_session";
    /**
     * Locally-controlled pseudo-flag: shows the Settings / Verification tab.
     * Set {@code true} when the account is not verified; {@code false} once verified.
     */
    public static final String FEATURE_VERIFICATION = "verification";

    // ── Config keys ───────────────────────────────────────────────────────────
    private static final String CONFIG_GROUP    = "runealytics";
    private static final String CONFIG_LAST_TAB = "lastTab";

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final ConfigManager configManager;

    // ── Last-applied feature flags ────────────────────────────────────────────
    /**
     * The most recent flags passed to {@link #applyFeatureFlags}.  Stored so
     * that tabs registered <em>after</em> the first flag fetch (which happens
     * asynchronously in startUp) immediately start in the correct
     * visible/hidden state instead of always defaulting to visible.
     */
    private Map<String, Boolean> lastAppliedFlags = null;

    // ── Internal tab registry ─────────────────────────────────────────────────
    private static class TabEntry
    {
        final String     title;
        final String     featureKey; // null = always visible
        final JComponent content;
        boolean          visible;

        TabEntry(String title, String featureKey, JComponent content)
        {
            this.title      = title;
            this.featureKey = featureKey;
            this.content    = content;
            this.visible    = true;
        }
    }

    private final List<TabEntry> tabRegistry = new ArrayList<>();

    /**
     * Callbacks fired on the EDT immediately after a tab transitions from
     * hidden → visible.  Keyed by {@code featureKey}.
     *
     * <p>Register with {@link #registerOnTabShownCallback}.  Use this to reload
     * data when a tab is re-enabled after being hidden by a feature flag — for
     * example, reload loot history when the Loot Tracker tab reappears.
     */
    private final Map<String, Runnable> onTabShownCallbacks = new HashMap<>();

    // ── Swing components ──────────────────────────────────────────────────────
    private final JTabbedPane tabbedPane;

    // ── Constructor ───────────────────────────────────────────────────────────
    @Inject
    public RuneAlyticsPanel(ConfigManager configManager)
    {
        super(false);
        this.configManager = configManager;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // getPreferredSize() returns the parent height so the tab pane never
        // bubbles a max-of-all-tabs preferred height up and inflates the window.
        // SCROLL_TAB_LAYOUT keeps all tabs on a single row.
        // Calibri 10 pt fits three tabs within PluginPanel.PANEL_WIDTH (220 px).
        tabbedPane = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT)
        {
            @Override
            public Dimension getPreferredSize()
            {
                Container p = getParent();
                int h = (p != null && p.getHeight() > 0) ? p.getHeight() : 400;
                return new Dimension(PluginPanel.PANEL_WIDTH + 10, h);
            }
        };
        tabbedPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        tabbedPane.setForeground(Color.WHITE);
        tabbedPane.setFont(new Font("Calibri", Font.PLAIN, 11));
        tabbedPane.setBorder(new EmptyBorder(0, 0, 0, 0));

        tabbedPane.addChangeListener(e -> saveLastTab());

        add(tabbedPane, BorderLayout.CENTER);
    }

    /**
     * Returns the parent container's current height as the preferred height, or
     * 400 px when there is no laid-out parent (e.g. a floating window). Mirroring
     * the parent height keeps the preferred size equal to the rendered size so
     * revalidation does not resize the frame.
     */
    @Override
    public Dimension getPreferredSize()
    {
        Container p = getParent();
        int h = (p != null && p.getHeight() > 0) ? p.getHeight() : 400;
        return new Dimension(PluginPanel.PANEL_WIDTH + 10, h);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Tab registration
    // ═════════════════════════════════════════════════════════════════════════

    /** Adds a new tab that is always visible (no feature gate). */
    public void addTab(String title, JComponent content)
    {
        addTab(title, null, content);
    }

    /**
     * Adds a new tab gated behind a feature flag.
     *
     * @param title      label shown on the tab
     * @param featureKey the key this tab is controlled by (e.g. {@link #FEATURE_LOOT})
     * @param content    panel to display when the tab is selected
     */
    public void addTab(String title, String featureKey, JComponent content)
    {
        SwingUtilities.invokeLater(() -> {
            TabEntry entry = new TabEntry(title, featureKey, content);
            tabRegistry.add(entry);
            tabbedPane.addTab(title, content);
            log.debug("Tab added: '{}' (feature={})", title, featureKey);

            // If feature flags were already applied before this tab was
            // registered (race between async tab construction and the initial
            // flag fetch), re-apply the stored flags so the tab starts hidden
            // rather than defaulting to visible.
            if (lastAppliedFlags != null)
            {
                applyFeatureFlags(lastAppliedFlags);
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Tab-shown callbacks
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Registers a callback that fires on the EDT whenever the tab for
     * {@code featureKey} transitions from hidden to visible.
     *
     * <p>Example — reload loot data when the Loot Tracker tab is re-enabled:
     * <pre>
     * mainPanel.registerOnTabShownCallback(FEATURE_LOOT,
     *     () -> lootManager.loadFromStorage());
     * </pre>
     *
     * @param featureKey the feature key of the tab to watch
     * @param callback   action to run on the EDT when the tab becomes visible
     */
    public void registerOnTabShownCallback(String featureKey, Runnable callback)
    {
        if (featureKey != null && callback != null)
        {
            onTabShownCallbacks.put(featureKey, callback);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Feature flags  (single authoritative write path)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Applies a map of {@code featureKey → enabled}.
     *
     * <ul>
     *   <li>Tabs whose key maps to {@code false} are removed from the pane.</li>
     *   <li>Tabs that map to {@code true} (or whose key is absent) are shown.</li>
     *   <li>When a tab transitions hidden → visible its registered
     *       {@link #onTabShownCallbacks callback} fires so the panel can reload data.</li>
     *   <li>Tabs with {@code featureKey = null} are always visible.</li>
     * </ul>
     *
     * <p>Safe to call off the EDT — it re-dispatches automatically.
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

        log.debug("Applying feature flags: {}", flags);
        lastAppliedFlags = new HashMap<>(flags); // snapshot for late-registering tabs

        for (TabEntry entry : tabRegistry)
        {
            if (entry.featureKey == null)
            {
                ensureTabVisible(entry);
                continue;
            }

            boolean shouldBeVisible = flags.getOrDefault(entry.featureKey, true);

            if (shouldBeVisible && !entry.visible)
            {
                insertTabAtLogicalPosition(entry);
                entry.visible = true;
                log.debug("Tab '{}' enabled (flag '{}')", entry.title, entry.featureKey);

                // Fire reload callback so the panel gets fresh data
                Runnable cb = onTabShownCallbacks.get(entry.featureKey);
                if (cb != null)
                {
                    SwingUtilities.invokeLater(cb);
                }
            }
            else if (!shouldBeVisible && entry.visible)
            {
                int idx = findTabIndex(entry);
                if (idx >= 0)
                {
                    tabbedPane.removeTabAt(idx);
                    entry.visible = false;
                    log.debug("Tab '{}' disabled (flag '{}')", entry.title, entry.featureKey);
                }
            }
        }

        // Clamp selected index so we never land on a now-removed tab
        int count = tabbedPane.getTabCount();
        if (count > 0 && tabbedPane.getSelectedIndex() >= count)
            tabbedPane.setSelectedIndex(count - 1);

        // validate() lays out only this subtree, avoiding a full-frame relayout flash.
        tabbedPane.validate();
        tabbedPane.repaint();
    }

    // ── Convenience helpers — all route through applyFeatureFlags ─────────────

    /**
     * Called when an <b>unverified</b> player reaches the login screen or logs out.
     * Shows the Loot Tracker and Verification tabs so they can link their account
     * while still viewing local data.
     */
    public void showLoggedOutState()
    {
        showVerificationOnly();
    }

    /**
     * Called when the player is logged in but not yet verified (or becomes
     * unverified).  Loot Tracker is always shown for local tracking; only the
     * Match Finder requires verification and remains hidden.
     */
    public void showVerificationOnly()
    {
        Map<String, Boolean> flags = new HashMap<>();
        flags.put(FEATURE_VERIFICATION, true);
        flags.put(FEATURE_LOOT,         true);   // local tracking always available
        flags.put(FEATURE_MATCHES,      false);  // match finder requires verification
        applyFeatureFlags(flags);
    }

    /**
     * Called after feature flags are fetched for a verified account.
     *
     * <p>Verification tab stays visible so the player can always access settings.
     * Loot Tracker / Match Finder visibility is server-controlled.</p>
     *
     * @param lootEnabled  whether the Loot Tracker tab should be visible
     * @param matchEnabled whether the Match Finder tab should be visible
     */
    public void showMainFeatures(boolean lootEnabled, boolean matchEnabled)
    {
        Map<String, Boolean> flags = new HashMap<>();
        flags.put(FEATURE_VERIFICATION, true);   // always accessible
        flags.put(FEATURE_LOOT,         lootEnabled);
        flags.put(FEATURE_MATCHES,      matchEnabled);
        applyFeatureFlags(flags);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Tab persistence
    // ═════════════════════════════════════════════════════════════════════════

    private void saveLastTab()
    {
        int idx = tabbedPane.getSelectedIndex();
        if (idx >= 0)
        {
            // Persist the selected tab's title (indices shift as tabs are added/removed).
            String title = tabbedPane.getTitleAt(idx);
            configManager.setConfiguration(CONFIG_GROUP, CONFIG_LAST_TAB, title);
            log.debug("Saved last tab: {}", title);
        }
    }

    /**
     * Restores the previously selected tab.
     * Call from {@link RuneAlyticsPlugin#onPlayerSpawned} after login.
     */
    public void restoreLastTab()
    {
        SwingUtilities.invokeLater(() -> {
            String saved = configManager.getConfiguration(CONFIG_GROUP, CONFIG_LAST_TAB);
            if (saved == null || saved.trim().isEmpty()) return;

            // Look up by title; an unknown or hidden tab resolves to -1 and is ignored.
            int idx = tabbedPane.indexOfTab(saved.trim());
            if (idx >= 0)
            {
                tabbedPane.setSelectedIndex(idx);
                log.debug("Restored last tab: {}", saved.trim());
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Private helpers
    // ═════════════════════════════════════════════════════════════════════════

    private int findTabIndex(TabEntry entry)
    {
        for (int i = 0; i < tabbedPane.getTabCount(); i++)
        {
            if (tabbedPane.getComponentAt(i) == entry.content) return i;
        }
        return -1;
    }

    private void ensureTabVisible(TabEntry entry)
    {
        if (findTabIndex(entry) < 0)
        {
            insertTabAtLogicalPosition(entry);
            entry.visible = true;
        }
    }

    private void insertTabAtLogicalPosition(TabEntry entry)
    {
        int logicalIndex = tabRegistry.indexOf(entry);

        int insertAt = 0;
        for (int i = 0; i < logicalIndex; i++)
        {
            if (tabRegistry.get(i).visible) insertAt++;
        }

        int clampedAt = Math.min(insertAt, tabbedPane.getTabCount());
        tabbedPane.insertTab(entry.title, null, entry.content, null, clampedAt);
    }
}