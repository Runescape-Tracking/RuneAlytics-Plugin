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
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * Main loot tracker panel.
 *
 * <p>Layout:
 * <pre>
 *   ┌─────────────────────────────┐
 *   │  FIXED HEADER               │  ← stats + filter buttons, NEVER scrolls
 *   ├─────────────────────────────┤
 *   │  SCROLLABLE BOSS LIST       │  ← one card per boss, scrolls independently
 *   └─────────────────────────────┘
 * </pre>
 *
 * <h2>Mouse-wheel scrolling fix</h2>
 * Child components (item slots, labels, cards) consume {@link MouseWheelEvent}s
 * before the parent {@link JScrollPane} sees them. {@link #propagateWheelEvents}
 * recursively attaches a forwarding listener after each rebuild so scrolling
 * works anywhere inside the panel, not just over the scrollbar track.
 *
 * <h2>Scrollbar-coverage fix</h2>
 * A {@link ComponentListener} on the vertical scrollbar widens the right inset
 * of the boss-list panel by the scrollbar's pixel width whenever it becomes
 * visible, preventing item slots from sliding underneath it.
 */
@Slf4j
@Singleton
public class LootTrackerPanel extends PluginPanel implements LootTrackerUpdateListener
{
    // ── Item grid constants ──────────────────────────────────────────────────
    private static final int ITEMS_PER_ROW = 5;
    private static final int ITEM_SIZE     = 42;
    private static final int ITEM_GAP      = 3;

    /** Base padding applied to all four sides when no scrollbar is visible */
    private static final int PAD = 6;

    // ── Timing ───────────────────────────────────────────────────────────────
    private static final int  HIGHLIGHT_TIMEOUT_MS = 10_000;
    private static final long SYNC_COOLDOWN_MS     = 5 * 60 * 1_000L;

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final LootTrackerManager lootManager;
    private final RuneAlyticsState   runeAlyticsState;
    private final ItemManager        itemManager;
    private final RuneAlyticsPlugin  plugin;

    // ── Header widgets ───────────────────────────────────────────────────────
    private final JLabel totalKillsLabel = new JLabel("0 kills");
    private final JLabel totalValueLabel = new JLabel("0 gp");

    private JButton eyeButton;
    private JButton sortButton;
    private JButton clearButton;
    private JButton syncButton;

    // ── Boss-list panel (scrollable) ──────────────────────────────────────────
    private final JPanel bossListPanel = new JPanel();

    /**
     * Stored as a field so mouse-wheel propagation and padding adjustments can
     * reference it directly without traversing the component tree every call.
     */
    private JScrollPane scrollPane;

    // ── State ─────────────────────────────────────────────────────────────────
    private String highlightedBoss = null;
    private javax.swing.Timer highlightTimer;

    private boolean  showIgnoredItems = false;
    private SortMode currentSort      = SortMode.RECENT;

    private final Map<String, Boolean> bossExpandedState = new HashMap<>();
    private volatile boolean isRefreshing = false;
    private long lastSyncTime = 0L;

    // ── Sort modes ────────────────────────────────────────────────────────────
    @Getter
    private enum SortMode
    {
        RECENT("Sort: Most Recent"),
        VALUE ("Sort: Highest Value"),
        KILLS ("Sort: Most Kills");

        private final String label;
        SortMode(String label) { this.label = label; }

        public SortMode next()
        {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────────
    @Inject
    public LootTrackerPanel(
            LootTrackerManager lootManager,
            RuneAlyticsState   runeAlyticsState,
            ItemManager        itemManager,
            RuneAlyticsPlugin  plugin
    )
    {
        super(false); // false = do NOT let PluginPanel add its own outer scroll pane
        this.lootManager      = lootManager;
        this.runeAlyticsState = runeAlyticsState;
        this.itemManager      = itemManager;
        this.plugin           = plugin;

        lootManager.addListener(this);
        buildUi();
        SwingUtilities.invokeLater(this::refreshDisplay);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI Construction
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Builds the two-section layout.
     *
     * <p>The header goes into {@link BorderLayout#NORTH}: it is completely
     * outside the scroll pane and never scrolls.
     *
     * <p>The {@link JScrollPane} fills {@link BorderLayout#CENTER}: it expands
     * to use all remaining vertical space and scrolls only the boss card list.
     */
    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // NORTH: fixed header (never scrolls)
        add(buildHeader(), BorderLayout.NORTH);

        // CENTER: scrollable boss list
        bossListPanel.setLayout(new BoxLayout(bossListPanel, BoxLayout.Y_AXIS));
        bossListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        bossListPanel.setBorder(new EmptyBorder(PAD, PAD, PAD, PAD));

        scrollPane = new JScrollPane(bossListPanel);
        scrollPane.setBorder(null);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        // Scrollbar-coverage fix: widen right inset when scrollbar appears
        scrollPane.getVerticalScrollBar().addComponentListener(new ComponentAdapter()
        {
            @Override public void componentShown (ComponentEvent e) { setScrollPadding(true);  }
            @Override public void componentHidden(ComponentEvent e) { setScrollPadding(false); }
        });

        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Adjusts the right inset of {@link #bossListPanel} to prevent item slots
     * from being hidden under the vertical scrollbar.
     *
     * @param scrollbarVisible {@code true} when the scrollbar just became visible
     */
    private void setScrollPadding(boolean scrollbarVisible)
    {
        int rightPad = PAD;
        if (scrollbarVisible)
        {
            int sbWidth = scrollPane.getVerticalScrollBar().getWidth();
            if (sbWidth <= 0)
            {
                sbWidth = UIManager.getInt("ScrollBar.width");
                if (sbWidth <= 0) sbWidth = 13; // safe default
            }
            rightPad = PAD + sbWidth;
        }
        bossListPanel.setBorder(new EmptyBorder(PAD, PAD, PAD, rightPad));
        bossListPanel.revalidate();
        bossListPanel.repaint();
    }

    /**
     * Builds the fixed header (stats + button row).
     * {@code setMinimumSize} prevents parent containers from squashing it.
     */
    private JPanel buildHeader()
    {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        header.setBorder(new EmptyBorder(8, 8, 8, 8));
        header.setMinimumSize(new Dimension(0, 72)); // never compress below this

        // Stats row
        JPanel statsRow = new JPanel(new BorderLayout(8, 0));
        statsRow.setOpaque(false);
        statsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        statsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        JLabel iconLabel = new JLabel("💰");
        iconLabel.setFont(new Font("Dialog", Font.PLAIN, 22));

        JPanel statsText = new JPanel(new GridLayout(2, 1, 0, 1));
        statsText.setOpaque(false);

        totalKillsLabel.setForeground(Color.WHITE);
        totalKillsLabel.setFont(FontManager.getRunescapeSmallFont());

        totalValueLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        totalValueLabel.setFont(FontManager.getRunescapeSmallFont());

        statsText.add(totalKillsLabel);
        statsText.add(totalValueLabel);

        statsRow.add(iconLabel,  BorderLayout.WEST);
        statsRow.add(statsText,  BorderLayout.CENTER);

        header.add(statsRow);
        header.add(Box.createVerticalStrut(6));

        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(60, 60, 60));
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(sep);
        header.add(Box.createVerticalStrut(6));

        // Button row
        JPanel btnRow = new JPanel(new BorderLayout(4, 0));
        btnRow.setOpaque(false);
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        leftBtns.setOpaque(false);

        eyeButton   = makeIconButton("👁",  "Toggle hidden drops");
        sortButton  = makeIconButton("⇅",  currentSort.getLabel());
        clearButton = makeIconButton("🗑", "Clear all loot data");

        sortButton.setBackground(new Color(55, 90, 55)); // RECENT = green

        eyeButton  .addActionListener(e -> toggleHiddenItems());
        sortButton .addActionListener(e -> cycleSortMode());
        clearButton.addActionListener(e -> confirmClearAll());

        leftBtns.add(eyeButton);
        leftBtns.add(sortButton);
        leftBtns.add(clearButton);

        syncButton = new JButton("Sync");
        syncButton.setPreferredSize(new Dimension(58, 24));
        syncButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
        syncButton.setForeground(Color.WHITE);
        syncButton.setFocusPainted(false);
        syncButton.setBorder(BorderFactory.createLineBorder(new Color(70, 70, 70), 1));
        syncButton.setFont(FontManager.getRunescapeSmallFont());
        syncButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        syncButton.setToolTipText("Sync with RuneAlytics server");
        syncButton.addActionListener(e -> onSyncClicked());

        btnRow.add(leftBtns,   BorderLayout.WEST);
        btnRow.add(syncButton, BorderLayout.EAST);

        header.add(btnRow);
        return header;
    }

    private JButton makeIconButton(String icon, String tooltip)
    {
        JButton btn = new JButton(icon);
        btn.setPreferredSize(new Dimension(30, 24));
        btn.setBackground(ColorScheme.DARK_GRAY_COLOR);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createLineBorder(new Color(70, 70, 70), 1));
        btn.setFont(new Font("Dialog", Font.PLAIN, 13));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText(tooltip);
        btn.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseEntered(MouseEvent e)
            {
                if (btn.isEnabled()) btn.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
            }
            @Override public void mouseExited(MouseEvent e) { restoreButtonColor(btn); }
        });
        return btn;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Button actions
    // ═════════════════════════════════════════════════════════════════════════

    private void toggleHiddenItems()
    {
        showIgnoredItems = !showIgnoredItems;
        eyeButton.setBackground(showIgnoredItems ? ColorScheme.BRAND_ORANGE : ColorScheme.DARK_GRAY_COLOR);
        eyeButton.setToolTipText(showIgnoredItems ? "Hide ignored drops" : "Show ignored drops");
        refreshDisplay();
    }

    private void cycleSortMode()
    {
        currentSort = currentSort.next();
        sortButton.setToolTipText(currentSort.getLabel());
        switch (currentSort)
        {
            case RECENT: sortButton.setBackground(new Color(55, 90, 55));       break;
            case VALUE:  sortButton.setBackground(ColorScheme.DARK_GRAY_COLOR); break;
            case KILLS:  sortButton.setBackground(new Color(55, 55, 90));       break;
        }
        refreshDisplay();
    }

    private void confirmClearAll()
    {
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete ALL loot tracking data?\nThis cannot be undone.",
                "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) lootManager.clearAllData();
    }

    private void onSyncClicked()
    {
        long remaining = SYNC_COOLDOWN_MS - (System.currentTimeMillis() - lastSyncTime);
        if (remaining > 0)
        {
            long mins = remaining / 60_000;
            long secs = (remaining % 60_000) / 1_000;
            JOptionPane.showMessageDialog(this,
                    String.format("Wait %d:%02d before syncing again.", mins, secs),
                    "Sync Cooldown", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String username = runeAlyticsState.getVerifiedUsername();
        if (username == null || username.isEmpty() || !runeAlyticsState.isVerified())
        {
            JOptionPane.showMessageDialog(this,
                    "Log in and verify your account before syncing.",
                    "Not Verified", JOptionPane.WARNING_MESSAGE);
            return;
        }

        lastSyncTime = System.currentTimeMillis();
        syncButton.setEnabled(false);
        syncButton.setText("…");
        lootManager.performManualSync(username);
    }

    public void showSyncCompleted()
    {
        syncButton.setEnabled(true);
        syncButton.setText("Sync");
        JOptionPane.showMessageDialog(this, "Loot data synced successfully!",
                "Sync Complete", JOptionPane.INFORMATION_MESSAGE);
    }

    public void showSyncFailed(String error)
    {
        syncButton.setEnabled(true);
        syncButton.setText("Sync");
        JOptionPane.showMessageDialog(this, "Sync failed: " + error,
                "Sync Failed", JOptionPane.ERROR_MESSAGE);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LootTrackerUpdateListener
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void onKillRecorded(NpcKillRecord kill, BossKillStats stats)
    {
        SwingUtilities.invokeLater(() -> {
            highlightBoss(kill.getNpcName());
            refreshDisplay();
        });
    }

    @Override public void onLootUpdated() { SwingUtilities.invokeLater(this::refreshDisplay); }
    @Override public void onDataRefresh()  { SwingUtilities.invokeLater(this::refreshDisplay); }

    // ═════════════════════════════════════════════════════════════════════════
    //  Public API
    // ═════════════════════════════════════════════════════════════════════════

    public void highlightBoss(String npcName)
    {
        highlightedBoss = npcName;
        if (highlightTimer != null) highlightTimer.stop();
        highlightTimer = new javax.swing.Timer(HIGHLIGHT_TIMEOUT_MS, e -> {
            highlightedBoss = null;
            SwingUtilities.invokeLater(this::refreshDisplay);
        });
        highlightTimer.setRepeats(false);
        highlightTimer.start();
        SwingUtilities.invokeLater(this::refreshDisplay);
    }

    public void refresh() { SwingUtilities.invokeLater(this::refreshDisplay); }

    // ═════════════════════════════════════════════════════════════════════════
    //  Display rebuild
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Clears and rebuilds the boss-card list, then calls
     * {@link #propagateWheelEvents} on the whole subtree.
     */
    public void refreshDisplay()
    {
        if (isRefreshing) return;
        isRefreshing = true;
        try
        {
            int savedScroll = scrollPane.getVerticalScrollBar().getValue();

            bossListPanel.removeAll();

            List<BossKillStats> allStats = lootManager.getAllBossStats();

            if (allStats.isEmpty())
            {
                bossListPanel.add(buildEmptyState());
            }
            else
            {
                Map<String, BossKillStats> unique = new LinkedHashMap<>();
                for (BossKillStats s : allStats) unique.putIfAbsent(s.getNpcName(), s);

                List<BossKillStats> sorted = new ArrayList<>(unique.values());
                sortStats(sorted);

                long totalValue = 0;
                int  totalKills = 0;

                for (BossKillStats stats : sorted)
                {
                    totalValue += stats.getTotalLootValue();
                    totalKills += stats.getKillCount();

                    JPanel card = buildBossCard(stats);
                    card.setAlignmentX(Component.LEFT_ALIGNMENT);
                    bossListPanel.add(card);
                    bossListPanel.add(Box.createVerticalStrut(6));
                }

                totalKillsLabel.setText(formatNumber(totalKills) + " kills");
                totalValueLabel.setText(formatGp(totalValue)     + " total");
            }

            // Instrument every new component so wheel events reach scrollPane
            propagateWheelEvents(bossListPanel);

            bossListPanel.revalidate();
            bossListPanel.repaint();

            final int scrollY = savedScroll;
            SwingUtilities.invokeLater(() ->
                    scrollPane.getVerticalScrollBar().setValue(scrollY));
        }
        finally
        {
            isRefreshing = false;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Mouse-wheel propagation
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Recursively attaches a {@link MouseWheelListener} to every component in
     * the subtree rooted at {@code root}.  Each listener re-dispatches the
     * event to {@link #scrollPane} so the viewport scrolls regardless of which
     * child the cursor is hovering over.
     *
     * <p>A client-property flag ({@code "ra.wheel"}) prevents the listener
     * being added more than once per component across repeated
     * {@link #refreshDisplay()} calls.  Recursion still descends into
     * marked containers to catch newly added children.
     */
    private void propagateWheelEvents(Component root)
    {
        final String FLAG = "ra.wheel";

        boolean alreadyDone = (root instanceof JComponent)
                && Boolean.TRUE.equals(((JComponent) root).getClientProperty(FLAG));

        if (!alreadyDone)
        {
            root.addMouseWheelListener(e -> {
                MouseWheelEvent converted = (MouseWheelEvent)
                        SwingUtilities.convertMouseEvent(e.getComponent(), e, scrollPane);
                scrollPane.dispatchEvent(converted);
            });
            if (root instanceof JComponent)
                ((JComponent) root).putClientProperty(FLAG, Boolean.TRUE);
        }

        if (root instanceof Container)
            for (Component child : ((Container) root).getComponents())
                propagateWheelEvents(child);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Boss card
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildBossCard(BossKillStats stats)
    {
        boolean highlighted = stats.getNpcName().equals(highlightedBoss);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                        highlighted ? new Color(0, 200, 60) : new Color(55, 55, 55),
                        highlighted ? 2 : 1),
                new EmptyBorder(0, 0, 4, 0)));

        // Header row
        JPanel headerRow = new JPanel(new BorderLayout(6, 0));
        headerRow.setBackground(highlighted ? new Color(25, 38, 25) : new Color(40, 40, 40));
        headerRow.setBorder(new EmptyBorder(6, 8, 6, 8));
        headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        String displayName = truncate(stats.getNpcName(), 20);
        JLabel nameLabel = new JLabel(displayName + " × " + formatNumber(stats.getKillCount()));
        nameLabel.setForeground(highlighted ? new Color(140, 255, 140) : Color.WHITE);
        nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 11f));
        nameLabel.setToolTipText(stats.getNpcName() + " – " + stats.getKillCount() + " kills");

        JLabel valueLabel = new JLabel(formatGp(stats.getTotalLootValue()));
        valueLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        valueLabel.setFont(FontManager.getRunescapeSmallFont());

        headerRow.add(nameLabel,  BorderLayout.WEST);
        headerRow.add(valueLabel, BorderLayout.EAST);

        JPopupMenu ctx = new JPopupMenu();
        JMenuItem prestigeItem = new JMenuItem("Prestige (Reset KC)");
        prestigeItem.addActionListener(e -> confirmPrestige(stats));
        ctx.add(prestigeItem);
        JMenuItem clearItem = new JMenuItem("Clear Data");
        clearItem.addActionListener(e -> confirmClearBoss(stats));
        ctx.add(clearItem);
        headerRow .setComponentPopupMenu(ctx);
        nameLabel .setComponentPopupMenu(ctx);
        valueLabel.setComponentPopupMenu(ctx);

        // Collapsible grid
        boolean       expanded    = bossExpandedState.getOrDefault(stats.getNpcName(), true);
        final boolean[] isExpanded = { expanded };

        JPanel grid = buildItemGrid(stats.getAggregatedDropsSorted(), stats.getNpcName());
        grid.setVisible(isExpanded[0]);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);

        MouseAdapter collapseToggle = new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e)
            {
                if (SwingUtilities.isLeftMouseButton(e))
                {
                    isExpanded[0] = !isExpanded[0];
                    bossExpandedState.put(stats.getNpcName(), isExpanded[0]);
                    grid.setVisible(isExpanded[0]);
                    card.revalidate(); card.repaint();
                    bossListPanel.revalidate(); bossListPanel.repaint();
                }
            }
            @Override public void mouseEntered(MouseEvent e)
            {
                headerRow.setBackground(highlighted ? new Color(35, 55, 35) : new Color(50, 50, 50));
            }
            @Override public void mouseExited(MouseEvent e)
            {
                headerRow.setBackground(highlighted ? new Color(25, 38, 25) : new Color(40, 40, 40));
            }
        };
        headerRow.addMouseListener(collapseToggle);
        nameLabel .addMouseListener(collapseToggle);

        card.add(headerRow);
        card.add(grid);
        return card;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Item grid
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildItemGrid(List<BossKillStats.AggregatedDrop> drops, String npcName)
    {
        JPanel grid = new JPanel(new FixedColumnsWrapLayout(ITEMS_PER_ROW, ITEM_GAP, ITEM_GAP));
        grid.setBackground(new Color(30, 30, 30));
        grid.setBorder(new EmptyBorder(5, 6, 6, 6));
        for (BossKillStats.AggregatedDrop drop : drops)
        {
            if (lootManager.isDropHidden(npcName, drop.getItemId()) && !showIgnoredItems) continue;
            grid.add(buildItemSlot(drop, npcName));
        }
        return grid;
    }

    private JPanel buildItemSlot(BossKillStats.AggregatedDrop drop, String npcName)
    {
        boolean ignored = lootManager.isDropHidden(npcName, drop.getItemId());

        JLayeredPane slot = new JLayeredPane();
        slot.setPreferredSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
        slot.setMinimumSize  (new Dimension(ITEM_SIZE, ITEM_SIZE));
        slot.setMaximumSize  (new Dimension(ITEM_SIZE, ITEM_SIZE));
        slot.setOpaque(true);
        slot.setBackground(ignored ? new Color(55, 35, 35) : new Color(38, 38, 38));
        slot.setBorder(BorderFactory.createLineBorder(new Color(65, 65, 65), 1));

        JLabel iconLabel = new JLabel();
        iconLabel.setBounds(0, 0, ITEM_SIZE, ITEM_SIZE);
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        iconLabel.setVerticalAlignment  (SwingConstants.CENTER);

        AsyncBufferedImage img =
                itemManager.getImage(drop.getItemId(), (int) drop.getTotalQuantity(), false);
        if (img != null) img.addTo(iconLabel);
        slot.add(iconLabel, JLayeredPane.DEFAULT_LAYER);

        if (drop.getTotalQuantity() > 1)
        {
            JLabel qty = new JLabel(formatQuantity(drop.getTotalQuantity()));
            qty.setFont(new Font("Arial", Font.BOLD, 10));
            qty.setForeground(valueColour(drop.getTotalValue()));
            qty.setBounds(2, 1, ITEM_SIZE - 4, 13);
            qty.setHorizontalAlignment(SwingConstants.LEFT);
            slot.add(qty, JLayeredPane.PALETTE_LAYER);
        }

        String tt = buildTooltip(drop);
        slot.setToolTipText(tt); iconLabel.setToolTipText(tt);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem toggleItem = new JMenuItem(ignored ? "Un-ignore" : "Ignore");
        toggleItem.addActionListener(e -> {
            if (ignored) lootManager.unhideDropForNpc(npcName, drop.getItemId());
            else         lootManager.hideDropForNpc  (npcName, drop.getItemId());
            refreshDisplay();
        });
        menu.add(toggleItem);

        slot.addMouseListener(new MouseAdapter()
        {
            @Override public void mousePressed (MouseEvent e) { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
            private void maybePopup(MouseEvent e)
            {
                if (e.isPopupTrigger()) menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setPreferredSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
        wrapper.setMinimumSize  (new Dimension(ITEM_SIZE, ITEM_SIZE));
        wrapper.setMaximumSize  (new Dimension(ITEM_SIZE, ITEM_SIZE));
        wrapper.setOpaque(false);
        wrapper.add(slot, BorderLayout.CENTER);
        return wrapper;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Empty state
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildEmptyState()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        p.setBorder(new EmptyBorder(40, 20, 40, 20));
        p.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel skull = new JLabel("💀");
        skull.setFont(new Font("Dialog", Font.PLAIN, 48));
        skull.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel msg = new JLabel("No loot tracked yet");
        msg.setForeground(Color.WHITE);
        msg.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 12f));
        msg.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel sub = new JLabel("Kill bosses to start tracking!");
        sub.setForeground(Color.GRAY);
        sub.setFont(FontManager.getRunescapeSmallFont());
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);

        p.add(skull);
        p.add(Box.createVerticalStrut(8));
        p.add(msg);
        p.add(Box.createVerticalStrut(4));
        p.add(sub);

        totalKillsLabel.setText("0 kills");
        totalValueLabel.setText("0 gp total");
        return p;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Sorting
    // ═════════════════════════════════════════════════════════════════════════

    private void sortStats(List<BossKillStats> stats)
    {
        stats.sort((a, b) -> {
            boolean aHl = a.getNpcName().equals(highlightedBoss);
            boolean bHl = b.getNpcName().equals(highlightedBoss);
            if (aHl && !bHl) return -1;
            if (bHl && !aHl) return  1;
            switch (currentSort)
            {
                case VALUE:  return Long.   compare(b.getTotalLootValue(),    a.getTotalLootValue());
                case KILLS:  return Integer.compare(b.getKillCount(),         a.getKillCount());
                case RECENT: return Long.   compare(b.getLastKillTimestamp(), a.getLastKillTimestamp());
                default:     return 0;
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Confirm dialogs
    // ═════════════════════════════════════════════════════════════════════════

    private void confirmPrestige(BossKillStats stats)
    {
        int r = JOptionPane.showConfirmDialog(this,
                String.format("Prestige %s? Resets KC to Prestige %d.",
                        stats.getNpcName(), stats.getPrestige() + 1),
                "Confirm Prestige", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) lootManager.prestigeBoss(stats.getNpcName());
    }

    private void confirmClearBoss(BossKillStats stats)
    {
        int r = JOptionPane.showConfirmDialog(this,
                String.format("Clear all data for %s? (%d kills, %s loot)",
                        stats.getNpcName(), stats.getKillCount(),
                        formatGp(stats.getTotalLootValue())),
                "Confirm Clear", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
        if (r == JOptionPane.YES_OPTION) lootManager.clearBossData(stats.getNpcName());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Utility
    // ═════════════════════════════════════════════════════════════════════════

    private void restoreButtonColor(JButton btn)
    {
        if      (btn == eyeButton)   btn.setBackground(showIgnoredItems ? ColorScheme.BRAND_ORANGE : ColorScheme.DARK_GRAY_COLOR);
        else if (btn == sortButton)
        {
            switch (currentSort)
            {
                case RECENT: btn.setBackground(new Color(55, 90, 55));       break;
                case VALUE:  btn.setBackground(ColorScheme.DARK_GRAY_COLOR); break;
                case KILLS:  btn.setBackground(new Color(55, 55, 90));       break;
            }
        }
        else btn.setBackground(ColorScheme.DARK_GRAY_COLOR);
    }

    private String truncate(String s, int maxLen)
    {
        if (s == null || s.length() <= maxLen) return s;
        int cut = s.lastIndexOf(' ', maxLen - 1);
        return (cut > maxLen / 2 ? s.substring(0, cut) : s.substring(0, maxLen - 1)) + "…";
    }

    private String formatGp(long v)
    {
        if (v >= 1_000_000_000) return String.format("%.2fB gp", v / 1e9);
        if (v >= 1_000_000)     return String.format("%.2fM gp", v / 1e6);
        if (v >= 1_000)         return String.format("%.1fK gp", v / 1e3);
        return v + " gp";
    }

    private String formatNumber(long n)
    {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1e9);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1e6);
        if (n >= 1_000)         return String.format("%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    private String formatQuantity(long qty)
    {
        if (qty >= 1_000_000) return String.format("%.1fM", qty / 1e6);
        if (qty >= 1_000)     return String.format("%.1fK", qty / 1e3);
        return String.valueOf(qty);
    }

    private Color valueColour(long value)
    {
        if (value >= 10_000_000) return new Color(255, 170,   0);
        if (value >=  1_000_000) return new Color(200, 100, 255);
        if (value >=    100_000) return new Color(100, 200, 255);
        if (value >=      1_000) return new Color(100, 255, 100);
        return new Color(255, 215, 0);
    }

    private String buildTooltip(BossKillStats.AggregatedDrop drop)
    {
        return "<html>"
                + "<b>" + drop.getItemName() + "</b><br>"
                + "Dropped: <b>" + drop.getDropCount() + "×</b><br>"
                + "Qty: "        + QuantityFormatter.formatNumber((int) drop.getTotalQuantity()) + "<br>"
                + "GE: "         + formatGp(drop.getGePrice()) + " ea<br>"
                + "Total: <b>"   + formatGp(drop.getTotalValue()) + "</b><br>"
                + "Alch: "       + formatGp(drop.getHighAlchValue()) + " ea"
                + "</html>";
    }
}