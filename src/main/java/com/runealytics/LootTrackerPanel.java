package com.runealytics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loot tracker panel with filters, ignore functionality, and collapse/expand per boss
 */
@Slf4j
@Singleton
public class LootTrackerPanel extends PluginPanel implements LootTrackerUpdateListener
{
    private static final int ITEMS_PER_ROW = 5;
    private static final int ITEM_SIZE = 40;
    private static final int ITEM_SPACING = 2;

    private final LootTrackerManager lootManager;
    private final RuneAlyticsState runeAlyticsState;
    private final ItemManager itemManager;
    private final RuneAlyticsPlugin plugin;

    private final JPanel bossListPanel;
    private final JLabel totalCountLabel;
    private final JLabel totalValueLabel;
    private String highlightedBoss = null;
    private Timer highlightTimeoutTimer;

    // Refresh guard
    private volatile boolean isRefreshing = false;

    // Filter buttons
    private JButton eyeButton;
    private JButton sortButton;
    private JButton clearButton;
    private JButton syncButton;

    private Timer highlightTimer;
    private Timer fadeTimer;
    private static final int HIGHLIGHT_TIMEOUT_MS = 10_000;
    private static final int SYNC_COOLDOWN_MS = 5 * 60 * 1000; // 5 minutes
    private long lastSyncTime = 0;

    private boolean showIgnoredItems = false;
    private String lastKilledBoss = null;
    private SortMode currentSort = SortMode.RECENT; // Default to most recent kills
    // Track expanded/collapsed state per boss
    private final Map<String, Boolean> bossExpandedState = new HashMap<>();

    @Getter
    private enum SortMode
    {
        VALUE("Sort: By Value"),
        KILLS("Sort: By Kills"),
        RECENT("Sort: By Recent");

        private final String tooltip;

        SortMode(String tooltip)
        {
            this.tooltip = tooltip;
        }

        public SortMode next()
        {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    @Inject
    public LootTrackerPanel(
            LootTrackerManager lootManager,
            RuneAlyticsState runeAlyticsState,
            ItemManager itemManager,
            RuneAlyticsPlugin plugin
    )
    {
        super(false);

        this.lootManager = lootManager;
        this.runeAlyticsState = runeAlyticsState;
        this.itemManager = itemManager;
        this.plugin = plugin;

        log.info("LootTrackerPanel: Initializing");

        lootManager.addListener(this);

        totalCountLabel = new JLabel("0");
        totalCountLabel.setForeground(Color.WHITE);
        totalCountLabel.setFont(FontManager.getRunescapeSmallFont());

        totalValueLabel = new JLabel("0 gp");
        totalValueLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        totalValueLabel.setFont(FontManager.getRunescapeSmallFont());

        bossListPanel = new JPanel();
        bossListPanel.setLayout(new BoxLayout(bossListPanel, BoxLayout.Y_AXIS));
        bossListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        buildUi();

        SwingUtilities.invokeLater(this::refreshDisplay);
    }

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        topPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel headerPanel = createBackpackHeader();
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(headerPanel);
        topPanel.add(Box.createVerticalStrut(10));

        JPanel filterPanel = createFilterPanel();
        filterPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(filterPanel);
        topPanel.add(Box.createVerticalStrut(10));

        JScrollPane scrollPane = new JScrollPane(bossListPanel);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    private JPanel createBackpackHeader()
    {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setOpaque(false);

        JLabel backpackIcon = new JLabel("💰");
        backpackIcon.setFont(new Font("Dialog", Font.PLAIN, 24));
        backpackIcon.setPreferredSize(new Dimension(32, 32));
        backpackIcon.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel statsPanel = new JPanel(new GridLayout(2, 2, 5, 2));
        statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statsPanel.setOpaque(false);

        JLabel countLabel = new JLabel("Total kills:");
        countLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        countLabel.setFont(FontManager.getRunescapeSmallFont());

        JLabel valueLabel = new JLabel("Total value:");
        valueLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        valueLabel.setFont(FontManager.getRunescapeSmallFont());

        statsPanel.add(countLabel);
        statsPanel.add(totalCountLabel);
        statsPanel.add(valueLabel);
        statsPanel.add(totalValueLabel);

        panel.add(backpackIcon, BorderLayout.WEST);
        panel.add(statsPanel, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createFilterPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setOpaque(false);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        // Left side - filter buttons
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftButtons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        leftButtons.setOpaque(false);

        eyeButton = createFilterButton("👁");
        eyeButton.setToolTipText(showIgnoredItems ? "Hide ignored items" : "Show ignored items");
        eyeButton.addActionListener(e -> toggleIgnoredItems());

        sortButton = createFilterButton("⇅");
        sortButton.setToolTipText(currentSort.getTooltip());
        // Set initial color for RECENT mode (default)
        sortButton.setBackground(new Color(70, 100, 70));
        sortButton.addActionListener(e -> cycleSortMode());

        clearButton = createFilterButton("🗑");
        clearButton.setToolTipText("Clear all data");
        clearButton.addActionListener(e -> confirmClearAll());

        leftButtons.add(eyeButton);
        leftButtons.add(sortButton);
        leftButtons.add(clearButton);

        // Right side - sync button
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightButtons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        rightButtons.setOpaque(false);

        syncButton = new JButton("Sync");
        syncButton.setPreferredSize(new Dimension(60, 24));
        syncButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
        syncButton.setForeground(Color.WHITE);
        syncButton.setFocusPainted(false);
        syncButton.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
        syncButton.setFont(FontManager.getRunescapeSmallFont());
        syncButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        syncButton.setToolTipText("Sync loot data with server");
        syncButton.addActionListener(e -> onSyncButtonClicked());

        syncButton.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseEntered(MouseEvent e)
            {
                if (syncButton.isEnabled())
                {
                    syncButton.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
                }
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                syncButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
            }
        });

        rightButtons.add(syncButton);

        panel.add(leftButtons, BorderLayout.WEST);
        panel.add(rightButtons, BorderLayout.EAST);

        return panel;
    }

    private void onSyncButtonClicked()
    {
        // Check cooldown
        long now = System.currentTimeMillis();
        long timeSinceLastSync = now - lastSyncTime;

        if (timeSinceLastSync < SYNC_COOLDOWN_MS)
        {
            long remainingSeconds = (SYNC_COOLDOWN_MS - timeSinceLastSync) / 1000;
            long minutes = remainingSeconds / 60;
            long seconds = remainingSeconds % 60;

            JOptionPane.showMessageDialog(
                    this,
                    String.format("Please wait %d:%02d before syncing again", minutes, seconds),
                    "Sync Cooldown",
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        // Get current username from plugin
        String username = runeAlyticsState.getVerifiedUsername();

        if (username == null || username.isEmpty())
        {
            JOptionPane.showMessageDialog(
                    this,
                    "Please log in to sync data",
                    "Sync Failed",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        // Check if verified
        if (!runeAlyticsState.isVerified())
        {
            JOptionPane.showMessageDialog(
                    this,
                    "Account not verified. Please verify your account first.",
                    "Sync Failed",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        // Update last sync time
        lastSyncTime = now;

        // Disable button and show syncing state
        syncButton.setEnabled(false);
        syncButton.setText("...");

        // Trigger sync
        lootManager.performManualSync(username);
    }

    public void showSyncCompleted()
    {
        syncButton.setEnabled(true);
        syncButton.setText("Sync");

        JOptionPane.showMessageDialog(
                this,
                "Loot data synced successfully!",
                "Sync Complete",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    public void showSyncFailed(String error)
    {
        syncButton.setEnabled(true);
        syncButton.setText("Sync");

        JOptionPane.showMessageDialog(
                this,
                "Sync failed: " + error,
                "Sync Failed",
                JOptionPane.ERROR_MESSAGE
        );
    }

    private JButton createFilterButton(String text)
    {
        JButton button = new JButton(text);
        button.setPreferredSize(new Dimension(32, 24));
        button.setBackground(ColorScheme.DARK_GRAY_COLOR);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
        button.setFont(new Font("Dialog", Font.PLAIN, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        button.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseEntered(MouseEvent e)
            {
                if (button.isEnabled())
                {
                    button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
                }
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                Color bg = ColorScheme.DARK_GRAY_COLOR;
                if (button == eyeButton && showIgnoredItems)
                {
                    bg = ColorScheme.BRAND_ORANGE;
                }
                else if (button == sortButton)
                {
                    // Restore sort mode color
                    if (currentSort == SortMode.VALUE)
                    {
                        bg = ColorScheme.DARK_GRAY_COLOR;
                    }
                    else if (currentSort == SortMode.KILLS)
                    {
                        bg = new Color(70, 70, 100);
                    }
                    else // RECENT
                    {
                        bg = new Color(70, 100, 70);
                    }
                }
                button.setBackground(bg);
            }
        });

        return button;
    }

    private void toggleIgnoredItems()
    {
        showIgnoredItems = !showIgnoredItems;
        eyeButton.setToolTipText(showIgnoredItems ? "Hide ignored items" : "Show ignored items");
        eyeButton.setBackground(showIgnoredItems ? ColorScheme.BRAND_ORANGE : ColorScheme.DARK_GRAY_COLOR);
        refreshDisplay();
    }

    private void cycleSortMode()
    {
        currentSort = currentSort.next();
        sortButton.setToolTipText(currentSort.getTooltip());

        // Update button color based on mode
        Color buttonColor;
        if (currentSort == SortMode.VALUE)
        {
            buttonColor = ColorScheme.DARK_GRAY_COLOR;
        }
        else if (currentSort == SortMode.KILLS)
        {
            buttonColor = new Color(70, 70, 100);
        }
        else // RECENT
        {
            buttonColor = new Color(70, 100, 70);
        }
        sortButton.setBackground(buttonColor);

        log.info("Sort mode changed to: {}", currentSort);
        refreshDisplay();
    }

    private void confirmClearAll()
    {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Clear ALL loot tracking data?\n\n" +
                        "This will delete all boss kills and drops.\n" +
                        "This action cannot be undone!",
                "Confirm Clear All",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.ERROR_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION)
        {
            lootManager.clearAllData();
            refreshDisplay();
        }
    }

    @Override
    public void onKillRecorded(NpcKillRecord kill, BossKillStats stats)
    {
        lastKilledBoss = kill.getNpcName();
        SwingUtilities.invokeLater(this::refreshDisplay);
    }

    public void onKillAdded(BossKillStats stats)
    {
        SwingUtilities.invokeLater(() -> {
            refreshDisplay();
            highlightBoss(stats.getNpcName());
        });
    }

    /**
     * PUBLIC: Highlight a boss panel (called from LootTrackerManager)
     */
    public void highlightBoss(String npcName)
    {
        // Track highlighted boss by name (NOT panel)
        highlightedBoss = npcName;

        // Cancel previous timer
        if (highlightTimer != null)
        {
            highlightTimer.stop();
        }

        // After timeout, clear highlight and refresh UI
        highlightTimer = new Timer(HIGHLIGHT_TIMEOUT_MS, e -> {
            highlightedBoss = null;
            refreshDisplay();
        });
        highlightTimer.setRepeats(false);
        highlightTimer.start();

        // Move highlighted boss to top + redraw
        refreshDisplay();
    }

    private void startFadeOut(JPanel panel)
    {
        final int steps = 10;
        final int delay = 40;

        fadeTimer = new Timer(delay, null);

        fadeTimer.addActionListener(e -> {
            int alpha = ((Timer) e.getSource()).getDelay() * steps;
            float progress = (float) fadeTimer.getInitialDelay() / (steps * delay);

            int green = Math.max(60, (int) (200 * (1 - progress)));
            panel.setBorder(BorderFactory.createLineBorder(
                    new Color(0, green, 0),
                    1
            ));

            panel.repaint();

            if (progress >= 1f)
            {
                fadeTimer.stop();
                panel.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
            }
        });

        fadeTimer.setRepeats(true);
        fadeTimer.start();
    }

    @Override
    public void onLootUpdated()
    {
        SwingUtilities.invokeLater(this::refreshDisplay);
    }

    @Override
    public void onDataRefresh()
    {
        SwingUtilities.invokeLater(this::refreshDisplay);
    }

    public void refreshDisplay()
    {
        refreshDisplayPreservingLayout();
    }

    /**
     * Refresh display while preserving scroll position and expanded states
     */
    public void refreshDisplayPreservingLayout()
    {
        if (isRefreshing)
        {
            log.debug("Refresh already in progress - skipping");
            return;
        }

        isRefreshing = true;

        try
        {
            // Store current scroll position
            JScrollPane scrollPane = findScrollPane(this);
            int scrollPosition = 0;
            if (scrollPane != null)
            {
                scrollPosition = scrollPane.getVerticalScrollBar().getValue();
            }

            // Get existing boss panels to preserve expanded states
            Map<String, Boolean> currentExpandedStates = new HashMap<>(bossExpandedState);

            // Perform the refresh
            bossListPanel.removeAll();

            List<BossKillStats> allStats = lootManager.getAllBossStats();

            if (allStats.isEmpty())
            {
                bossListPanel.add(createEmptyStatePanel());
            }
            else
            {
                // Deduplicate by boss name
                Map<String, BossKillStats> uniqueBosses = new HashMap<>();
                for (BossKillStats stats : allStats)
                {
                    String bossName = stats.getNpcName();
                    if (uniqueBosses.containsKey(bossName))
                    {
                        log.warn("Duplicate boss detected in display: {} - using first occurrence", bossName);
                        continue;
                    }
                    uniqueBosses.put(bossName, stats);
                }

                List<BossKillStats> dedupedStats = new ArrayList<>(uniqueBosses.values());
                sortBossStats(dedupedStats);

                long totalValue = 0;
                int totalKills = 0;

                for (BossKillStats stats : dedupedStats)
                {
                    totalValue += stats.getTotalLootValue();
                    totalKills += stats.getKillCount();

                    // Restore expanded state from previous render
                    String bossName = stats.getNpcName();
                    if (currentExpandedStates.containsKey(bossName))
                    {
                        bossExpandedState.put(bossName, currentExpandedStates.get(bossName));
                    }

                    JPanel bossPanel = createBossPanel(stats);
                    bossPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                    bossListPanel.add(bossPanel);
                    bossListPanel.add(RuneAlyticsUi.vSpace(8));
                }

                totalCountLabel.setText(String.valueOf(totalKills));
                totalValueLabel.setText(formatGp(totalValue));
            }

            // Single revalidate/repaint instead of multiple
            bossListPanel.revalidate();
            bossListPanel.repaint();

            // Restore scroll position
            if (scrollPane != null)
            {
                final int finalScrollPosition = scrollPosition;
                SwingUtilities.invokeLater(() -> {
                    scrollPane.getVerticalScrollBar().setValue(finalScrollPosition);
                });
            }
        }
        finally
        {
            isRefreshing = false;
        }
    }

    /**
     * Find the parent scroll pane of a component
     */
    private JScrollPane findScrollPane(Component component)
    {
        Component parent = component.getParent();
        while (parent != null)
        {
            if (parent instanceof JScrollPane)
            {
                return (JScrollPane) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }


    private JPanel createEmptyStatePanel()
    {
        JPanel panel = RuneAlyticsUi.verticalPanel();
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(40, 20, 40, 20));
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel emptyIcon = new JLabel("💀");
        emptyIcon.setFont(new Font("Dialog", Font.PLAIN, 48));
        emptyIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyIcon.setHorizontalAlignment(SwingConstants.CENTER);

        JLabel emptyText = RuneAlyticsUi.titleLabel("No loot tracked yet");
        emptyText.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel emptySubtext = RuneAlyticsUi.subtitleLabel("Kill bosses to start tracking!");
        emptySubtext.setAlignmentX(Component.CENTER_ALIGNMENT);

        panel.add(emptyIcon);
        panel.add(RuneAlyticsUi.vSpace(10));
        panel.add(emptyText);
        panel.add(RuneAlyticsUi.vSpace(5));
        panel.add(emptySubtext);

        return panel;
    }

    private void sortBossStats(List<BossKillStats> stats)
    {
        log.debug("Sorting {} bosses by: {}", stats.size(), currentSort);

        switch (currentSort)
        {
            case VALUE:
                stats.sort((a, b) -> {
                    // PRIORITY: Highlighted boss always first
                    if (highlightedBoss != null)
                    {
                        if (a.getNpcName().equals(highlightedBoss) && !b.getNpcName().equals(highlightedBoss))
                            return -1;
                        if (b.getNpcName().equals(highlightedBoss) && !a.getNpcName().equals(highlightedBoss))
                            return 1;
                    }
                    return Long.compare(b.getTotalLootValue(), a.getTotalLootValue());
                });
                log.debug("Sorted by value - top boss: {} with {} gp",
                        stats.get(0).getNpcName(), stats.get(0).getTotalLootValue());
                break;

            case KILLS:
                stats.sort((a, b) -> {
                    if (highlightedBoss != null)
                    {
                        if (a.getNpcName().equals(highlightedBoss) && !b.getNpcName().equals(highlightedBoss))
                            return -1;
                        if (b.getNpcName().equals(highlightedBoss) && !a.getNpcName().equals(highlightedBoss))
                            return 1;
                    }
                    return Integer.compare(b.getKillCount(), a.getKillCount());
                });
                log.debug("Sorted by kills - top boss: {} with {} KC",
                        stats.get(0).getNpcName(), stats.get(0).getKillCount());
                break;

            case RECENT:
                stats.sort((a, b) -> {
                    if (highlightedBoss != null)
                    {
                        if (a.getNpcName().equals(highlightedBoss) && !b.getNpcName().equals(highlightedBoss))
                            return -1;
                        if (b.getNpcName().equals(highlightedBoss) && !a.getNpcName().equals(highlightedBoss))
                            return 1;
                    }
                    // Most recent kill first (higher timestamp = more recent)
                    return Long.compare(b.getLastKillTimestamp(), a.getLastKillTimestamp());
                });
                log.debug("Sorted by recent - top boss: {} with last kill at {}",
                        stats.get(0).getNpcName(), stats.get(0).getLastKillTimestamp());
                break;
        }
    }

    private JPanel createBossPanel(BossKillStats stats)
    {
        boolean isHighlighted = stats.getNpcName().equals(highlightedBoss);

        // Main panel - NO SIZE CONSTRAINTS
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.setBorder(BorderFactory.createCompoundBorder(
                new EmptyBorder(0, 5, 0, 5),
                BorderFactory.createLineBorder(
                        isHighlighted ? new Color(0, 200, 0) : new Color(60, 60, 60),
                        isHighlighted ? 2 : 1
                )
        ));

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout(5, 0));
        headerPanel.setBackground(
                isHighlighted ? new Color(30, 35, 30) : ColorScheme.DARKER_GRAY_COLOR
        );
        headerPanel.setBorder(new EmptyBorder(4, 6, 4, 6));
        // Header can have fixed height
        headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        String bossName = truncateText(stats.getNpcName(), 18);
        JLabel nameLabel = new JLabel(bossName + " × " + formatNumber(stats.getKillCount()));
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setToolTipText(stats.getNpcName());

        JLabel valueLabel = new JLabel(formatGp(stats.getTotalLootValue()));
        valueLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        valueLabel.setFont(FontManager.getRunescapeSmallFont());

        headerPanel.add(nameLabel, BorderLayout.WEST);
        headerPanel.add(valueLabel, BorderLayout.EAST);

        // Right-click menu
        JPopupMenu popupMenu = new JPopupMenu();

        JMenuItem prestigeItem = new JMenuItem("Prestige (Reset Stats)");
        prestigeItem.addActionListener(e -> confirmPrestige(stats));
        popupMenu.add(prestigeItem);

        JMenuItem clearItem = new JMenuItem("Clear Data");
        clearItem.addActionListener(e -> confirmClear(stats));
        popupMenu.add(clearItem);

        headerPanel.setComponentPopupMenu(popupMenu);

        // Expansion state
        boolean expanded = bossExpandedState.getOrDefault(stats.getNpcName(), true);
        final boolean[] isExpanded = new boolean[] { expanded };

        // Loot container - NO SIZE CONSTRAINTS
        JPanel lootContainer = new JPanel(new BorderLayout());
        lootContainer.setOpaque(false);
        lootContainer.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Loot grid holder (per-NPC, no shared refs)
        final JPanel[] lootGridHolder = new JPanel[1];
        lootGridHolder[0] = createAggregatedLootGridFlexible(
                stats.getAggregatedDropsSorted(),
                stats.getNpcName()
        );
        lootGridHolder[0].setVisible(isExpanded[0]);

        lootContainer.add(lootGridHolder[0], BorderLayout.CENTER);

        // Header click toggles ONLY this NPC
        headerPanel.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                isExpanded[0] = !isExpanded[0];
                bossExpandedState.put(stats.getNpcName(), isExpanded[0]);

                // Toggle visibility ONLY
                lootGridHolder[0].setVisible(isExpanded[0]);

                // Force complete re-layout WITHOUT size constraints
                lootContainer.invalidate();
                panel.invalidate();
                bossListPanel.invalidate();

                SwingUtilities.getRoot(panel).validate();

                panel.revalidate();
                panel.repaint();
                bossListPanel.revalidate();
                bossListPanel.repaint();
            }
        });

        // Assemble
        panel.add(headerPanel);
        panel.add(lootContainer);

        return panel;
    }

    private JPanel createAggregatedLootGridFlexible(List<BossKillStats.AggregatedDrop> drops, String npcName)
    {
        JPanel grid = new JPanel(new FixedColumnsWrapLayout(5, ITEM_SPACING, ITEM_SPACING));
        grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        grid.setBorder(new EmptyBorder(5, 5, 5, 5));
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Don't constrain size - let it flow naturally
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        for (BossKillStats.AggregatedDrop drop : drops)
        {
            JPanel itemPanel = createAggregatedItemPanel(drop, npcName);
            grid.add(itemPanel);
        }

        return grid;
    }

    private String truncateText(String text, int maxLength)
    {
        if (text == null || text.length() <= maxLength)
        {
            return text;
        }

        // Try to break at word boundary
        int lastSpace = text.substring(0, maxLength - 1).lastIndexOf(' ');
        if (lastSpace > maxLength / 2)
        {
            return text.substring(0, lastSpace) + "...";
        }

        // Otherwise just truncate
        return text.substring(0, maxLength - 3) + "...";
    }

    private String formatNumber(int number)
    {
        if (number >= 1_000_000_000)
        {
            return String.format("%.1fB", number / 1_000_000_000.0);
        }
        else if (number >= 1_000_000)
        {
            return String.format("%.1fM", number / 1_000_000.0);
        }
        else if (number >= 1_000)
        {
            return String.format("%.1fK", number / 1_000.0);
        }
        else
        {
            return String.valueOf(number);
        }
    }

    private void confirmPrestige(BossKillStats stats)
    {
        int result = JOptionPane.showConfirmDialog(
                this,
                String.format(
                        "Prestige %s?\n\n" +
                                "Current Stats:\n" +
                                "• Kills: %d\n" +
                                "• Total Value: %s\n\n" +
                                "Stats will be reset but saved to server history.\n" +
                                "You will advance to Prestige %d.",
                        stats.getNpcName(),
                        stats.getKillCount(),
                        formatGp(stats.getTotalLootValue()),
                        stats.getPrestige() + 1
                ),
                "Confirm Prestige",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION)
        {
            lootManager.prestigeBoss(stats.getNpcName());
        }
    }

    private void confirmClear(BossKillStats stats)
    {
        int result = JOptionPane.showConfirmDialog(
                this,
                String.format(
                        "Clear all data for %s?\n\n" +
                                "This will delete:\n" +
                                "• %d kills\n" +
                                "• %s total loot\n" +
                                "• All prestige history\n\n" +
                                "This action cannot be undone!",
                        stats.getNpcName(),
                        stats.getKillCount(),
                        formatGp(stats.getTotalLootValue())
                ),
                "Confirm Clear Data",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.ERROR_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION)
        {
            lootManager.clearBossData(stats.getNpcName());
            refreshDisplay();
        }
    }

    private JPanel createAggregatedLootGrid(List<BossKillStats.AggregatedDrop> drops, String npcName)
    {
        JPanel grid = new JPanel(
                new WrapLayout(FlowLayout.LEFT, ITEM_SPACING, ITEM_SPACING)
        );

        grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        grid.setBorder(new EmptyBorder(5, 5, 5, 5));
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (BossKillStats.AggregatedDrop drop : drops)
        {
            JPanel itemPanel = createAggregatedItemPanel(drop, npcName);
            grid.add(itemPanel);
        }

        return grid;
    }

    private JPanel createAggregatedItemPanel(BossKillStats.AggregatedDrop drop, String npcName)
    {
        JLayeredPane layeredPane = new JLayeredPane();
        layeredPane.setPreferredSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
        layeredPane.setMinimumSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
        layeredPane.setMaximumSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
        layeredPane.setOpaque(true);

        boolean isIgnored = lootManager.isDropHidden(npcName, drop.getItemId());
        layeredPane.setBackground(isIgnored ? new Color(60, 40, 40) : new Color(40, 40, 40));
        layeredPane.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));

        long displayQty = drop.getTotalQuantity();
        AsyncBufferedImage itemImage = itemManager.getImage(drop.getItemId(), (int)displayQty, false);

        // Icon layer
        JLabel iconLabel = new JLabel();
        iconLabel.setBounds(0, 0, ITEM_SIZE, ITEM_SIZE);
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        iconLabel.setVerticalAlignment(SwingConstants.CENTER);
        iconLabel.setOpaque(false);

        if (itemImage != null)
        {
            itemImage.addTo(iconLabel);
        }

        layeredPane.add(iconLabel, JLayeredPane.DEFAULT_LAYER);

        // Quantity overlay
        if (displayQty > 1)
        {
            String qtyText = formatQuantity(displayQty);
            Color qtyColor = getValueColor(drop.getTotalValue());

            JLabel qtyLabel = new JLabel(qtyText);
            qtyLabel.setFont(new Font("Arial", Font.BOLD, 11));
            qtyLabel.setForeground(qtyColor);
            qtyLabel.setBounds(2, 1, ITEM_SIZE - 4, 14);
            qtyLabel.setHorizontalAlignment(SwingConstants.LEFT);
            qtyLabel.setVerticalAlignment(SwingConstants.TOP);
            qtyLabel.setOpaque(false);

            layeredPane.add(qtyLabel, JLayeredPane.PALETTE_LAYER);
        }

        // Right-click menu
        JPopupMenu itemMenu = new JPopupMenu();
        JMenuItem ignoreItem = new JMenuItem(isIgnored ? "Unignore" : "Ignore");
        ignoreItem.addActionListener(e -> {
            if (isIgnored)
            {
                lootManager.unhideDropForNpc(npcName, drop.getItemId());
            }
            else
            {
                lootManager.hideDropForNpc(npcName, drop.getItemId());
            }
            refreshDisplay();
        });
        itemMenu.add(ignoreItem);

        layeredPane.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                if (e.isPopupTrigger())
                {
                    itemMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                if (e.isPopupTrigger())
                {
                    itemMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        // Tooltip
        String tooltip = buildAggregatedItemTooltip(drop);
        layeredPane.setToolTipText(tooltip);
        iconLabel.setToolTipText(tooltip);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setPreferredSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
        wrapper.setMinimumSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
        wrapper.setMaximumSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
        wrapper.setOpaque(false);
        wrapper.add(layeredPane, BorderLayout.CENTER);

        return wrapper;
    }

    private static Color getValueColor(long value)
    {
        if (value >= 10_000_000) return new Color(255, 170, 0);  // Orange
        if (value >= 1_000_000)  return new Color(200, 100, 255); // Purple
        if (value >= 100_000)    return new Color(100, 200, 255); // Cyan
        if (value >= 1_000)      return new Color(100, 255, 100); // Green
        return new Color(255, 215, 0); // Gold
    }

    public void refresh()
    {
        SwingUtilities.invokeLater(this::refreshDisplay);
    }

    private String buildAggregatedItemTooltip(BossKillStats.AggregatedDrop drop)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("<html>");
        sb.append("<b>").append(drop.getItemName()).append("</b><br>");
        sb.append("<br>");
        sb.append("Times Dropped: <b>").append(drop.getDropCount()).append("</b><br>");
        sb.append("Total Quantity: ").append(QuantityFormatter.formatNumber(drop.getTotalQuantity())).append("<br>");
        sb.append("<br>");
        sb.append("GE Price: ").append(formatGp(drop.getGePrice())).append(" ea<br>");
        sb.append("Total Value: <b>").append(formatGp(drop.getTotalValue())).append("</b><br>");
        sb.append("High Alch: ").append(formatGp(drop.getHighAlchValue())).append(" ea");
        sb.append("</html>");
        return sb.toString();
    }

    private String formatGp(long value)
    {
        if (value >= 1_000_000_000) return String.format("%.2fB gp", value / 1_000_000_000.0);
        if (value >= 1_000_000) return String.format("%.2fM gp", value / 1_000_000.0);
        if (value >= 1_000) return String.format("%.1fK gp", value / 1_000.0);
        return value + " gp";
    }

    private String formatQuantity(long qty)
    {
        if (qty >= 1_000_000) return String.format("%.1fM", qty / 1_000_000.0);
        if (qty >= 1_000) return String.format("%.1fK", qty / 1_000.0);
        return String.valueOf(qty);
    }
}