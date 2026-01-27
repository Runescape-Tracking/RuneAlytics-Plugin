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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    private final JPanel bossListPanel;
    private final JLabel totalCountLabel;
    private final JLabel totalValueLabel;

    // Filter buttons
    private JButton eyeButton;
    private JButton sortButton;
    private JButton clearButton;

    private boolean showIgnoredItems = false;
    private String lastKilledBoss = null;
    private SortMode currentSort = SortMode.VALUE;
    // Track expanded/collapsed state per boss
    private final Map<String, Boolean> bossExpandedState = new HashMap<>();

    @Getter
    private enum SortMode
    {
        VALUE("Sort by Value"),
        KILLS("Sort by Kills"),
        RECENT("Sort by Recent");

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
            ItemManager itemManager
    )
    {
        super(false);

        this.lootManager = lootManager;
        this.runeAlyticsState = runeAlyticsState;
        this.itemManager = itemManager;

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
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setOpaque(false);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        eyeButton = createFilterButton("👁");
        eyeButton.setToolTipText(showIgnoredItems ? "Hide ignored items" : "Show ignored items");
        eyeButton.addActionListener(e -> toggleIgnoredItems());

        sortButton = createFilterButton("⇅");
        sortButton.setToolTipText(currentSort.getTooltip());
        sortButton.addActionListener(e -> cycleSortMode());

        clearButton = createFilterButton("🗑");
        clearButton.setToolTipText("Clear all data");
        clearButton.addActionListener(e -> confirmClearAll());

        panel.add(eyeButton);
        panel.add(sortButton);
        panel.add(clearButton);

        return panel;
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
                else if (button == sortButton && currentSort != SortMode.VALUE)
                {
                    bg = new Color(70, 70, 100);
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
        sortButton.setBackground(currentSort == SortMode.VALUE ? ColorScheme.DARK_GRAY_COLOR : new Color(70, 70, 100));
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

    private void highlightBoss(String npcName)
    {
        for (Component comp : bossListPanel.getComponents())
        {
            if (comp instanceof JPanel)
            {
                JPanel panel = (JPanel) comp;
                // Add green border
                panel.setBorder(BorderFactory.createLineBorder(Color.GREEN, 2));

                // Move to top
                bossListPanel.remove(panel);
                bossListPanel.add(panel, 0);
                bossListPanel.revalidate();
                bossListPanel.repaint();

                // Schedule border removal after 5 minutes
                Timer timer = new Timer(5 * 60 * 1000, e -> {
                    panel.setBorder(BorderFactory.createEmptyBorder());
                    panel.repaint();
                });
                timer.setRepeats(false);
                timer.start();

                break;
            }
        }
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
        bossListPanel.removeAll();

        List<BossKillStats> allStats = lootManager.getAllBossStats();

        if (allStats.isEmpty())
        {
            bossListPanel.add(createEmptyStatePanel());
        }
        else
        {
            sortBossStats(allStats);

            long totalValue = 0;
            int totalKills = 0;

            for (BossKillStats stats : allStats)
            {
                totalValue += stats.getTotalLootValue();
                totalKills += stats.getKillCount();

                JPanel bossPanel = createBossPanel(stats);
                bossPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                bossPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, bossPanel.getPreferredSize().height));
                bossListPanel.add(bossPanel);
                bossListPanel.add(RuneAlyticsUi.vSpace(8));
            }

            totalCountLabel.setText(String.valueOf(totalKills));
            totalValueLabel.setText(formatGp(totalValue));
        }

        bossListPanel.revalidate();
        bossListPanel.repaint();
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
        switch (currentSort)
        {
            case VALUE : stats.sort((a, b) -> {
                if (lastKilledBoss != null)
                {
                    if (a.getNpcName().equals(lastKilledBoss) && !b.getNpcName().equals(lastKilledBoss))
                        return -1;
                    if (b.getNpcName().equals(lastKilledBoss) && !a.getNpcName().equals(lastKilledBoss))
                        return 1;
                }
                return Long.compare(b.getTotalLootValue(), a.getTotalLootValue());
            });
            case KILLS : stats.sort((a, b) -> {
                if (lastKilledBoss != null)
                {
                    if (a.getNpcName().equals(lastKilledBoss) && !b.getNpcName().equals(lastKilledBoss))
                        return -1;
                    if (b.getNpcName().equals(lastKilledBoss) && !a.getNpcName().equals(lastKilledBoss))
                        return 1;
                }
                return Integer.compare(b.getKillCount(), a.getKillCount());
            });
            case RECENT : stats.sort((a, b) -> {
                if (lastKilledBoss != null)
                {
                    if (a.getNpcName().equals(lastKilledBoss) && !b.getNpcName().equals(lastKilledBoss))
                        return -1;
                    if (b.getNpcName().equals(lastKilledBoss) && !a.getNpcName().equals(lastKilledBoss))
                        return 1;
                }
                return Long.compare(b.getLastKillTimestamp(), a.getLastKillTimestamp());
            });
        }
    }

    private JPanel createBossPanel(BossKillStats stats)
    {
        boolean isRecentKill = lastKilledBoss != null && lastKilledBoss.equals(stats.getNpcName());

        // Main panel
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Thin green border on ALL sides
        panel.setBorder(BorderFactory.createCompoundBorder(
                new EmptyBorder(0, 5, 0, 5),
                BorderFactory.createLineBorder(
                        isRecentKill ? new Color(0, 200, 0) : new Color(60, 60, 60),
                        1
                )
        ));

        // Compact header
        JPanel headerPanel = new JPanel(new BorderLayout(5, 0));
        headerPanel.setBackground(isRecentKill ? new Color(30, 35, 30) : ColorScheme.DARKER_GRAY_COLOR);
        headerPanel.setBorder(new EmptyBorder(4, 6, 4, 6));
        headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
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

        // Holder for loot grid so inner class can access it
        final JPanel[] lootGridHolder = new JPanel[1];
        final boolean[] isExpanded = {true};

        headerPanel.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                // Single click toggles loot grid
                if (lootGridHolder[0] != null)
                {
                    isExpanded[0] = !isExpanded[0];
                    lootGridHolder[0].setVisible(isExpanded[0]);
                    panel.revalidate();
                    panel.repaint();
                }
            }

            @Override
            public void mousePressed(MouseEvent e)
            {
                if (e.isPopupTrigger())
                {
                    popupMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                if (e.isPopupTrigger())
                {
                    popupMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        panel.add(headerPanel);

        // Drops
        List<BossKillStats.AggregatedDrop> drops = stats.getAggregatedDropsSorted();

        if (!showIgnoredItems)
        {
            drops = drops.stream()
                    .filter(drop -> !lootManager.isDropHidden(stats.getNpcName(), drop.getItemId()))
                    .collect(java.util.stream.Collectors.toList());
        }

        if (!drops.isEmpty())
        {
            lootGridHolder[0] = createAggregatedLootGridFlexible(drops, stats.getNpcName());
            lootGridHolder[0].setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(lootGridHolder[0]);
            panel.add(Box.createVerticalStrut(5)); // Small bottom padding
        }
        else
        {
            JLabel noDropsLabel = new JLabel("All drops hidden");
            noDropsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            noDropsLabel.setFont(FontManager.getRunescapeSmallFont());
            noDropsLabel.setHorizontalAlignment(SwingConstants.CENTER);
            noDropsLabel.setBorder(new EmptyBorder(8, 8, 8, 8));
            noDropsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(noDropsLabel);
        }

        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));

        return panel;
    }

    private JPanel createAggregatedLootGridFlexible(List<BossKillStats.AggregatedDrop> drops, String npcName)
    {
        JPanel grid = new JPanel(new FixedColumnsWrapLayout(5, ITEM_SPACING, ITEM_SPACING));
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


    /**
     * Truncate text and add ellipsis if too long
     */
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

    /**
     * Format large numbers with K/M/B suffix
     */
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

        // 🔒 HARD WIDTH CAP (this is the magic)
        int maxColumns = ITEMS_PER_ROW;
        int maxWidth =
                (maxColumns * ITEM_SIZE)
                        + ((maxColumns - 1) * ITEM_SPACING)
                        + 10; // border padding

        Dimension preferred = grid.getPreferredSize();
        grid.setPreferredSize(new Dimension(maxWidth, preferred.height));
        grid.setMaximumSize(new Dimension(maxWidth, preferred.height));

        return grid;
    }



    private JPanel createAggregatedItemPanel(BossKillStats.AggregatedDrop drop, String npcName)
    {
        JLayeredPane layeredPane = new JLayeredPane();
        layeredPane.setPreferredSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
        layeredPane.setMinimumSize(new Dimension(ITEM_SIZE, ITEM_SIZE)); // ← Add this
        layeredPane.setMaximumSize(new Dimension(ITEM_SIZE, ITEM_SIZE)); // ← Add this
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
        wrapper.setMinimumSize(new Dimension(ITEM_SIZE, ITEM_SIZE)); // ← Add this
        wrapper.setMaximumSize(new Dimension(ITEM_SIZE, ITEM_SIZE)); // ← Add this
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