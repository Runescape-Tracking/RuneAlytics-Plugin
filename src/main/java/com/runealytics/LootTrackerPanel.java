package com.runealytics;

import com.runealytics.RuneAlyticsPanelBase;
import com.runealytics.RuneAlyticsState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Loot tracker panel with filters and ignore functionality
 */
@Singleton
public class LootTrackerPanel extends RuneAlyticsPanelBase implements LootTrackerUpdateListener
{
    private static final int ITEMS_PER_ROW = 5;
    private static final int ITEM_SIZE = 36;
    private static final int ITEM_SPACING = 4;

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

    @Inject
    public LootTrackerPanel(
            LootTrackerManager lootManager,
            RuneAlyticsState runeAlyticsState,
            ItemManager itemManager
    )
    {
        this.lootManager = lootManager;
        this.runeAlyticsState = runeAlyticsState;
        this.itemManager = itemManager;

        lootManager.addListener(this);

        // Initialize with proper styling
        totalCountLabel = new JLabel("0");
        totalCountLabel.setForeground(Color.WHITE);
        totalCountLabel.setFont(new Font("Dialog", Font.BOLD, 14));

        totalValueLabel = new JLabel("0 gp");
        totalValueLabel.setForeground(new Color(255, 215, 0));
        totalValueLabel.setFont(new Font("Dialog", Font.BOLD, 14));

        bossListPanel = new JPanel();
        bossListPanel.setLayout(new BoxLayout(bossListPanel, BoxLayout.Y_AXIS));
        bossListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        bossListPanel.setOpaque(true);

        buildUi();
        refreshDisplay();
    }

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Top panel with backpack icon and filters
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        topPanel.setBorder(new EmptyBorder(8, 8, 5, 8));

        // Backpack header with totals
        JPanel backpackPanel = createBackpackHeader();
        backpackPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(backpackPanel);

        topPanel.add(Box.createVerticalStrut(5));

        // Filter buttons
        JPanel filterPanel = createFilterPanel();
        filterPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(filterPanel);

        // Scroll pane for boss list
        JScrollPane scrollPane = new JScrollPane(bossListPanel);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Create backpack header with icon and totals
     */
    private JPanel createBackpackHeader()
    {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(200, 50));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        // Backpack icon
        JLabel backpackIcon = new JLabel("🎒");
        backpackIcon.setFont(new Font("Dialog", Font.PLAIN, 28));
        backpackIcon.setPreferredSize(new Dimension(40, 50));
        backpackIcon.setHorizontalAlignment(SwingConstants.CENTER);
        backpackIcon.setVerticalAlignment(SwingConstants.CENTER);

        // Total stats panel
        JPanel statsPanel = new JPanel();
        statsPanel.setLayout(new GridLayout(2, 2, 5, 2));
        statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statsPanel.setOpaque(false);

        // Total count
        JLabel countHeader = new JLabel("Total count:");
        countHeader.setForeground(Color.LIGHT_GRAY);
        countHeader.setFont(new Font("Dialog", Font.PLAIN, 11));

        // Total value
        JLabel valueHeader = new JLabel("Total value:");
        valueHeader.setForeground(Color.LIGHT_GRAY);
        valueHeader.setFont(new Font("Dialog", Font.PLAIN, 11));

        statsPanel.add(countHeader);
        statsPanel.add(totalCountLabel);
        statsPanel.add(valueHeader);
        statsPanel.add(totalValueLabel);

        panel.add(backpackIcon, BorderLayout.WEST);
        panel.add(statsPanel, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Create filter button panel
     */
    private JPanel createFilterPanel()
    {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setOpaque(false);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        // Eye button (toggle ignored items)
        eyeButton = createFilterButton("👁");
        eyeButton.setToolTipText(showIgnoredItems ? "Hide ignored items" : "Show ignored items");
        eyeButton.addActionListener(e -> toggleIgnoredItems());

        // Sort button
        sortButton = createFilterButton("⇅");
        sortButton.setToolTipText("Sort by value");

        // Clear all button
        clearButton = createFilterButton("🗑");
        clearButton.setToolTipText("Clear all data");
        clearButton.addActionListener(e -> confirmClearAll());

        panel.add(eyeButton);
        panel.add(sortButton);
        panel.add(clearButton);

        return panel;
    }

    /**
     * Create styled filter button
     */
    private JButton createFilterButton(String text)
    {
        JButton button = new JButton(text);
        button.setPreferredSize(new Dimension(32, 24));
        button.setBackground(ColorScheme.DARK_GRAY_COLOR);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
        button.setFont(new Font("Dialog", Font.PLAIN, 14));
        return button;
    }

    /**
     * Toggle showing/hiding ignored items
     */
    private void toggleIgnoredItems()
    {
        showIgnoredItems = !showIgnoredItems;
        eyeButton.setToolTipText(showIgnoredItems ? "Hide ignored items" : "Show ignored items");
        eyeButton.setBackground(showIgnoredItems ? ColorScheme.BRAND_ORANGE : ColorScheme.DARK_GRAY_COLOR);
        refreshDisplay();
    }

    /**
     * Confirm clear all data
     */
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

    @Override
    public void onDataRefresh()
    {
        SwingUtilities.invokeLater(this::refreshDisplay);
    }

    private void refreshDisplay()
    {
        bossListPanel.removeAll();

        List<BossKillStats> allStats = lootManager.getAllBossStats();

        // Sort: Most recently killed first, then by total value
        allStats.sort((a, b) -> {
            if (lastKilledBoss != null)
            {
                if (a.getNpcName().equals(lastKilledBoss) && !b.getNpcName().equals(lastKilledBoss))
                {
                    return -1;
                }
                if (b.getNpcName().equals(lastKilledBoss) && !a.getNpcName().equals(lastKilledBoss))
                {
                    return 1;
                }
            }
            return Long.compare(b.getTotalLootValue(), a.getTotalLootValue());
        });

        long totalValue = 0;
        int totalKills = 0;

        for (BossKillStats stats : allStats)
        {
            totalValue += stats.getTotalLootValue();
            totalKills += stats.getKillCount();

            JPanel bossPanel = createBossPanel(stats);
            bossPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            bossListPanel.add(bossPanel);

            // Add separator between bosses
            bossListPanel.add(Box.createVerticalStrut(12));
        }

        // Update header totals
        totalCountLabel.setText(String.valueOf(totalKills));
        totalValueLabel.setText(formatGp(totalValue));

        bossListPanel.revalidate();
        bossListPanel.repaint();
    }

    private JPanel createBossPanel(BossKillStats stats)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 60), 1),
                new EmptyBorder(0, 0, 0, 0)
        ));
        // Don't set maxHeight - let it size naturally
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Boss header bar
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(28, 28, 28));
        headerPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
        headerPanel.setOpaque(true);
        headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        String prestigeText = stats.getCurrentPrestige() > 0
                ? " [P" + stats.getCurrentPrestige() + "]"
                : "";
        JLabel nameLabel = new JLabel("▼ " + stats.getNpcName() + " × " + stats.getKillCount() + prestigeText);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(new Font("Dialog", Font.BOLD, 13));

        JLabel valueLabel = new JLabel(formatGp(stats.getTotalLootValue()));
        valueLabel.setForeground(new Color(255, 215, 0));
        valueLabel.setFont(new Font("Dialog", Font.PLAIN, 13));

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

        headerPanel.addMouseListener(new MouseAdapter()
        {
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
        panel.add(Box.createVerticalStrut(4));

        // Get drops (filter if needed)
        List<BossKillStats.AggregatedDrop> drops = stats.getAggregatedDropsSorted();

        // Filter ignored items if eye is off
        if (!showIgnoredItems)
        {
            drops = drops.stream()
                    .filter(drop -> !lootManager.isDropHidden(stats.getNpcName(), drop.getItemId()))
                    .collect(java.util.stream.Collectors.toList());
        }

        if (!drops.isEmpty())
        {
            JPanel lootGrid = createAggregatedLootGrid(drops, stats.getNpcName());
            lootGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(lootGrid);
            panel.add(Box.createVerticalStrut(4));
        }

        return panel;
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
                        stats.getCurrentPrestige() + 1
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
        int visibleDrops = drops.size();
        int rows = (int) Math.ceil((double) visibleDrops / ITEMS_PER_ROW);

        // Calculate exact height needed
        int gridHeight = (rows * ITEM_SIZE) + ((rows - 1) * ITEM_SPACING);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        wrapper.setOpaque(false);
        wrapper.setBorder(new EmptyBorder(0, 8, 0, 8));
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, gridHeight));

        JPanel grid = new JPanel(new GridLayout(rows, ITEMS_PER_ROW, ITEM_SPACING, ITEM_SPACING));
        grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        grid.setOpaque(false);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, gridHeight));

        for (BossKillStats.AggregatedDrop drop : drops)
        {
            JPanel itemPanel = createAggregatedItemPanel(drop, npcName);
            grid.add(itemPanel);
        }

        // Fill remaining slots
        int remainder = drops.size() % ITEMS_PER_ROW;
        if (remainder != 0)
        {
            int fillCount = ITEMS_PER_ROW - remainder;
            for (int i = 0; i < fillCount; i++)
            {
                JPanel filler = new JPanel();
                filler.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                filler.setPreferredSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
                filler.setOpaque(false);
                grid.add(filler);
            }
        }

        wrapper.add(grid, BorderLayout.WEST);
        return wrapper;
    }

    /**
     * Create item panel with right-click ignore option
     */
    private JPanel createAggregatedItemPanel(BossKillStats.AggregatedDrop drop, String npcName)
    {
        JLayeredPane layeredPane = new JLayeredPane();
        layeredPane.setPreferredSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
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

        // Right-click menu for ignore
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
        wrapper.setOpaque(false);
        wrapper.add(layeredPane, BorderLayout.CENTER);

        return wrapper;
    }

    private static Color getValueColor(long value)
    {
        if (value >= 10_000_000) return new Color(255, 170, 0);
        if (value >= 1_000_000)  return new Color(200, 100, 255);
        if (value >= 100_000)    return new Color(100, 200, 255);
        if (value >= 1_000)      return new Color(100, 255, 100);
        return new Color(255, 215, 0);
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