package com.runealytics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Singleton
public class LootTrackerPanel extends PluginPanel implements LootTrackerUpdateListener
{
    private static final int  ITEMS_PER_ROW    = 5;
    private static final int  ITEM_SIZE        = 42;
    private static final int  ITEM_GAP         = 3;
    private static final int  PAD              = 6;
    private static final int  HIGHLIGHT_TIMEOUT_MS = 10_000;
    private static final long SYNC_COOLDOWN_MS = 5 * 60 * 1_000L;

    private static final Font CALIBRI_BOLD  = new Font("Calibri", Font.BOLD, 14);
    private static final Font CALIBRI_PLAIN = new Font("Calibri", Font.PLAIN, 12);
    private static final Font FILTER_FONT   = new Font("Calibri", Font.BOLD, 11);

    private static final Color PICKPOCKET_HEADER_HL   = new Color(30, 18, 55);
    private static final Color PICKPOCKET_HEADER_BASE = new Color(25, 15, 45);
    private static final Color SKILLING_HEADER_HL     = new Color(15, 40, 20);
    private static final Color SKILLING_HEADER_BASE   = new Color(10, 30, 15);

    private static final Color FILTER_ACTIVE_ALL     = new Color(70,  70, 110);
    private static final Color FILTER_ACTIVE_COMBAT  = new Color(110,  40,  40);
    private static final Color FILTER_ACTIVE_SKILLS  = new Color( 35,  85,  45);
    private static final Color FILTER_INACTIVE       = new Color( 38,  38,  38);
    private static final Color FILTER_BORDER_ACTIVE  = new Color(180, 180, 220);
    private static final Color FILTER_BORDER_INACTIVE= new Color( 70,  70,  70);

    private static final String   ALL_SKILLS_LABEL = "All Skills";
    private static final String[] SKILL_OPTIONS    = {
            ALL_SKILLS_LABEL,
            "Woodcutting", "Fishing",   "Mining",    "Farming",
            "Hunter",      "Herblore",  "Runecraft", "Fletching",
            "Cooking",     "Smithing",  "Crafting",  "Agility",
            "Thieving"
    };

    private final LootTrackerManager lootManager;
    private final RuneAlyticsState   runeAlyticsState;
    private final ItemManager        itemManager;
    private final RuneAlyticsPlugin  plugin;

    private final JLabel totalKillsLabel = new JLabel("0 kills");
    private final JLabel totalValueLabel = new JLabel("0 gp");
    private final JPanel bossListPanel   = new JPanel();

    private JButton           eyeButton;
    private JButton           sortButton;
    private JButton           clearButton;
    private JButton           syncButton;
    private JLabel            syncStatusLabel;
    private javax.swing.Timer syncResetTimer;
    private JButton           filterAllButton;
    private JButton           filterCombatButton;
    private JComboBox<String> skillsCombo;
    private JScrollPane       scrollPane;
    private boolean           suppressComboAction = false;

    private String       lastDisplayFingerprint = null;
    private String       highlightedBoss        = null;
    private boolean      showIgnoredItems       = false;
    private SortMode     currentSort            = SortMode.RECENT;
    private SourceFilter currentFilter          = SourceFilter.ALL;
    private String       currentSkillFilter     = null;
    private long         lastSyncTime           = 0L;

    private final Map<String, Boolean> bossExpandedState = new HashMap<>();
    private final Map<String, JLabel>  itemSlotMap       = new ConcurrentHashMap<>();
    private final Map<String, JPanel>  bossCardMap       = new ConcurrentHashMap<>();
    private final Map<String, JLabel>  bossValueLabelMap = new ConcurrentHashMap<>();
    private final Map<String, JLabel>  bossNameLabelMap  = new ConcurrentHashMap<>();

    private final Map<String, javax.swing.Timer> lootDebounceMap = new ConcurrentHashMap<>();

    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final java.util.concurrent.ExecutorService refreshPool =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "RuneAlytics-Refresh");
                t.setDaemon(true);
                return t;
            });
    private javax.swing.Timer highlightTimer;
    private javax.swing.Timer refreshDebounce;

    @Getter
    private enum SortMode
    {
        RECENT("Sort: Recent"),
        VALUE ("Sort: Value"),
        KILLS ("Sort: Kills");

        private final String label;
        SortMode(String label) { this.label = label; }

        SortMode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private enum SourceFilter { ALL, COMBAT, SKILLS }

    @Inject
    public LootTrackerPanel(
            LootTrackerManager lootManager,
            RuneAlyticsState   runeAlyticsState,
            ItemManager        itemManager,
            RuneAlyticsPlugin  plugin)
    {
        super(false);
        this.lootManager      = lootManager;
        this.runeAlyticsState = runeAlyticsState;
        this.itemManager      = itemManager;
        this.plugin           = plugin;

        refreshDebounce = new javax.swing.Timer(150, e -> executeRefresh());
        refreshDebounce.setRepeats(false);

        lootManager.addListener(this);
        buildUi();
        SwingUtilities.invokeLater(this::refreshDisplay);
    }

    private void buildUi()
    {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(buildHeader(), BorderLayout.NORTH);

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
        scrollPane.getVerticalScrollBar().addComponentListener(new ComponentAdapter()
        {
            @Override public void componentShown (ComponentEvent e) { setScrollPadding(true);  }
            @Override public void componentHidden(ComponentEvent e) { setScrollPadding(false); }
        });

        add(scrollPane, BorderLayout.CENTER);
    }

    private void setScrollPadding(boolean visible)
    {
        int leftPad  = visible ? 2 : PAD;
        int rightPad = PAD;
        if (visible)
        {
            int sbw = scrollPane.getVerticalScrollBar().getWidth();
            if (sbw <= 0) sbw = UIManager.getInt("ScrollBar.width");
            if (sbw <= 0) sbw = 13;
            rightPad = PAD + sbw;
        }
        bossListPanel.setBorder(new EmptyBorder(PAD, leftPad, PAD, rightPad));
        bossListPanel.revalidate();
        bossListPanel.repaint();
    }

    private JPanel buildHeader()
    {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(new Color(28, 28, 28));
        header.setBorder(new EmptyBorder(8, 8, 8, 8));

        // ── Stats row ────────────────────────────────────────────────────────
        JPanel statsRow = new JPanel(new BorderLayout(8, 0));
        statsRow.setOpaque(false);
        statsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        statsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JLabel icon = new JLabel("💰");
        icon.setFont(new Font("Dialog", Font.PLAIN, 22));

        JPanel statsText = new JPanel(new GridLayout(2, 1, 0, 1));
        statsText.setOpaque(false);
        totalKillsLabel.setForeground(Color.WHITE);
        totalKillsLabel.setFont(CALIBRI_BOLD);
        totalValueLabel.setForeground(new Color(200, 180, 80));
        totalValueLabel.setFont(CALIBRI_PLAIN);
        statsText.add(totalKillsLabel);
        statsText.add(totalValueLabel);

        statsRow.add(icon,      BorderLayout.WEST);
        statsRow.add(statsText, BorderLayout.CENTER);
        header.add(statsRow);
        header.add(Box.createVerticalStrut(7));

        // ── Divider ──────────────────────────────────────────────────────────
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(55, 55, 55));
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(sep);
        header.add(Box.createVerticalStrut(6));

        // ── Filter row: [All] [PvM]  [🌿 Skills ▼]  ────────────────────────
        // Two solid pill buttons on the left, compact skills dropdown on the right.
        // The dropdown is given a fixed preferred width (~60% of sidebar) instead of
        // stretching to fill, so it doesn't crowd the pills.
        JPanel filterRow = new JPanel(new BorderLayout(6, 0));
        filterRow.setOpaque(false);
        filterRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        filterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        filterAllButton    = makeFilterPill("All",  FILTER_ACTIVE_ALL,    () -> setFilter(SourceFilter.ALL,    null));
        filterCombatButton = makeFilterPill("⚔ PvM", FILTER_ACTIVE_COMBAT, () -> setFilter(SourceFilter.COMBAT, null));

        filterAllButton.setPreferredSize(new Dimension(36, 22));
        filterCombatButton.setPreferredSize(new Dimension(52, 22));

        JPanel pillGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pillGroup.setOpaque(false);
        pillGroup.add(filterAllButton);
        pillGroup.add(filterCombatButton);

        skillsCombo = new JComboBox<>(SKILL_OPTIONS);
        skillsCombo.setBackground(FILTER_INACTIVE);
        skillsCombo.setForeground(new Color(160, 160, 160));
        skillsCombo.setFont(FILTER_FONT);
        skillsCombo.setFocusable(false);
        skillsCombo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        skillsCombo.setToolTipText("Filter by skill");
        skillsCombo.setBorder(BorderFactory.createLineBorder(FILTER_BORDER_INACTIVE, 1));
        skillsCombo.setPreferredSize(new Dimension(95, 22));
        skillsCombo.setMaximumSize(new Dimension(95, 22));

        skillsCombo.setRenderer(new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus)
            {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String v = (String) value;
                setText(ALL_SKILLS_LABEL.equals(v) ? "🌿 Skills" : "  " + v);
                setBackground(isSelected ? FILTER_ACTIVE_SKILLS : new Color(32, 32, 32));
                setForeground(isSelected ? Color.WHITE : new Color(170, 170, 170));
                setBorder(new EmptyBorder(2, 4, 2, 4));
                return this;
            }
        });

        skillsCombo.addActionListener(e -> {
            if (suppressComboAction) return;
            String sel = (String) skillsCombo.getSelectedItem();
            setFilter(SourceFilter.SKILLS, ALL_SKILLS_LABEL.equals(sel) ? null : sel);
        });

        JPanel comboWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        comboWrap.setOpaque(false);
        comboWrap.add(skillsCombo);

        filterRow.add(pillGroup, BorderLayout.WEST);
        filterRow.add(comboWrap, BorderLayout.EAST);

        applyFilterColors();
        header.add(filterRow);
        header.add(Box.createVerticalStrut(6));

        // ── Action buttons row ───────────────────────────────────────────────
        JPanel btnRow = new JPanel(new BorderLayout(4, 0));
        btnRow.setOpaque(false);
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        leftBtns.setOpaque(false);

        eyeButton   = makeIconButton("👁",  "Toggle hidden drops");
        sortButton  = makeIconButton("⇅",  currentSort.getLabel());
        clearButton = makeIconButton("🗑", "Clear all loot data");
        sortButton.setBackground(new Color(45, 70, 45));

        eyeButton  .addActionListener(e -> toggleHiddenItems());
        sortButton .addActionListener(e -> cycleSortMode());
        clearButton.addActionListener(e -> confirmClearAll());

        JButton importBtn = makeIconButton("⬇", "Import from RuneLite Loot Tracker");
        importBtn.addActionListener(e -> onImportFromRuneLiteClicked());

        leftBtns.add(eyeButton);
        leftBtns.add(sortButton);
        leftBtns.add(clearButton);
        leftBtns.add(importBtn);

        syncButton = new JButton("Sync");
        syncButton.setPreferredSize(new Dimension(52, 24));
        syncButton.setBackground(new Color(40, 60, 90));
        syncButton.setForeground(Color.WHITE);
        syncButton.setFocusPainted(false);
        syncButton.setBorder(BorderFactory.createLineBorder(new Color(60, 90, 130), 1));
        syncButton.setFont(FILTER_FONT);
        syncButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        syncButton.setToolTipText("Sync with RuneAlytics server");
        syncButton.addActionListener(e -> onSyncClicked());

        btnRow.add(leftBtns,   BorderLayout.WEST);
        btnRow.add(syncButton, BorderLayout.EAST);
        header.add(btnRow);

        // ── Sync status line (hidden until a sync completes / fails) ─────────
        syncStatusLabel = new JLabel(" ");
        syncStatusLabel.setFont(new Font("Calibri", Font.BOLD, 10));
        syncStatusLabel.setForeground(new Color(0, 0, 0, 0)); // fully transparent initially
        syncStatusLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        syncStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        syncStatusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
        syncStatusLabel.setBorder(new EmptyBorder(1, 0, 0, 0));
        header.add(syncStatusLabel);

        return header;
    }

    private JButton makeFilterPill(String label, Color activeColor, Runnable action)
    {
        JButton btn = new JButton(label);
        btn.setFont(FILTER_FONT);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(FILTER_INACTIVE);
        btn.setForeground(new Color(160, 160, 160));
        btn.setBorder(BorderFactory.createLineBorder(FILTER_BORDER_INACTIVE, 1));
        btn.addActionListener(e -> action.run());
        btn.putClientProperty("activeColor", activeColor);
        return btn;
    }

    private void setFilter(SourceFilter filter, String skillFilter)
    {
        currentFilter     = filter;
        currentSkillFilter = skillFilter;
        if (filter != SourceFilter.SKILLS)
        {
            suppressComboAction = true;
            skillsCombo.setSelectedIndex(0);
            suppressComboAction = false;
        }
        applyFilterColors();
        invalidateFingerprint();
        refreshDisplay();
    }

    private void applyFilterColors()
    {
        styleFilterPill(filterAllButton,    currentFilter == SourceFilter.ALL,    FILTER_ACTIVE_ALL);
        styleFilterPill(filterCombatButton, currentFilter == SourceFilter.COMBAT, FILTER_ACTIVE_COMBAT);

        boolean skillsActive = currentFilter == SourceFilter.SKILLS;
        skillsCombo.setBackground(skillsActive ? FILTER_ACTIVE_SKILLS : FILTER_INACTIVE);
        skillsCombo.setForeground(skillsActive ? Color.WHITE : new Color(160, 160, 160));
        skillsCombo.setBorder(BorderFactory.createLineBorder(
                skillsActive ? FILTER_BORDER_ACTIVE : FILTER_BORDER_INACTIVE, 1));
    }

    private void styleFilterPill(JButton btn, boolean active, Color activeColor)
    {
        btn.setBackground(active ? activeColor : FILTER_INACTIVE);
        btn.setForeground(active ? Color.WHITE : new Color(160, 160, 160));
        btn.setBorder(BorderFactory.createLineBorder(
                active ? FILTER_BORDER_ACTIVE : FILTER_BORDER_INACTIVE, 1));
    }

    private JButton makeIconButton(String icon, String tooltip)
    {
        JButton btn = new JButton(icon);
        btn.setPreferredSize(new Dimension(28, 24));
        btn.setBackground(new Color(38, 38, 38));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createLineBorder(new Color(65, 65, 65), 1));
        btn.setFont(new Font("Dialog", Font.PLAIN, 12));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText(tooltip);
        btn.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseEntered(MouseEvent e) { if (btn.isEnabled()) btn.setBackground(new Color(55, 55, 55)); }
            @Override public void mouseExited (MouseEvent e) { restoreButtonColor(btn); }
        });
        return btn;
    }

    private void toggleHiddenItems()
    {
        showIgnoredItems = !showIgnoredItems;
        eyeButton.setBackground(showIgnoredItems ? new Color(160, 80, 0) : new Color(38, 38, 38));
        eyeButton.setToolTipText(showIgnoredItems ? "Hide ignored drops" : "Show ignored drops");
        invalidateFingerprint();
        refreshDisplay();
    }

    private void cycleSortMode()
    {
        currentSort = currentSort.next();
        sortButton.setToolTipText(currentSort.getLabel());
        switch (currentSort)
        {
            case RECENT: sortButton.setBackground(new Color(45, 70, 45)); break;
            case VALUE:  sortButton.setBackground(new Color(70, 45, 45)); break;
            case KILLS:  sortButton.setBackground(new Color(45, 45, 70)); break;
        }
        invalidateFingerprint();
        refreshDisplay();
    }

    private void confirmClearAll()
    {
        int ch = JOptionPane.showConfirmDialog(this,
                "Delete ALL loot tracking data?\nThis cannot be undone.",
                "Confirm Clear All", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ch == JOptionPane.YES_OPTION)
        {
            invalidateFingerprint();
            lootManager.clearAllData();
        }
    }

    private void onImportFromRuneLiteClicked()
    {
        String username = runeAlyticsState.getVerifiedUsername();
        if (username == null || username.isEmpty() || !runeAlyticsState.isVerified())
        {
            JOptionPane.showMessageDialog(this, "Log in and verify your account before importing.",
                    "Not Verified", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int ch = JOptionPane.showConfirmDialog(this,
                "<html>Import loot history from RuneLite's built-in Loot Tracker?<br><br>"
                        + "RuneLite will be scanned for a loot data file for <b>" + username + "</b>.<br>"
                        + "If not found automatically, you can browse to it manually.<br><br>"
                        + "Existing RuneAlytics data is never overwritten.</html>",
                "Import from RuneLite", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (ch != JOptionPane.YES_OPTION) return;
        new Thread(() -> runImport(username, null), "runealytics-rl-import").start();
    }

    private void runImport(String username, java.io.File manualFile)
    {
        String result = lootManager.importFromRuneLiteLootTracker(username, manualFile);

        if (result.startsWith("__CHOOSE_FILE__:"))
        {
            String startDir = result.substring("__CHOOSE_FILE__:".length());
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "<html><b>No loot tracker data found automatically.</b><br><br>"
                                + "Modern RuneLite stores loot data inside <b>profiles2/</b> config files.<br>"
                                + "If you see no data it means RuneLite's Loot Tracker plugin<br>"
                                + "has not saved any kills to your profile yet.<br><br>"
                                + "<b>To generate a file RuneLite can export:</b><br>"
                                + "1. Enable the Loot Tracker plugin in RuneLite<br>"
                                + "2. Kill any boss at least once<br>"
                                + "3. In the Loot Tracker panel, click the export/save icon<br><br>"
                                + "Alternatively, browse for any compatible JSON file below.",
                        "File Not Found", JOptionPane.INFORMATION_MESSAGE);

                JFileChooser chooser = new JFileChooser(new java.io.File(startDir));
                chooser.setDialogTitle("Select RuneLite Loot Tracker JSON File");
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON files (*.json)", "json"));
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                {
                    java.io.File chosen = chooser.getSelectedFile();
                    new Thread(() -> {
                        String r2 = lootManager.importFromRuneLiteLootTracker(username, chosen);
                        invalidateFingerprint();
                        SwingUtilities.invokeLater(() ->
                                JOptionPane.showMessageDialog(this, r2, "Import Result", JOptionPane.INFORMATION_MESSAGE));
                    }, "runealytics-rl-import-manual").start();
                }
            });
        }
        else
        {
            invalidateFingerprint();
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, result, "Import Result", JOptionPane.INFORMATION_MESSAGE));
        }
    }

    private void onSyncClicked()
    {
        long remaining = SYNC_COOLDOWN_MS - (System.currentTimeMillis() - lastSyncTime);
        if (remaining > 0)
        {
            long mins = remaining / 60_000;
            long secs = (remaining % 60_000) / 1_000;
            if (syncResetTimer != null) syncResetTimer.stop();
            syncStatusLabel.setText(String.format("⏳ Wait %d:%02d", mins, secs));
            syncStatusLabel.setForeground(new Color(200, 160, 60));
            syncResetTimer = new javax.swing.Timer(2_500, e -> resetSyncButton());
            syncResetTimer.setRepeats(false);
            syncResetTimer.start();
            return;
        }

        String username = runeAlyticsState.getVerifiedUsername();
        if (username == null || username.isEmpty() || !runeAlyticsState.isVerified())
        {
            JOptionPane.showMessageDialog(this, "Log in and verify your account before syncing.",
                    "Not Verified", JOptionPane.WARNING_MESSAGE);
            return;
        }

        lastSyncTime = System.currentTimeMillis();
        syncButton.setEnabled(false);
        syncButton.setText("⟳ Sync…");
        syncButton.setBackground(new Color(30, 50, 80));
        syncButton.setBorder(BorderFactory.createLineBorder(new Color(80, 120, 180), 1));
        syncStatusLabel.setText("Syncing…");
        syncStatusLabel.setForeground(new Color(100, 160, 220));
        lootManager.performManualSync(username);
    }

    public void showSyncCompleted()
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            invalidateFingerprint();
            flashSyncButton(true);
        }
        else
        {
            SwingUtilities.invokeLater(() -> { invalidateFingerprint(); flashSyncButton(true); });
        }
    }

    public void showSyncFailed(String error)
    {
        if (SwingUtilities.isEventDispatchThread())
            flashSyncButton(false);
        else
            SwingUtilities.invokeLater(() -> flashSyncButton(false));
    }

    /**
     * Animates the sync button and status label to reflect the outcome.
     * Reverts automatically after 2.5 seconds — no modal dialog.
     *
     * @param success {@code true} for green "✓ Synced", {@code false} for red "✗ Failed"
     */
    private void flashSyncButton(boolean success)
    {
        if (syncResetTimer != null) syncResetTimer.stop();

        Color  btnColor   = success ? new Color(30,  90, 40)  : new Color(100, 30, 30);
        Color  borderCol  = success ? new Color(60, 160, 70)  : new Color(180, 50, 50);
        Color  labelColor = success ? new Color(80, 200, 100) : new Color(220, 80, 80);
        String btnText    = success ? "✓ Synced"              : "✗ Failed";
        String statusText = success ? "✓ Synced to server"    : "✗ Sync failed";

        syncButton.setEnabled(true);
        syncButton.setText(btnText);
        syncButton.setBackground(btnColor);
        syncButton.setBorder(BorderFactory.createLineBorder(borderCol, 1));

        syncStatusLabel.setText(statusText);
        syncStatusLabel.setForeground(labelColor);

        syncResetTimer = new javax.swing.Timer(2_500, e -> resetSyncButton());
        syncResetTimer.setRepeats(false);
        syncResetTimer.start();
    }

    private void resetSyncButton()
    {
        syncButton.setText("Sync");
        syncButton.setBackground(new Color(40, 60, 90));
        syncButton.setBorder(BorderFactory.createLineBorder(new Color(60, 90, 130), 1));
        syncStatusLabel.setText(" ");
        syncStatusLabel.setForeground(new Color(0, 0, 0, 0));
    }

    @Override
    public void onKillRecorded(NpcKillRecord kill, BossKillStats stats)
    {
        scheduleLootUpdate(kill.getNpcName(), stats);
    }

    @Override
    public void onLootUpdated(BossKillStats stats, LootStorageData.KillRecord kill)
    {
        scheduleLootUpdate(stats.getNpcName(), stats);
    }

    @Override
    public void onDataRefresh()
    {
        refreshDisplay();
    }

    /**
     * Debounces per-boss updates so rapid pickpocket spam coalesces into a
     * single update 80ms after the last event rather than one per click.
     */
    private void scheduleLootUpdate(String npcName, BossKillStats stats)
    {
        javax.swing.Timer existing = lootDebounceMap.get(npcName);
        if (existing != null) existing.stop();

        javax.swing.Timer t = new javax.swing.Timer(80, e -> {
            lootDebounceMap.remove(npcName);
            updateLoot(npcName, stats);
        });
        t.setRepeats(false);
        lootDebounceMap.put(npcName, t);

        if (SwingUtilities.isEventDispatchThread())
            t.start();
        else
            SwingUtilities.invokeLater(t::start);
    }

    public void highlightBoss(String npcName)
    {
        highlightedBoss = npcName;
        if (highlightTimer != null) highlightTimer.stop();
        highlightTimer = new javax.swing.Timer(HIGHLIGHT_TIMEOUT_MS, e -> {
            highlightedBoss = null;
            invalidateFingerprint();
            refreshDisplay();
        });
        highlightTimer.setRepeats(false);
        highlightTimer.start();
    }

    public void refresh()
    {
        refreshDisplay();
    }

    public void updateLoot(String npcName, BossKillStats stats)
    {
        if (!passesFilter(npcName)) return;

        List<BossKillStats.AggregatedDrop> drops = lootManager.getStorageDropsForBoss(npcName);
        long totalValue = drops.stream().mapToLong(BossKillStats.AggregatedDrop::getTotalValue).sum();

        JPanel card = bossCardMap.get(npcName);
        if (card == null)
        {
            invalidateFingerprint();
            refreshDisplay();
            return;
        }

        boolean needsRebuild = false;
        for (BossKillStats.AggregatedDrop drop : drops)
        {
            JLabel existing = itemSlotMap.get(npcName + "_" + drop.getItemId());
            if (existing != null)
            {
                AsyncBufferedImage img = itemManager.getImage(
                        drop.getItemId(), drop.getTotalQuantity(), drop.getTotalQuantity() > 1);
                img.addTo(existing);
            }
            else
            {
                needsRebuild = true;
            }
        }

        JLabel vl = bossValueLabelMap.get(npcName);
        JLabel nl = bossNameLabelMap.get(npcName);
        if (vl != null) vl.setText(totalValue > 0 ? formatGp(totalValue) : "");
        if (nl != null) nl.setText(buildNameLabel(npcName, stats.getKillCount()));

        if (needsRebuild)
            rebuildBossCardGrid(npcName, drops);
        else
        {
            bossListPanel.repaint();
        }
    }

    private void rebuildBossCardGrid(String npcName, List<BossKillStats.AggregatedDrop> drops)
    {
        JPanel card = bossCardMap.get(npcName);
        if (card == null) { invalidateFingerprint(); refreshDisplay(); return; }

        itemSlotMap.keySet().removeIf(k -> k.startsWith(npcName + "_"));

        JPanel newGrid = buildItemGrid(drops, npcName);
        JPanel gridWrapper = new JPanel(new BorderLayout());
        gridWrapper.setBackground(new Color(28, 28, 28));
        gridWrapper.add(newGrid, BorderLayout.NORTH);
        gridWrapper.setVisible(bossExpandedState.getOrDefault(npcName, true));

        card.add(gridWrapper, BorderLayout.CENTER);
        card.revalidate();
        card.repaint();
        bossListPanel.revalidate();
        bossListPanel.repaint();
    }

    public void refreshDisplay()
    {
        if (SwingUtilities.isEventDispatchThread())
            refreshDebounce.restart();
        else
            SwingUtilities.invokeLater(refreshDebounce::restart);
    }

    private void executeRefresh()
    {
        if (!refreshing.compareAndSet(false, true)) return;

        refreshPool.submit(() ->
        {
            try
            {
                List<BossKillStats> allStats = lootManager.getAllBossStats();

                String fp = buildDisplayFingerprint(allStats, highlightedBoss);
                if (fp.equals(lastDisplayFingerprint))
                {
                    refreshing.set(false);
                    return;
                }
                lastDisplayFingerprint = fp;

                Map<String, BossKillStats> unique = new LinkedHashMap<>();
                for (BossKillStats s : allStats) unique.putIfAbsent(s.getNpcName(), s);

                List<BossKillStats> sorted = new ArrayList<>();
                for (BossKillStats s : unique.values())
                    if (passesFilter(s.getNpcName())) sorted.add(s);

                sortStats(sorted);

                final long totalVal   = sorted.stream().mapToLong(BossKillStats::getTotalLootValue).sum();
                final int  totalKills = sorted.stream().mapToInt(BossKillStats::getKillCount).sum();

                SwingUtilities.invokeLater(() ->
                {
                    try
                    {
                        int savedScroll = scrollPane.getVerticalScrollBar().getValue();
                        bossListPanel.removeAll();
                        itemSlotMap.clear();
                        bossCardMap.clear();
                        bossValueLabelMap.clear();
                        bossNameLabelMap.clear();
                        lootDebounceMap.values().forEach(javax.swing.Timer::stop);
                        lootDebounceMap.clear();

                        if (sorted.isEmpty())
                        {
                            bossListPanel.add(buildEmptyState());
                        }
                        else
                        {
                            for (BossKillStats stats : sorted)
                            {
                                bossListPanel.add(buildBossCard(stats));
                                bossListPanel.add(Box.createVerticalStrut(5));
                            }
                            totalKillsLabel.setText(formatNumber(totalKills) + " kills");
                            totalValueLabel.setText(formatGp(totalVal));
                        }

                        bossListPanel.revalidate();
                        bossListPanel.repaint();
                        scrollPane.revalidate();
                        scrollPane.getVerticalScrollBar().setValue(savedScroll);
                    }
                    catch (Exception ex)
                    {
                        log.error("Refresh EDT rebuild failed", ex);
                    }
                    finally
                    {
                        refreshing.set(false);
                    }
                });
            }
            catch (Exception e)
            {
                log.error("Refresh background thread failed", e);
                refreshing.set(false);
            }
        });
    }

    private boolean passesFilter(String npcName)
    {
        boolean isSkilling   = lootManager.isSkillingSource(npcName);
        boolean isPickpocket = lootManager.isPickpocketSource(npcName);
        switch (currentFilter)
        {
            case COMBAT:
                return !isSkilling && !isPickpocket;
            case SKILLS:
                if (currentSkillFilter == null)
                    return isSkilling || isPickpocket;
                if ("Thieving".equals(currentSkillFilter))
                    return isPickpocket;
                return npcName.equals(LootTrackerManager.SKILLING_PREFIX + currentSkillFilter);
            default:
                return true;
        }
    }

    private String buildDisplayFingerprint(List<BossKillStats> stats, String highlight)
    {
        StringBuilder sb = new StringBuilder(stats.size() * 40 + 32);
        sb.append(highlight == null ? "" : highlight)
                .append('|').append(currentSort.name())
                .append('|').append(currentFilter.name())
                .append('|').append(currentSkillFilter == null ? "" : currentSkillFilter)
                .append('|').append(showIgnoredItems).append('|');

        List<BossKillStats> copy = new ArrayList<>(stats);
        sortStats(copy);
        for (BossKillStats s : copy)
        {
            if (!passesFilter(s.getNpcName())) continue;
            sb.append(s.getNpcName())
                    .append(':').append(s.getKillCount())
                    .append(':').append(s.getTotalLootValue())
                    .append(':').append(s.getLastKillTimestamp())
                    .append(';');
        }
        return sb.toString();
    }

    private void invalidateFingerprint() { lastDisplayFingerprint = null; }

    private String buildNameLabel(String npcName, int killCount)
    {
        boolean ip = lootManager.isPickpocketSource(npcName);
        boolean is = lootManager.isSkillingSource(npcName);
        String  ic = ip ? "👜" : (is ? "🌿" : "⚔");
        String  dn = ip ? lootManager.stripPickpocketPrefix(npcName)
                : is ? lootManager.stripSkillingPrefix(npcName)
                : npcName;
        return ic + " " + truncate(dn, 18) + " × " + String.format("%,d", killCount);
    }

    private JPanel buildBossCard(BossKillStats stats)
    {
        final String npcName = stats.getNpcName();
        boolean ip = lootManager.isPickpocketSource(npcName);
        boolean is = lootManager.isSkillingSource(npcName);
        boolean hl = npcName.equals(highlightedBoss);

        List<BossKillStats.AggregatedDrop> drops = lootManager.getStorageDropsForBoss(npcName);
        long totalValue = drops.stream().mapToLong(BossKillStats.AggregatedDrop::getTotalValue).sum();
        if (totalValue <= 0) totalValue = stats.getTotalLootValue();

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(new Color(28, 28, 28));
        card.setBorder(new EmptyBorder(0, 0, 0, 0));

        Color borderCol = hl ? (ip ? new Color(160, 80, 255) : is ? new Color(60, 180, 80) : new Color(0, 180, 60))
                : new Color(50, 50, 50);

        JPanel container = new JPanel(new BorderLayout());
        container.setBorder(BorderFactory.createLineBorder(borderCol, hl ? 2 : 1));
        container.setOpaque(false);

        Color headerBg = hl ? (ip ? PICKPOCKET_HEADER_HL : is ? SKILLING_HEADER_HL : new Color(20, 35, 20))
                : (ip ? PICKPOCKET_HEADER_BASE : is ? SKILLING_HEADER_BASE : new Color(36, 36, 36));

        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setBackground(headerBg);
        headerRow.setPreferredSize(new Dimension(0, 28));
        headerRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        Color nameCol = hl ? (ip ? new Color(200, 160, 255) : is ? new Color(140, 230, 140) : new Color(100, 255, 100))
                : new Color(210, 210, 210);

        JLabel nameLabel = new JLabel(buildNameLabel(npcName, stats.getKillCount()));
        nameLabel.setForeground(nameCol);
        nameLabel.setFont(CALIBRI_BOLD);
        nameLabel.setBorder(new EmptyBorder(0, 8, 0, 4));

        JLabel valueLabel = new JLabel(totalValue > 0 ? formatGp(totalValue) : "");
        valueLabel.setForeground(new Color(200, 180, 80));
        valueLabel.setFont(CALIBRI_PLAIN);
        valueLabel.setBorder(new EmptyBorder(0, 4, 0, 8));

        headerRow.add(nameLabel,  BorderLayout.WEST);
        headerRow.add(valueLabel, BorderLayout.EAST);

        bossNameLabelMap.put(npcName,  nameLabel);
        bossValueLabelMap.put(npcName, valueLabel);

        JPanel grid = buildItemGrid(drops, npcName);
        JPanel gridWrapper = new JPanel(new BorderLayout());
        gridWrapper.setBackground(new Color(28, 28, 28));
        gridWrapper.add(grid, BorderLayout.NORTH);
        gridWrapper.setVisible(bossExpandedState.getOrDefault(npcName, true));

        headerRow.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                boolean nowVisible = !gridWrapper.isVisible();
                gridWrapper.setVisible(nowVisible);
                bossExpandedState.put(npcName, nowVisible);
                container.revalidate();
                container.repaint();
                SwingUtilities.invokeLater(() -> { bossListPanel.revalidate(); bossListPanel.repaint(); });
            }
        });

        container.add(headerRow,   BorderLayout.NORTH);
        container.add(gridWrapper, BorderLayout.CENTER);
        card.add(container, BorderLayout.CENTER);

        bossCardMap.put(npcName, card);
        return card;
    }

    private JPanel buildItemGrid(List<BossKillStats.AggregatedDrop> drops, String npcName)
    {
        List<BossKillStats.AggregatedDrop> visible = new ArrayList<>();
        for (BossKillStats.AggregatedDrop d : drops)
            if (!lootManager.isDropHidden(npcName, d.getItemId()) || showIgnoredItems)
                visible.add(d);

        JPanel grid = new JPanel(new GridLayout(0, ITEMS_PER_ROW, ITEM_GAP, ITEM_GAP));
        grid.setBackground(new Color(28, 28, 28));
        grid.setBorder(new EmptyBorder(4, 4, 4, 4));

        if (visible.isEmpty())
        {
            grid.setLayout(new FlowLayout(FlowLayout.LEFT));
            JLabel none = new JLabel("No drops recorded yet");
            none.setForeground(new Color(80, 80, 80));
            none.setFont(CALIBRI_PLAIN);
            grid.add(none);
            return grid;
        }

        for (BossKillStats.AggregatedDrop drop : visible)
        {
            boolean hidden  = lootManager.isDropHidden(npcName, drop.getItemId());
            String  tooltip = buildTooltip(drop);
            String  slotKey = npcName + "_" + drop.getItemId();

            JPanel slot = new JPanel(new BorderLayout());
            slot.setOpaque(true);
            slot.setBackground(hidden ? new Color(50, 28, 28) : new Color(35, 35, 35));
            slot.setBorder(BorderFactory.createLineBorder(new Color(58, 58, 58), 1));
            slot.setPreferredSize(new Dimension(ITEM_SIZE, ITEM_SIZE));
            slot.setToolTipText(tooltip);

            JLabel iconLabel = new JLabel();
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iconLabel.setVerticalAlignment(SwingConstants.CENTER);
            iconLabel.setToolTipText(tooltip);

            itemManager.getImage(drop.getItemId(), drop.getTotalQuantity(), drop.getTotalQuantity() > 1)
                    .addTo(iconLabel);

            slot.add(iconLabel, BorderLayout.CENTER);
            itemSlotMap.put(slotKey, iconLabel);

            JPopupMenu menu = new JPopupMenu();
            JMenuItem toggleHide = new JMenuItem(hidden ? "Un-ignore" : "Ignore");
            toggleHide.addActionListener(e -> {
                if (hidden) lootManager.unhideDropForNpc(npcName, drop.getItemId());
                else        lootManager.hideDropForNpc  (npcName, drop.getItemId());
                invalidateFingerprint();
                refreshDisplay();
            });
            menu.add(toggleHide);
            slot.addMouseListener(new MouseAdapter()
            {
                @Override public void mousePressed (MouseEvent e) { if (e.isPopupTrigger()) menu.show(e.getComponent(), e.getX(), e.getY()); }
                @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) menu.show(e.getComponent(), e.getX(), e.getY()); }
            });

            grid.add(slot);
        }

        int rem = (ITEMS_PER_ROW - (visible.size() % ITEMS_PER_ROW)) % ITEMS_PER_ROW;
        for (int i = 0; i < rem; i++) { JPanel p = new JPanel(); p.setOpaque(false); grid.add(p); }

        return grid;
    }

    private JPanel buildEmptyState()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(new Color(28, 28, 28));
        p.setBorder(new EmptyBorder(40, 20, 40, 20));
        p.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel skull = new JLabel("💀");
        skull.setFont(new Font("Dialog", Font.PLAIN, 42));
        skull.setAlignmentX(Component.CENTER_ALIGNMENT);

        String msg = currentFilter == SourceFilter.SKILLS && currentSkillFilter != null
                ? "No " + currentSkillFilter + " data yet"
                : currentFilter == SourceFilter.COMBAT ? "No combat drops yet"
                : "No loot tracked yet";

        JLabel msgLabel = new JLabel(msg);
        msgLabel.setForeground(new Color(120, 120, 120));
        msgLabel.setFont(new Font("Calibri", Font.PLAIN, 11));
        msgLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        p.add(skull);
        p.add(Box.createVerticalStrut(8));
        p.add(msgLabel);

        totalKillsLabel.setText("0 kills");
        totalValueLabel.setText("0 gp total");
        return p;
    }

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

    private void restoreButtonColor(JButton btn)
    {
        if (btn == eyeButton)
            btn.setBackground(showIgnoredItems ? new Color(160, 80, 0) : new Color(38, 38, 38));
        else if (btn == sortButton)
        {
            switch (currentSort)
            {
                case RECENT: btn.setBackground(new Color(45, 70, 45)); break;
                case VALUE:  btn.setBackground(new Color(70, 45, 45)); break;
                case KILLS:  btn.setBackground(new Color(45, 45, 70)); break;
            }
        }
        else btn.setBackground(new Color(38, 38, 38));
    }

    private String truncate(String s, int maxLen)
    {
        if (s == null || s.length() <= maxLen) return s;
        int cut = s.lastIndexOf(' ', maxLen - 1);
        return (cut > maxLen / 2 ? s.substring(0, cut) : s.substring(0, maxLen - 1)) + "…";
    }

    private String formatGp(long v)
    {
        if (v >= 1_000_000_000) return String.format("%.2fB", v / 1e9);
        if (v >= 1_000_000)     return String.format("%.1fM", v / 1e6);
        if (v >= 1_000)         return String.format("%.1fK", v / 1e3);
        return String.valueOf(v);
    }

    private String formatNumber(long n)
    {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1e9);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1e6);
        if (n >= 1_000)         return String.format("%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    private String buildTooltip(BossKillStats.AggregatedDrop drop)
    {
        return "<html>"
                + "<b>" + drop.getItemName() + "</b><br>"
                + "Dropped: <b>" + drop.getDropCount() + "×</b><br>"
                + "Qty: "      + QuantityFormatter.formatNumber((int) drop.getTotalQuantity()) + "<br>"
                + "GE: "       + formatGp(drop.getGePrice()) + " ea<br>"
                + "Total: <b>" + formatGp(drop.getTotalValue()) + "</b><br>"
                + "Alch: "     + formatGp(drop.getHighAlchValue()) + " ea"
                + "</html>";
    }
}