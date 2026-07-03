package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
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
import javax.swing.ScrollPaneConstants;
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
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The RuneAlytics "XP" side-panel tab.
 *
 * <p>Shows the current session's XP: a summary card (runtime, total XP, XP/hr,
 * levels gained), a trend sparkline, and a per-skill list with progress bars.
 * Clicking a skill opens {@link RuneAlyticsXpSkillDetailPanel}. All data is read
 * from {@link RuneAlyticsXpSessionManager}; the view is refreshed once per second
 * by a Swing timer that only runs while the tab is attached, and reuses skill
 * rows so a live-updating session does not thrash the layout.</p>
 */
@Slf4j
@Singleton
public class RuneAlyticsXpTrackerPanel extends PluginPanel
{
    // ── Shared theme (referenced by the row / detail components) ──────────────
    static final Color NAVY_BG     = new Color(16, 20, 32);
    static final Color CARD_BG     = new Color(26, 31, 46);
    static final Color CARD_BORDER = new Color(44, 52, 74);
    static final Color GOLD        = new Color(214, 178, 64);
    static final Color TEAL        = new Color(82, 196, 196);
    static final Color XP_BLUE     = new Color(108, 140, 244);
    static final Color XP_GREEN    = new Color(105, 220, 140);
    static final Color TEXT        = new Color(228, 232, 240);
    static final Color MUTED       = new Color(150, 158, 178);

    private static final Font SECTION_FONT = new Font("Calibri", Font.BOLD, 11);
    private static final Font CELL_LBL_FONT = new Font("Calibri", Font.PLAIN, 10);
    private static final Font CELL_VAL_FONT = new Font("Calibri", Font.BOLD, 15);

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
    private final JPanel cardPanel = new JPanel(cards);
    private final RuneAlyticsXpSkillDetailPanel detailPanel;

    private final JLabel accountLabel = new JLabel();
    private final JLabel syncBadge    = new JLabel();

    private final JLabel runtimeVal = new JLabel("00:00:00");
    private final JLabel totalVal   = new JLabel("0");
    private final JLabel rateVal    = new JLabel("0");
    private final JLabel levelsVal  = new JLabel("0");

    private final XpSparkline overallSparkline = new XpSparkline();
    private final JPanel chartCard;

    private final JPanel skillListPanel = new JPanel();
    private final JLabel emptyLabel     = new JLabel();
    private final Map<Skill, RuneAlyticsXpSkillRow> rows = new LinkedHashMap<>();
    private List<Skill> lastOrder = new ArrayList<>();

    private JButton syncButton;

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
        detailPanel = new RuneAlyticsXpSkillDetailPanel(iconManager, config,
                this::showMain, this::onResetSkill);

        cardPanel.setBackground(NAVY_BG);
        cardPanel.add(main, CARD_MAIN);
        cardPanel.add(detailPanel, CARD_DETAIL);

        JScrollPane scroll = new JScrollPane(cardPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(NAVY_BG);
        scroll.setBackground(NAVY_BG);
        add(scroll, BorderLayout.CENTER);

        chartCard = chartCardHolder; // populated by buildMainView() above
    }

    // buildMainView stores the chart card here so the constructor can keep a ref.
    private JPanel chartCardHolder;

    private JPanel buildMainView()
    {
        JPanel view = new JPanel();
        view.setLayout(new BoxLayout(view, BoxLayout.Y_AXIS));
        view.setBackground(NAVY_BG);
        view.setOpaque(true);
        view.setBorder(new EmptyBorder(8, 8, 8, 8));

        // Header
        JComponent header = RuneAlyticsUi.buildPanelHeader("XP Tracker");
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        view.add(header);
        view.add(Box.createRigidArea(new Dimension(0, 6)));

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

        // Summary card
        JPanel summary = card();
        summary.add(sectionLabel("SESSION SUMMARY"));
        summary.add(Box.createRigidArea(new Dimension(0, 6)));
        JPanel grid = new JPanel(new GridLayout(2, 2, 6, 6));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.add(statCell("SESSION RUNTIME", runtimeVal, TEAL));
        grid.add(statCell("TOTAL XP GAINED", totalVal,   GOLD));
        grid.add(statCell("XP / HOUR",       rateVal,    XP_GREEN));
        grid.add(statCell("LEVELS GAINED",   levelsVal,  XP_BLUE));
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height + 40));
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

        // Chart card
        JPanel chart = card();
        chart.add(sectionLabel("XP GAIN OVER TIME"));
        chart.add(Box.createRigidArea(new Dimension(0, 6)));
        overallSparkline.setAlignmentX(Component.LEFT_ALIGNMENT);
        chart.add(overallSparkline);
        chartCardHolder = chart;
        view.add(chart);
        view.add(Box.createRigidArea(new Dimension(0, 8)));

        // Skills section
        view.add(sectionLabel("SKILLS"));
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

        return view;
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

        // Account + sync badge
        String acct = sessionManager.getSessionAccountKey();
        accountLabel.setText(acct != null ? "Account: " + prettyAccount(acct) : "Not logged in");
        updateSyncBadge(now);

        // Compact mode toggles the chart card off.
        chartCard.setVisible(!config.xpCompactMode());

        if (!config.enableXpTracker())
        {
            emptyLabel.setText("XP Tracker is disabled in RuneAlytics settings.");
            emptyLabel.setVisible(true);
            clearRows();
        }

        // Summary
        runtimeVal.setText(XpFormat.duration(sessionManager.runtimeMs(now)));
        totalVal.setText(XpFormat.compactUpper(sessionManager.totalXpGained()));
        rateVal.setText(config.xpShowPerHour()
                ? XpFormat.compactUpper(sessionManager.overallXpPerHour(now)) : "—");
        levelsVal.setText(Integer.toString(sessionManager.levelsGained()));

        overallSparkline.setSamples(sessionManager.overallSamplesSnapshot());

        // Detail view, if showing
        if (detailPanel.getSkill() != null && isDetailShowing())
        {
            RuneAlyticsXpSkillState st = sessionManager.getState(detailPanel.getSkill());
            if (st == null) showMain();
            else            detailPanel.update(st, now);
        }

        // Skill rows
        if (config.enableXpTracker())
        {
            updateSkillRows(now);
        }
    }

    private void updateSkillRows(long now)
    {
        List<RuneAlyticsXpSkillState> states = sessionManager.snapshotStates();

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
                RuneAlyticsXpSkillRow row = rows.computeIfAbsent(st.getSkill(),
                        s -> new RuneAlyticsXpSkillRow(s, iconManager, config, this::showSkillDetail));
                skillListPanel.add(row);
                skillListPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
            skillListPanel.revalidate();
            skillListPanel.repaint();
        }

        for (RuneAlyticsXpSkillState st : states)
        {
            RuneAlyticsXpSkillRow row = rows.get(st.getSkill());
            if (row != null) row.update(st, now);
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
        detailPanel.showSkill(skill);
        detailPanel.update(st, System.currentTimeMillis());
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

    private JComponent statCell(String caption, JLabel valueLabel, Color valueColor)
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(new Color(30, 36, 52));
        p.setOpaque(true);
        p.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER, 1, true),
                new EmptyBorder(6, 8, 6, 8)));

        JLabel cap = new JLabel(caption);
        cap.setFont(CELL_LBL_FONT);
        cap.setForeground(MUTED);
        cap.setAlignmentX(Component.LEFT_ALIGNMENT);

        valueLabel.setFont(CELL_VAL_FONT);
        valueLabel.setForeground(valueColor);
        valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        p.add(cap);
        p.add(Box.createRigidArea(new Dimension(0, 3)));
        p.add(valueLabel);
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
