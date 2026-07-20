package com.runealytics;

import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

import static com.runealytics.RuneAlyticsXpTrackerPanel.CARD_BG;
import static com.runealytics.RuneAlyticsXpTrackerPanel.CARD_BORDER;
import static com.runealytics.RuneAlyticsXpTrackerPanel.CELL_BG;
import static com.runealytics.RuneAlyticsXpTrackerPanel.GOLD;
import static com.runealytics.RuneAlyticsXpTrackerPanel.MUTED;
import static com.runealytics.RuneAlyticsXpTrackerPanel.TEAL;
import static com.runealytics.RuneAlyticsXpTrackerPanel.TEXT;
import static com.runealytics.RuneAlyticsXpTrackerPanel.XP_GREEN;
import static net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR;

/**
 * Per-skill detail view for the XP Tracker. Shown when a skill row is clicked.
 *
 * <p>Structure mirrors the RuneAlytics mockup: a back link, a skill header card
 * (icon, level, progress-to-next), a stat grid (XP gained, XP/hr, actions/hr,
 * time-to-level, XP-to-next, est. time), a per-skill XP/hr sparkline, a recent
 * XP drops list, and a reset-this-skill button. The content is wrapped in
 * {@code BorderLayout.NORTH} so cards keep their preferred height and never
 * stretch to fill the tall scroll viewport.</p>
 */
class RuneAlyticsXpSkillDetailPanel extends JPanel
{
    private static final Font H_FONT      = new Font("Calibri", Font.BOLD, 16);
    private static final Font LBL_FONT     = new Font("Calibri", Font.PLAIN, 11);
    private static final Font VAL_FONT     = new Font("Calibri", Font.BOLD, 13);
    private static final Font SECTION_FONT = new Font("Calibri", Font.BOLD, 13);
    private static final Font DROP_FONT    = new Font("Calibri", Font.PLAIN, 11);

    /** Positive-profit accent (matches XP green). */
    private static final Color PROFIT_POS = XP_GREEN;
    /** Negative-profit accent. */
    private static final Color PROFIT_NEG = new Color(235, 110, 110);
    /** Max supply rows rendered per scope (session / today). */
    private static final int MAX_SUPPLY_ROWS = 8;

    private final SkillIconManager iconManager;
    private final RunealyticsConfig config;
    private final RuneAlyticsXpSessionManager sessionManager;
    private final Consumer<Skill> onResetSkill;

    private Skill skill;

    private final XpSkillIcon iconLabel = new XpSkillIcon(32);
    private final JLabel nameLabel   = new JLabel();
    private final JLabel levelLabel  = new JLabel();
    private final XpProgressBar headerBar = new XpProgressBar();
    private final JLabel toNextLabel = new JLabel();

    private final JLabel gainedVal   = new JLabel();
    private final JLabel rateVal     = new JLabel();
    private final JLabel actionsVal  = new JLabel();
    private final JLabel toNextVal   = new JLabel();
    private final JLabel ttlVal      = new JLabel();
    private final JLabel estVal      = new JLabel();

    private final XpSparkline sparkline = new XpSparkline();

    // ── Profit / supplies (per-skill GP economics) ──
    private final JLabel geRateVal      = new JLabel();
    private final JLabel geSessionVal   = new JLabel();
    private final JLabel geBreakdown    = new JLabel();
    private final JLabel alchRateVal    = new JLabel();
    private final JLabel alchSessionVal = new JLabel();
    private final JLabel alchBreakdown  = new JLabel();
    private final JPanel suppliesSessionList = new JPanel();
    private final JPanel suppliesTodayList   = new JPanel();
    private final JPanel geCard;
    private final JPanel alchCard;
    private final JPanel suppliesCard;
    private long lastSuppliesSignature = Long.MIN_VALUE;

    private final JPanel dropsList = new JPanel();
    private final JPanel dropsCard;
    private final JButton resetBtn;

    private int lastDropSignature = -1;

    RuneAlyticsXpSkillDetailPanel(SkillIconManager iconManager, RunealyticsConfig config,
                                  RuneAlyticsXpSessionManager sessionManager,
                                  Runnable onBack, Consumer<Skill> onResetSkill)
    {
        this.iconManager    = iconManager;
        this.config         = config;
        this.sessionManager = sessionManager;
        this.onResetSkill   = onResetSkill;

        setLayout(new BorderLayout());
        setBackground(DARK_GRAY_COLOR);
        setOpaque(true);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(DARK_GRAY_COLOR);
        content.setOpaque(true);
        content.setBorder(new EmptyBorder(6, 6, 6, 6));

        // ── Back link ──
        JLabel back = new JLabel("←  Back to Skills");
        back.setFont(new Font("Calibri", Font.BOLD, 12));
        back.setForeground(TEAL);
        back.setAlignmentX(Component.LEFT_ALIGNMENT);
        back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        back.setBorder(new EmptyBorder(2, 2, 6, 2));
        back.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e) { if (onBack != null) onBack.run(); }
        });
        content.add(back);

        // ── Header card ──
        JPanel header = card();
        JPanel headerTop = new JPanel(new BorderLayout(8, 0));
        headerTop.setOpaque(false);
        headerTop.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerTop.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        JPanel titleCol = new JPanel();
        titleCol.setLayout(new BoxLayout(titleCol, BoxLayout.Y_AXIS));
        titleCol.setOpaque(false);
        nameLabel.setFont(H_FONT);
        nameLabel.setForeground(TEXT);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        levelLabel.setFont(new Font("Calibri", Font.PLAIN, 12));
        levelLabel.setForeground(MUTED);
        levelLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleCol.add(nameLabel);
        titleCol.add(levelLabel);
        headerTop.add(iconLabel, BorderLayout.WEST);
        headerTop.add(titleCol,  BorderLayout.CENTER);
        header.add(headerTop);
        header.add(Box.createRigidArea(new Dimension(0, 8)));
        headerBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        headerBar.setPreferredSize(new Dimension(180, 8));
        header.add(headerBar);
        header.add(Box.createRigidArea(new Dimension(0, 4)));
        toNextLabel.setFont(new Font("Calibri", Font.PLAIN, 11));
        toNextLabel.setForeground(MUTED);
        toNextLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(toNextLabel);
        content.add(header);
        content.add(Box.createRigidArea(new Dimension(0, 8)));

        // ── Stat grid (3 x 2) ──
        JPanel grid = new JPanel(new GridLayout(3, 2, 6, 6));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.add(statCell("XP GAINED",        gainedVal, GOLD));
        grid.add(statCell("XP / HOUR",        rateVal,   XP_GREEN));
        grid.add(statCell("ACTIONS / HOUR",   actionsVal, TEXT));
        grid.add(statCell("TIME TO LEVEL",    ttlVal,    TEXT));
        grid.add(statCell("XP TO NEXT LEVEL", toNextVal, TEXT));
        grid.add(statCell("EST. TIME",        estVal,    TEXT));
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 210));
        content.add(grid);
        content.add(Box.createRigidArea(new Dimension(0, 8)));

        // ── Profit cards: GE prices + high alch (session GP economics) ──
        geCard = profitCard("PROFIT (GE PRICES)", geRateVal, geSessionVal, geBreakdown);
        content.add(geCard);
        content.add(Box.createRigidArea(new Dimension(0, 8)));

        alchCard = profitCard("PROFIT (HIGH ALCH)", alchRateVal, alchSessionVal, alchBreakdown);
        content.add(alchCard);
        content.add(Box.createRigidArea(new Dimension(0, 8)));

        // ── Supplies used (session + today, GE valued) ──
        suppliesCard = card();
        suppliesCard.add(sectionLabel("SUPPLIES USED"));
        suppliesCard.add(Box.createRigidArea(new Dimension(0, 6)));
        suppliesCard.add(subSectionLabel("THIS SESSION"));
        suppliesSessionList.setLayout(new BoxLayout(suppliesSessionList, BoxLayout.Y_AXIS));
        suppliesSessionList.setOpaque(false);
        suppliesSessionList.setAlignmentX(Component.LEFT_ALIGNMENT);
        suppliesCard.add(suppliesSessionList);
        suppliesCard.add(Box.createRigidArea(new Dimension(0, 6)));
        suppliesCard.add(subSectionLabel("TODAY"));
        suppliesTodayList.setLayout(new BoxLayout(suppliesTodayList, BoxLayout.Y_AXIS));
        suppliesTodayList.setOpaque(false);
        suppliesTodayList.setAlignmentX(Component.LEFT_ALIGNMENT);
        suppliesCard.add(suppliesTodayList);
        content.add(suppliesCard);
        content.add(Box.createRigidArea(new Dimension(0, 8)));

        // ── Sparkline card (XP/hr over the last hour) ──
        JPanel chartCard = card();
        chartCard.add(sectionLabel("XP / HOUR"));
        chartCard.add(Box.createRigidArea(new Dimension(0, 6)));
        sparkline.setAlignmentX(Component.LEFT_ALIGNMENT);
        chartCard.add(sparkline);
        content.add(chartCard);
        content.add(Box.createRigidArea(new Dimension(0, 8)));

        // ── Recent XP drops ──
        dropsCard = card();
        dropsCard.add(sectionLabel("RECENT XP DROPS"));
        dropsCard.add(Box.createRigidArea(new Dimension(0, 6)));
        dropsList.setLayout(new BoxLayout(dropsList, BoxLayout.Y_AXIS));
        dropsList.setOpaque(false);
        dropsList.setAlignmentX(Component.LEFT_ALIGNMENT);
        dropsCard.add(dropsList);
        content.add(dropsCard);
        content.add(Box.createRigidArea(new Dimension(0, 8)));

        // ── Reset this skill ──
        resetBtn = new JButton("Reset Skill XP");
        resetBtn.setFont(new Font("Calibri", Font.BOLD, 12));
        resetBtn.setForeground(new Color(255, 150, 150));
        resetBtn.setBackground(new Color(60, 30, 34));
        resetBtn.setFocusPainted(false);
        resetBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        resetBtn.setBorder(new CompoundBorder(
                new LineBorder(new Color(110, 50, 55), 1, true),
                new EmptyBorder(5, 10, 5, 10)));
        resetBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        resetBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        resetBtn.addActionListener(e ->
        {
            if (this.skill != null && this.onResetSkill != null) this.onResetSkill.accept(this.skill);
        });
        content.add(resetBtn);

        add(content, BorderLayout.NORTH);
    }

    /** Points the panel at a skill and resets its per-skill drop cache. */
    void showSkill(Skill skill)
    {
        this.skill = skill;
        lastDropSignature = -1;
        lastSuppliesSignature = Long.MIN_VALUE;
        Color accent = SkillColors.of(skill);
        nameLabel.setText(RuneAlyticsXpSkillRow.prettyName(skill));
        nameLabel.setForeground(accent);
        headerBar.setAccent(accent);
        sparkline.setLineColor(accent);
        gainedVal.setForeground(accent);
        resetBtn.setText("Reset " + RuneAlyticsXpSkillRow.prettyName(skill) + " XP");
        try
        {
            iconLabel.setImage(iconManager.getSkillImage(skill, false));
        }
        catch (Exception ignored)
        {
            iconLabel.setImage(null);
        }
    }

    Skill getSkill()
    {
        return skill;
    }

    /**
     * Refreshes all values for the current skill. Runs on the EDT.
     *
     * @param wallNow   real wall-clock ms (for "time ago" on drops)
     * @param activeNow active-session-clock ms (for all rate/time calculations)
     */
    void update(RuneAlyticsXpSkillState st, long wallNow, long activeNow)
    {
        if (st == null) return;

        boolean ignoreAfk = config.xpIgnoreAfk();
        long afk = Math.max(1, config.xpAfkTimeout()) * 60_000L;

        levelLabel.setText("Lvl. " + st.displayLevel());
        headerBar.setFraction(st.levelProgress());

        long toNext = st.xpToNextLevel();
        toNextLabel.setText(toNext > 0
                ? XpFormat.comma(toNext) + " xp to level " + (st.displayLevel() + 1)
                : "Level maxed");

        // True XP-gained value (never abbreviated).
        gainedVal.setText(XpFormat.comma(st.getTotalGained()));
        rateVal.setText(config.xpShowPerHour()
                ? XpFormat.compactUpper(st.xpPerHour(activeNow, ignoreAfk, afk))
                : "—");
        actionsVal.setText(XpFormat.comma(st.actionsPerHour(activeNow, ignoreAfk, afk)));
        toNextVal.setText(toNext > 0 ? XpFormat.comma(toNext) : "—");

        long ttlMs = st.timeToNextLevelMs(activeNow, ignoreAfk, afk);
        ttlVal.setText(XpFormat.timeToLevel(ttlMs));
        estVal.setText(ttlMs <= 0 ? "—" : XpFormat.duration(ttlMs));

        sparkline.setSamples(st.rateHistorySnapshot());

        updateEconomy(st, activeNow, ignoreAfk, afk);
        updateDrops(st, wallNow);
    }

    // ── Profit / supplies (per-skill GP economics) ────────────────────────────

    /**
     * Refreshes the GE-profit, alch-profit and supplies-used cards from the
     * per-skill economy snapshot. The GP/hr denominators are the skill's own
     * active training time — the same clock XP/hr uses — so both rates pause
     * and resume together during AFK.
     */
    private void updateEconomy(RuneAlyticsXpSkillState st, long activeNow,
                               boolean ignoreAfk, long afk)
    {
        SkillEconomyTracker.Snapshot econ;
        try
        {
            econ = sessionManager.economy().snapshot(skill != null ? skill.getName() : null);
        }
        catch (Exception e)
        {
            econ = SkillEconomyTracker.Snapshot.EMPTY;
        }

        boolean show = econ.hasSessionData() || econ.hasTodayData();
        geCard.setVisible(show);
        alchCard.setVisible(show);
        suppliesCard.setVisible(show && (!econ.sessionSupplies.isEmpty() || !econ.todaySupplies.isEmpty()));
        if (!show) return;

        long activeMs = st.activeMillis(activeNow, ignoreAfk, afk);

        setRate(geRateVal, econ.sessionProfitGe(), activeMs);
        setProfit(geSessionVal, econ.sessionProfitGe());
        geBreakdown.setText("Made " + XpFormat.compactUpper(econ.sessionOutputGe)
                + "  ·  Spent " + XpFormat.compactUpper(econ.sessionInputGe)
                + "  ·  Today " + signedCompact(econ.todayProfitGe()));

        setRate(alchRateVal, econ.sessionProfitAlch(), activeMs);
        setProfit(alchSessionVal, econ.sessionProfitAlch());
        alchBreakdown.setText("Made " + XpFormat.compactUpper(econ.sessionOutputAlch)
                + "  ·  Spent " + XpFormat.compactUpper(econ.sessionInputAlch)
                + "  ·  Today " + signedCompact(econ.todayProfitAlch()));

        updateSupplies(econ);
    }

    /** GP/hr cell: signed compact rate, green for profit, red for loss, "—" when unknown. */
    private static void setRate(JLabel label, long profitGp, long activeMs)
    {
        if (activeMs < 3_000L)
        {
            label.setText("—");
            label.setForeground(MUTED);
            return;
        }
        long rate = profitGp * 3_600_000L / activeMs;
        label.setText(signedCompact(rate) + "/hr");
        label.setForeground(rate < 0 ? PROFIT_NEG : PROFIT_POS);
    }

    /** Session-profit cell: signed compact total with profit/loss colouring. */
    private static void setProfit(JLabel label, long profitGp)
    {
        label.setText(signedCompact(profitGp) + " gp");
        label.setForeground(profitGp < 0 ? PROFIT_NEG : PROFIT_POS);
    }

    private static String signedCompact(long gp)
    {
        return gp < 0 ? "-" + XpFormat.compactUpper(-gp) : "+" + XpFormat.compactUpper(gp);
    }

    private void updateSupplies(SkillEconomyTracker.Snapshot econ)
    {
        // Cheap change signature so the lists aren't rebuilt on every refresh tick.
        long signature = 17L;
        for (SkillEconomyTracker.ItemFlow f : econ.sessionSupplies)
        {
            signature = signature * 31L + f.getItemId() * 1_000_003L + f.getQuantity();
        }
        for (SkillEconomyTracker.ItemFlow f : econ.todaySupplies)
        {
            signature = signature * 31L + f.getItemId() * 7_000_003L + f.getQuantity();
        }
        if (signature == lastSuppliesSignature) return;
        lastSuppliesSignature = signature;

        fillSupplyList(suppliesSessionList, econ.sessionSupplies);
        fillSupplyList(suppliesTodayList, econ.todaySupplies);
    }

    private void fillSupplyList(JPanel list, java.util.List<SkillEconomyTracker.ItemFlow> flows)
    {
        list.removeAll();
        if (flows.isEmpty())
        {
            JLabel none = new JLabel("No supplies used yet");
            none.setFont(DROP_FONT);
            none.setForeground(MUTED);
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            list.add(none);
        }
        else
        {
            long totalGe = 0L;
            int shown = Math.min(flows.size(), MAX_SUPPLY_ROWS);
            for (int i = 0; i < shown; i++)
            {
                SkillEconomyTracker.ItemFlow f = flows.get(i);
                list.add(supplyRow(
                        f.getItemName() + "  ×" + XpFormat.comma(f.getQuantity()),
                        XpFormat.compactUpper(f.getGeGp()) + " gp",
                        TEXT));
            }
            for (SkillEconomyTracker.ItemFlow f : flows) totalGe += f.getGeGp();
            if (flows.size() > shown)
            {
                list.add(supplyRow("… " + (flows.size() - shown) + " more", "", MUTED));
            }
            list.add(supplyRow("Total", XpFormat.compactUpper(totalGe) + " gp", GOLD));
        }
        list.revalidate();
        list.repaint();
    }

    private JPanel supplyRow(String left, String right, Color leftColor)
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(2, 0, 2, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));

        JLabel l = new JLabel(left);
        l.setFont(DROP_FONT);
        l.setForeground(leftColor);

        JLabel r = new JLabel(right);
        r.setFont(DROP_FONT);
        r.setForeground(MUTED);
        r.setHorizontalAlignment(SwingConstants.RIGHT);

        row.add(l, BorderLayout.WEST);
        row.add(r, BorderLayout.EAST);
        return row;
    }

    private void updateDrops(RuneAlyticsXpSkillState st, long nowMs)
    {
        if (!config.xpShowRecentDrops())
        {
            dropsCard.setVisible(false);
            return;
        }
        dropsCard.setVisible(true);

        List<RuneAlyticsXpSkillState.XpDrop> drops = st.recentDropsSnapshot();
        int signature = drops.isEmpty() ? 0 : (drops.size() * 31 + drops.get(0).amount);
        if (signature == lastDropSignature)
        {
            // Only the "time ago" text drifts; refresh those labels cheaply.
            refreshDropTimes(nowMs, drops);
            return;
        }
        lastDropSignature = signature;

        dropsList.removeAll();
        if (drops.isEmpty())
        {
            JLabel none = new JLabel("No XP drops yet");
            none.setFont(DROP_FONT);
            none.setForeground(MUTED);
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            dropsList.add(none);
        }
        else
        {
            int shown = Math.min(drops.size(), 12);
            for (int i = 0; i < shown; i++)
            {
                RuneAlyticsXpSkillState.XpDrop d = drops.get(i);
                JPanel row = new JPanel(new BorderLayout());
                row.setOpaque(false);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.setBorder(new EmptyBorder(2, 0, 2, 0));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));

                JLabel amt = new JLabel("+" + XpFormat.comma(d.amount) + " xp");
                amt.setFont(DROP_FONT);
                amt.setForeground(XP_GREEN);

                JLabel ago = new JLabel(XpFormat.ago(nowMs - d.timeMs));
                ago.setFont(DROP_FONT);
                ago.setForeground(MUTED);
                ago.setHorizontalAlignment(SwingConstants.RIGHT);

                row.add(amt, BorderLayout.WEST);
                row.add(ago, BorderLayout.EAST);
                dropsList.add(row);
            }
        }
        dropsList.revalidate();
        dropsList.repaint();
    }

    private void refreshDropTimes(long nowMs, List<RuneAlyticsXpSkillState.XpDrop> drops)
    {
        Component[] rows = dropsList.getComponents();
        int shown = Math.min(Math.min(rows.length, drops.size()), 12);
        for (int i = 0; i < shown; i++)
        {
            if (!(rows[i] instanceof JPanel)) continue;
            JPanel row = (JPanel) rows[i];
            Object east = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.EAST);
            if (east instanceof JLabel)
            {
                ((JLabel) east).setText(XpFormat.ago(nowMs - drops.get(i).timeMs));
            }
        }
    }

    // ── Small builders ────────────────────────────────────────────────────────

    /**
     * Builds one profit card: section title, a 1×2 grid (PROFIT / HR and
     * SESSION cells) and a muted made/spent/today breakdown line.
     */
    private JPanel profitCard(String title, JLabel rateLabel, JLabel sessionLabel, JLabel breakdownLabel)
    {
        JPanel p = card();
        p.add(sectionLabel(title));
        p.add(Box.createRigidArea(new Dimension(0, 6)));

        JPanel grid = new JPanel(new GridLayout(1, 2, 6, 6));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.add(statCell("PROFIT / HR", rateLabel, PROFIT_POS));
        grid.add(statCell("SESSION", sessionLabel, PROFIT_POS));
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        p.add(grid);
        p.add(Box.createRigidArea(new Dimension(0, 4)));

        breakdownLabel.setFont(DROP_FONT);
        breakdownLabel.setForeground(MUTED);
        breakdownLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(breakdownLabel);
        return p;
    }

    private JLabel subSectionLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(LBL_FONT);
        l.setForeground(MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

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

    /** Stat cell with caption + value vertically & horizontally centered. */
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
        cap.setFont(LBL_FONT);
        cap.setForeground(MUTED);
        cap.setAlignmentX(Component.CENTER_ALIGNMENT);
        cap.setHorizontalAlignment(SwingConstants.CENTER);

        valueLabel.setFont(VAL_FONT);
        valueLabel.setForeground(valueColor);
        valueLabel.setText("—");
        valueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        valueLabel.setHorizontalAlignment(SwingConstants.CENTER);

        p.add(Box.createVerticalGlue());
        p.add(cap);
        p.add(Box.createRigidArea(new Dimension(0, 3)));
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
}
