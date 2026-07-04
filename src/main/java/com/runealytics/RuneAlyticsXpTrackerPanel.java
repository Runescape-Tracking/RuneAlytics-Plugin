package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The RuneAlytics "XP" side-panel tab.
 *
 * <p>Shows the current session's XP: an "earned today" banner, a summary card
 * (runtime, total XP, XP/hr, levels gained), an XP/hr trend sparkline (last
 * hour), and a per-skill list with progress bars and time-to-level. Clicking a
 * skill opens {@link RuneAlyticsXpSkillDetailPanel}; right-clicking a skill
 * offers per-skill resets. All data is read from
 * {@link RuneAlyticsXpSessionManager}; the view is refreshed once per second by
 * a Swing timer that only runs while the tab is attached, and reuses skill rows
 * so a live-updating session does not thrash the layout.</p>
 */
@Slf4j
@Singleton
public class RuneAlyticsXpTrackerPanel extends PluginPanel
{
    // ── Shared theme (referenced by the row / detail components) ──────────────
    // Base background matches the other RuneAlytics tabs (Loot Tracker etc.).
    static final Color NAVY_BG     = ColorScheme.DARK_GRAY_COLOR;
    static final Color CARD_BG     = new Color(26, 31, 46);
    static final Color CARD_BORDER = new Color(44, 52, 74);
    static final Color CELL_BG     = new Color(30, 36, 52);
    static final Color GOLD        = new Color(214, 178, 64);
    static final Color TEAL        = new Color(82, 196, 196);
    static final Color XP_BLUE     = new Color(108, 140, 244);
    static final Color XP_GREEN    = new Color(105, 220, 140);
    static final Color TEXT        = new Color(228, 232, 240);
    static final Color MUTED       = new Color(150, 158, 178);

    private static final Font SECTION_FONT  = new Font("Calibri", Font.BOLD, 13);
    private static final Font CELL_LBL_FONT = new Font("Calibri", Font.PLAIN, 11);
    private static final Font CELL_VAL_FONT = new Font("Calibri", Font.BOLD, 16);

    private static final int PAD = 6;

    private static final String CARD_MAIN   = "main";
    private static final String CARD_DETAIL = "detail";

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final RuneAlyticsXpSessionManager sessionManager;
    private final RunealyticsConfig           config;
    private final RuneAlyticsState            state;
    private final SkillIconManager            iconManager;

    /** Set after construction; used to trigger website sync. */
    private volatile RuneAlyticsPlugin plugin;

    // ── Swing structure ───────────────────────────────────────────────────────
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new ScrollableView(cards);
    private final RuneAlyticsXpSkillDetailPanel detailPanel;

    private final JLabel accountLabel = new JLabel();
    private final JLabel syncBadge    = new JLabel();

    private final JLabel todayVal   = new JLabel("0 xp");
    private final JLabel runtimeVal = new JLabel("00:00:00");
    private final JLabel totalVal   = new JLabel("0");
    private final JLabel rateVal    = new JLabel("0");
    private final JLabel levelsVal  = new JLabel("0");

    // Session-summary "featured skill" tag (icon + name at top-right of the card).
    private final JLabel summarySkillName = new JLabel();
    private final JLabel summarySkillIcon = new JLabel();
    private Skill lastFeatured = null;

    private final XpSparkline overallSparkline = new XpSparkline();
    private JPanel chartCardHolder;
    private final JPanel chartCard;

    private final JPanel skillListPanel = new JPanel();
    private final JLabel emptyLabel     = new JLabel();
    private final Map<Skill, RuneAlyticsXpSkillRow> rows = new LinkedHashMap<>();
    private List<Skill> lastOrder = new ArrayList<>();

    private JButton syncButton;
    private JButton eyeButton;
    private boolean showHidden = false;

    private Timer refreshTimer;
    private long syncMessageUntil = 0L;

    @Inject
    public RuneAlyticsXpTrackerPanel(RuneAlyticsXpSessionManager sessionManager,
                                     RunealyticsConfig config,
                                     RuneAlyticsState state,
                                     SkillIconManager iconManager)
    {
        super(false);
        this.sessionManager = sessionManager;
        this.config         = config;
        this.state          = state;
        this.iconManager    = iconManager;

        setLayout(new BorderLayout());
        setBackground(NAVY_BG);

        JPanel main = buildMainView();
        chartCard = chartCardHolder; // populated by buildMainView()

        detailPanel = new RuneAlyticsXpSkillDetailPanel(iconManager, config,
                this::showMain, this::onResetSkill);

        cardPanel.setBackground(NAVY_BG);
        cardPanel.add(main, CARD_MAIN);
        cardPanel.add(detailPanel, CARD_DETAIL);

        // ALWAYS-on 8px scrollbar keeps the content width constant, so the layout
        // never shifts horizontally when content grows past the viewport height.
        // The ScrollableView forces the content to the viewport width, so nothing
        // is ever clipped on the right and the scrollbar never overlaps content.
        JScrollPane scroll = new JScrollPane(cardPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scroll.getViewport().setBackground(NAVY_BG);
        scroll.setBackground(NAVY_BG);

        add(scroll, BorderLayout.CENTER);
    }

    /**
     * Scroll content that is forced to the viewport's width (never wider), so the
     * design reflows within the available space and is never clipped by the
     * always-on vertical scrollbar.
     */
    private static final class ScrollableView extends JPanel implements Scrollable
    {
        ScrollableView(LayoutManager layout)
        {
            super(layout);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(16, r.height - 16); }
        @Override public boolean getScrollableTracksViewportWidth()  { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private JPanel buildMainView()
    {
        JPanel view = new JPanel();
        view.setLayout(new BoxLayout(view, BoxLayout.Y_AXIS));
        view.setBackground(NAVY_BG);
        view.setOpaque(true);
        view.setBorder(new EmptyBorder(PAD, PAD, PAD, PAD));

        // Shared branding header (logo + tagline + tab name), like every other tab.
        JComponent branding = RuneAlyticsUi.buildPanelHeader("XP Tracker");
        branding.setAlignmentX(Component.LEFT_ALIGNMENT);
        view.add(branding);
        view.add(Box.createRigidArea(new Dimension(0, 6)));

        JSeparator headerSep = new JSeparator();
        headerSep.setForeground(new Color(55, 55, 55));
        headerSep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        headerSep.setAlignmentX(Component.LEFT_ALIGNMENT);
        view.add(headerSep);
        view.add(Box.createRigidArea(new Dimension(0, 8)));

        // Account + sync badge row
        JPanel acctRow = new JPanel(new BorderLayout());
        acctRow.setOpaque(false);
        acctRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        acctRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        accountLabel.setFont(new Font("Calibri", Font.BOLD, 12));
        accountLabel.setForeground(TEXT);
        syncBadge.setFont(new Font("Calibri", Font.BOLD, 10));
        syncBadge.setHorizontalAlignment(SwingConstants.RIGHT);
        syncBadge.setBorder(new EmptyBorder(2, 6, 2, 6));
        acctRow.add(accountLabel, BorderLayout.WEST);
        acctRow.add(syncBadge,    BorderLayout.EAST);
        view.add(acctRow);
        view.add(Box.createRigidArea(new Dimension(0, 8)));

        // XP earned today (top highlight)
        JPanel today = card();
        today.setBackground(new Color(34, 28, 16));
        today.setBorder(new CompoundBorder(
                new LineBorder(new Color(96, 78, 34), 1, true),
                new EmptyBorder(6, 8, 6, 8)));
        JPanel todayInner = new JPanel(new BorderLayout());
        todayInner.setOpaque(false);
        todayInner.setAlignmentX(Component.LEFT_ALIGNMENT);
        todayInner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        JLabel todayCap = new JLabel("XP EARNED TODAY");
        todayCap.setFont(new Font("Calibri", Font.BOLD, 12));
        todayCap.setForeground(MUTED);
        todayVal.setFont(new Font("Calibri", Font.BOLD, 15));
        todayVal.setForeground(GOLD);
        todayVal.setHorizontalAlignment(SwingConstants.RIGHT);
        todayInner.add(todayCap, BorderLayout.WEST);
        todayInner.add(todayVal, BorderLayout.EAST);
        today.add(todayInner);
        view.add(today);
        view.add(Box.createRigidArea(new Dimension(0, 8)));

        // Summary card — focused on the featured (highest-output live) skill.
        JPanel summary = card();
        JPanel sumHeader = new JPanel(new BorderLayout());
        sumHeader.setOpaque(false);
        sumHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        sumHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        sumHeader.add(sectionLabel("SESSION SUMMARY"), BorderLayout.WEST);
        JPanel skillTag = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        skillTag.setOpaque(false);
        summarySkillName.setFont(new Font("Calibri", Font.BOLD, 12));
        summarySkillName.setForeground(MUTED);
        skillTag.add(summarySkillName);
        skillTag.add(summarySkillIcon);
        sumHeader.add(skillTag, BorderLayout.EAST);
        summary.add(sumHeader);
        summary.add(Box.createRigidArea(new Dimension(0, 6)));
        JPanel grid = new JPanel(new GridLayout(2, 2, 6, 6));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.add(statCell("SESSION RUNTIME", runtimeVal, TEAL));
        grid.add(statCell("XP GAINED",       totalVal,   GOLD));
        grid.add(statCell("XP / HOUR",       rateVal,    XP_GREEN));
        grid.add(statCell("LEVELS GAINED",   levelsVal,  XP_BLUE));
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        summary.add(grid);
        view.add(summary);
        view.add(Box.createRigidArea(new Dimension(0, 8)));

        // Controls: sync + reset
        JPanel controls = new JPanel(new BorderLayout(6, 0));
        controls.setOpaque(false);
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        syncButton = accentButton("⟳ Sync Session", new Color(30, 50, 80), new Color(80, 120, 180));
        syncButton.addActionListener(e -> onSyncClicked());
        JButton resetButton = accentButton("Reset Session", new Color(48, 30, 34), new Color(110, 60, 64));
        resetButton.setForeground(new Color(255, 160, 160));
        resetButton.addActionListener(e -> onResetSession());
        controls.add(syncButton,  BorderLayout.CENTER);
        controls.add(resetButton, BorderLayout.EAST);
        view.add(controls);
        view.add(Box.createRigidArea(new Dimension(0, 8)));

        // Chart card (XP/hr over the last hour)
        JPanel chart = card();
        chart.add(sectionLabel("XP / HOUR"));
        chart.add(Box.createRigidArea(new Dimension(0, 6)));
        overallSparkline.setAlignmentX(Component.LEFT_ALIGNMENT);
        chart.add(overallSparkline);
        chartCardHolder = chart;
        view.add(chart);
        view.add(Box.createRigidArea(new Dimension(0, 8)));

        // Skills section header with a "reveal hidden" toggle (like the Loot Tracker eye).
        JPanel skillsHeader = new JPanel(new BorderLayout());
        skillsHeader.setOpaque(false);
        skillsHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        skillsHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        skillsHeader.add(sectionLabel("SKILLS"), BorderLayout.WEST);
        eyeButton = new JButton("Show hidden");
        eyeButton.setFont(new Font("Calibri", Font.BOLD, 10));
        eyeButton.setForeground(MUTED);
        eyeButton.setBackground(CELL_BG);
        eyeButton.setFocusPainted(false);
        eyeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        eyeButton.setBorder(new CompoundBorder(new LineBorder(CARD_BORDER, 1, true), new EmptyBorder(2, 8, 2, 8)));
        eyeButton.addActionListener(e -> onToggleShowHidden());
        skillsHeader.add(eyeButton, BorderLayout.EAST);
        view.add(skillsHeader);
        view.add(Box.createRigidArea(new Dimension(0, 4)));
        skillListPanel.setLayout(new BoxLayout(skillListPanel, BoxLayout.Y_AXIS));
        skillListPanel.setOpaque(false);
        skillListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        view.add(skillListPanel);

        emptyLabel.setFont(new Font("Calibri", Font.PLAIN, 12));
        emptyLabel.setForeground(MUTED);
        emptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        emptyLabel.setText("No XP gained yet this session.");
        view.add(emptyLabel);

        // Wrap in BorderLayout.NORTH so the content keeps its preferred height and
        // never stretches to fill the tall scroll viewport (matches Loot Tracker).
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(NAVY_BG);
        wrap.setOpaque(true);
        wrap.add(view, BorderLayout.NORTH);
        return wrap;
    }

    // ── Lifecycle: run the refresh timer only while attached ──────────────────

    @Override
    public void addNotify()
    {
        super.addNotify();
        if (refreshTimer == null)
        {
            refreshTimer = new Timer(1000, e -> refresh());
            refreshTimer.setRepeats(true);
        }
        refreshTimer.start();
        refresh();
    }

    @Override
    public void removeNotify()
    {
        if (refreshTimer != null) refreshTimer.stop();
        super.removeNotify();
    }

    void setPlugin(RuneAlyticsPlugin plugin)
    {
        this.plugin = plugin;
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /** Recomputes all displayed values. Must run on the EDT. */
    public void refresh()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }

        long now = System.currentTimeMillis();
        sessionManager.sampleRates(now);
        long activeNow = sessionManager.activeElapsed(now);
        long liveWindow = Math.max(1, config.xpAfkTimeout()) * 60_000L;

        // Account + sync badge
        String acct = sessionManager.getSessionAccountKey();
        accountLabel.setText(acct != null ? "Account: " + prettyAccount(acct) : "Not logged in");
        updateSyncBadge(now);

        // Compact mode toggles the chart card off.
        chartCard.setVisible(!config.xpCompactMode());

        // "Today" total is always shown (all skills).
        todayVal.setText(XpFormat.comma(sessionManager.todayXpGained()) + " xp");

        if (!config.enableXpTracker())
        {
            emptyLabel.setText("XP Tracker is disabled in RuneAlytics settings.");
            emptyLabel.setVisible(true);
            clearRows();
        }

        // Summary — runtime is session-wide; XP gained / XP-hr / levels + the trend
        // chart are the single featured skill (favorite-if-live, else highest-output
        // live skill). updateFeaturedSummary also repoints the chart.
        runtimeVal.setText(XpFormat.duration(sessionManager.runtimeMs(now)));
        updateFeaturedSummary(now, activeNow, liveWindow);

        // Eye toggle: only meaningful when something is hidden (or we're revealing).
        int hiddenCount = sessionManager.hiddenCount();
        eyeButton.setVisible(hiddenCount > 0 || showHidden);
        eyeButton.setText((showHidden ? "Hide hidden" : "Show hidden")
                + (hiddenCount > 0 ? " (" + hiddenCount + ")" : ""));
        eyeButton.setForeground(showHidden ? XP_GREEN : MUTED);

        // Detail view, if showing
        if (detailPanel.getSkill() != null && isDetailShowing())
        {
            RuneAlyticsXpSkillState st = sessionManager.getState(detailPanel.getSkill());
            if (st == null) showMain();
            else            detailPanel.update(st, now, activeNow);
        }

        // Skill rows
        if (config.enableXpTracker())
        {
            updateSkillRows(now, activeNow);
        }
    }

    private void updateSkillRows(long now, long activeNow)
    {
        List<RuneAlyticsXpSkillState> states =
                sessionManager.snapshotStates(config.xpShowAllSkills(), showHidden);
        long liveWindow = Math.max(1, config.xpAfkTimeout()) * 60_000L;

        List<Skill> order = new ArrayList<>(states.size());
        for (RuneAlyticsXpSkillState st : states) order.add(st.getSkill());

        emptyLabel.setVisible(states.isEmpty());

        if (!order.equals(lastOrder))
        {
            // Membership / order changed — rebuild the list (rows are reused).
            lastOrder = order;
            skillListPanel.removeAll();
            for (RuneAlyticsXpSkillState st : states)
            {
                RuneAlyticsXpSkillRow row = rows.computeIfAbsent(st.getSkill(), s ->
                        new RuneAlyticsXpSkillRow(s, iconManager, config,
                                this::showSkillDetail, this::onResetSkillRate,
                                this::onResetSkill, this::onToggleHide, this::onToggleFavorite));
                skillListPanel.add(row);
                skillListPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
            skillListPanel.revalidate();
            skillListPanel.repaint();
        }

        for (RuneAlyticsXpSkillState st : states)
        {
            RuneAlyticsXpSkillRow row = rows.get(st.getSkill());
            if (row == null) continue;
            // "LIVE" = still gaining XP; drops off once idle past the AFK window
            // (real wall time, so it also clears while logged out).
            boolean live = st.hasGains() && st.getLastGainWallMs() > 0
                    && (now - st.getLastGainWallMs()) < liveWindow;
            row.update(st, activeNow, live, sessionManager.isHidden(st.getSkill()),
                    sessionManager.isFavorite(st.getSkill()));
        }
    }

    private void clearRows()
    {
        if (!lastOrder.isEmpty())
        {
            lastOrder = new ArrayList<>();
            skillListPanel.removeAll();
            skillListPanel.revalidate();
            skillListPanel.repaint();
        }
    }

    /**
     * Points the session summary at a single skill: the favorite that's live, else
     * the highest-output live skill, else the most-recent. XP gained / XP-hr /
     * levels reflect that skill; XP/hr is tinted to the skill's colour, and its
     * icon + name appear top-right.
     */
    private void updateFeaturedSummary(long now, long activeNow, long liveWindow)
    {
        Skill featured = sessionManager.featuredSkill(now, liveWindow);
        RuneAlyticsXpSkillState fs = (featured != null) ? sessionManager.getState(featured) : null;

        if (fs == null)
        {
            if (lastFeatured != null) { summarySkillName.setText(""); summarySkillIcon.setIcon(null); lastFeatured = null; }
            totalVal.setText("0");
            rateVal.setText(config.xpShowPerHour() ? "0" : "—");
            rateVal.setForeground(XP_GREEN);
            levelsVal.setText("0");
            // No featured skill yet — show the combined trend in the default colour.
            overallSparkline.setLineColor(null);
            overallSparkline.setSamples(sessionManager.overallRateHistorySnapshot());
            return;
        }

        Color c = SkillColors.of(featured);

        // Chart follows the featured skill: its own XP/hr history, in its colour.
        overallSparkline.setLineColor(c);
        overallSparkline.setSamples(fs.rateHistorySnapshot());

        // Only touch the icon/name when the featured skill actually changes.
        if (featured != lastFeatured)
        {
            lastFeatured = featured;
            summarySkillName.setText(RuneAlyticsXpSkillRow.prettyName(featured));
            summarySkillName.setForeground(c);
            try
            {
                java.awt.image.BufferedImage img = iconManager.getSkillImage(featured, true);
                summarySkillIcon.setIcon(img != null ? new javax.swing.ImageIcon(img) : null);
            }
            catch (Exception ignored)
            {
                summarySkillIcon.setIcon(null);
            }
        }

        boolean ignoreAfk = config.xpIgnoreAfk();
        long afk = liveWindow; // AFK timeout == live window (both are xpAfkTimeout minutes)

        totalVal.setText(XpFormat.comma(fs.getTotalGained()));
        rateVal.setText(config.xpShowPerHour()
                ? XpFormat.compactUpper(fs.xpPerHour(activeNow, ignoreAfk, afk)) : "—");
        rateVal.setForeground(c); // XP/hr coloured to the featured skill

        int start = Math.min(fs.getStartLevel(), 99);
        int cur   = Math.min(fs.getCurrentLevel(), 99);
        levelsVal.setText(Integer.toString(Math.max(0, cur - start)));
    }

    private void updateSyncBadge(long now)
    {
        if (now < syncMessageUntil) return; // keep a transient message visible

        String text;
        Color fg;
        if (!state.isLoggedIn())
        {
            text = "OFFLINE"; fg = MUTED;
        }
        else if (!state.isVerified())
        {
            text = "NOT LINKED"; fg = GOLD;
        }
        else if (state.isSyncInProgress())
        {
            text = "SYNCING…"; fg = TEAL;
        }
        else if (config.xpAutoSync())
        {
            text = "AUTO-SYNC ON"; fg = XP_GREEN;
        }
        else
        {
            text = "LINKED"; fg = XP_GREEN;
        }
        syncBadge.setText(text);
        syncBadge.setForeground(fg);
    }

    /** Shows a short-lived status message in the sync badge (called from the plugin). */
    public void showSyncMessage(String message, boolean positive)
    {
        SwingUtilities.invokeLater(() ->
        {
            syncMessageUntil = System.currentTimeMillis() + 3000L;
            syncBadge.setText(message.toUpperCase());
            syncBadge.setForeground(positive ? XP_GREEN : new Color(255, 140, 140));
        });
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void showSkillDetail(Skill skill)
    {
        RuneAlyticsXpSkillState st = sessionManager.getState(skill);
        if (st == null) return;
        long now = System.currentTimeMillis();
        detailPanel.showSkill(skill);
        detailPanel.update(st, now, sessionManager.activeElapsed(now));
        cards.show(cardPanel, CARD_DETAIL);
    }

    private void showMain()
    {
        cards.show(cardPanel, CARD_MAIN);
    }

    private boolean isDetailShowing()
    {
        return detailPanel.isVisible() && detailPanel.getParent() != null
                && detailPanel.getWidth() > 0;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void onSyncClicked()
    {
        RuneAlyticsPlugin p = plugin;
        if (p != null) p.syncXpSession(true);
    }

    private void onResetSession()
    {
        // Flush the finished session's XP/hr to the website BEFORE clearing it, so
        // clearing a session records its data for analysis (ended = true).
        RuneAlyticsPlugin p = plugin;
        if (p != null) p.syncXpSession(false, true);
        sessionManager.reset();
        showMain();
        refresh();
    }

    private void onResetSkill(Skill skill)
    {
        sessionManager.resetSkill(skill);
        rows.remove(skill);
        showMain();
        refresh();
    }

    private void onResetSkillRate(Skill skill)
    {
        sessionManager.resetSkillRate(skill);
        refresh();
    }

    private void onToggleShowHidden()
    {
        showHidden = !showHidden;
        lastOrder = new ArrayList<>(); // force list rebuild (membership changes)
        refresh();
    }

    private void onToggleHide(Skill skill)
    {
        sessionManager.setHidden(skill, !sessionManager.isHidden(skill));
        rows.remove(skill);
        lastOrder = new ArrayList<>(); // force rebuild so the row appears/disappears
        showMain();
        refresh();
    }

    private void onToggleFavorite(Skill skill)
    {
        sessionManager.setFavorite(skill, !sessionManager.isFavorite(skill));
        refresh();
    }

    // ── Small builders ────────────────────────────────────────────────────────

    private JPanel card()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(CARD_BG);
        p.setOpaque(true);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER, 1, true),
                new EmptyBorder(8, 8, 8, 8)));
        return p;
    }

    /** A summary stat cell with its caption + value vertically & horizontally centered. */
    private JComponent statCell(String caption, JLabel valueLabel, Color valueColor)
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(CELL_BG);
        p.setOpaque(true);
        p.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER, 1, true),
                new EmptyBorder(8, 6, 8, 6)));

        JLabel cap = new JLabel(caption);
        cap.setFont(CELL_LBL_FONT);
        cap.setForeground(MUTED);
        cap.setAlignmentX(Component.CENTER_ALIGNMENT);
        cap.setHorizontalAlignment(SwingConstants.CENTER);

        valueLabel.setFont(CELL_VAL_FONT);
        valueLabel.setForeground(valueColor);
        valueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        valueLabel.setHorizontalAlignment(SwingConstants.CENTER);

        p.add(Box.createVerticalGlue());
        p.add(cap);
        p.add(Box.createRigidArea(new Dimension(0, 4)));
        p.add(valueLabel);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JLabel sectionLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(SECTION_FONT);
        l.setForeground(GOLD);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JButton accentButton(String text, Color bg, Color border)
    {
        JButton b = new JButton(text);
        b.setFont(new Font("Calibri", Font.BOLD, 12));
        b.setForeground(Color.WHITE);
        b.setBackground(bg);
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(new CompoundBorder(new LineBorder(border, 1, true), new EmptyBorder(5, 8, 5, 8)));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return b;
    }

    private static String prettyAccount(String normalized)
    {
        // Normalized keys are lowercase; title-case each word for display.
        String[] parts = normalized.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String part : parts)
        {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : normalized;
    }
}
