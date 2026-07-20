package com.runealytics;

import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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
import java.awt.Insets;
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
    private final Consumer<Skill> onResetSkillRate;

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
    /** "⏸ AFK — PAUSED" indicator on the right corner of the back-to-skills row. */
    private final JLabel afkBadge = new JLabel("⏸ AFK — PAUSED");
    private final JLabel profitRateVal    = new JLabel();
    private final JLabel profitSessionVal = new JLabel();
    private final JLabel profitBreakdown  = new JLabel();
    private final JButton gePricesBtn;
    private final JButton highAlchBtn;
    /** Which valuation the profit card shows; toggled by the two tab buttons. */
    private boolean showAlchPrices = false;
    /** Last economy snapshot + active ms, cached so a tab click re-renders instantly. */
    private SkillEconomyTracker.Snapshot lastEcon = SkillEconomyTracker.Snapshot.EMPTY;
    private long lastEconActiveMs = 0L;
    private final JPanel suppliesSessionList = new JPanel();
    private final JPanel suppliesTodayList   = new JPanel();
    private final JPanel profitCard;
    private final JPanel suppliesCard;
    private long lastSuppliesSignature = Long.MIN_VALUE;

    private final JPanel dropsList = new JPanel();
    private final JPanel dropsCard;
    private final JButton resetBtn;

    private int lastDropSignature = -1;

    RuneAlyticsXpSkillDetailPanel(SkillIconManager iconManager, RunealyticsConfig config,
                                  RuneAlyticsXpSessionManager sessionManager,
                                  Runnable onBack, Consumer<Skill> onResetSkill,
                                  Consumer<Skill> onResetSkillRate)
    {
        this.iconManager      = iconManager;
        this.config           = config;
        this.sessionManager   = sessionManager;
        this.onResetSkill     = onResetSkill;
        this.onResetSkillRate = onResetSkillRate;

        setLayout(new BorderLayout());
        setBackground(DARK_GRAY_COLOR);
        setOpaque(true);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(DARK_GRAY_COLOR);
        content.setOpaque(true);
        content.setBorder(new EmptyBorder(6, 6, 6, 6));

        // ── Back link row (AFK badge pinned to its right corner) ──
        JPanel backRow = new JPanel(new BorderLayout());
        backRow.setOpaque(false);
        backRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        backRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        backRow.setBorder(new EmptyBorder(2, 2, 6, 2));
        JLabel back = new JLabel("←  Back to Skills");
        back.setFont(new Font("Calibri", Font.BOLD, 12));
        back.setForeground(TEAL);
        back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        back.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e) { if (onBack != null) onBack.run(); }
        });
        afkBadge.setFont(new Font("Calibri", Font.BOLD, 10));
        afkBadge.setForeground(new Color(235, 170, 80));
        afkBadge.setHorizontalAlignment(SwingConstants.RIGHT);
        afkBadge.setVisible(false);
        backRow.add(back, BorderLayout.WEST);
        backRow.add(afkBadge, BorderLayout.EAST);
        content.add(backRow);

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
        installResetMenu(header, headerTop, titleCol, iconLabel, nameLabel, levelLabel);
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
        grid.add(statCell("ACTIONS REMAINING", estVal,   TEXT));
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 210));
        content.add(grid);
        content.add(Box.createRigidArea(new Dimension(0, 8)));

        // ── Profit card: tabbed between GE prices and high alch ──
        gePricesBtn = tabButton("GE Prices");
        highAlchBtn = tabButton("High Alch");
        gePricesBtn.addActionListener(e -> setProfitMode(false));
        highAlchBtn.addActionListener(e -> setProfitMode(true));
        profitCard = buildProfitCard();
        content.add(profitCard);
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
        resetBtn.setHorizontalAlignment(SwingConstants.CENTER);
        resetBtn.setMargin(new Insets(0, 0, 0, 0));
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

        // AFK auto-pause indicator, pinned to the right of the back-link row.
        afkBadge.setVisible(sessionManager.isAutoPaused(wallNow));

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

        long actionsLeft = actionsRemaining(st, toNext);
        estVal.setText(actionsLeft > 0 ? XpFormat.comma(actionsLeft) : "—");

        // Inside the final 25% of the level, the "almost there" stats go
        // gold; they revert automatically once a new level starts.
        Color closeColor = st.levelProgress() >= 0.75 ? GOLD : TEXT;
        ttlVal.setForeground(closeColor);
        estVal.setForeground(closeColor);
        toNextVal.setForeground(closeColor);

        sparkline.setSamples(st.rateHistorySnapshot());

        updateEconomy(st, activeNow, ignoreAfk, afk);
        updateDrops(st, wallNow);
    }

    // ── Profit / supplies (per-skill GP economics) ────────────────────────────

    /**
     * Refreshes the tabbed profit card and the supplies-used card from the
     * per-skill economy snapshot. The GP/hr denominator is the skill's own
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

        lastEcon = econ;
        lastEconActiveMs = st.activeMillis(activeNow, ignoreAfk, afk);

        boolean show = econ.hasSessionData() || econ.hasTodayData();
        profitCard.setVisible(show);
        suppliesCard.setVisible(show && (!econ.sessionSupplies.isEmpty() || !econ.todaySupplies.isEmpty()));
        if (!show) return;

        renderProfit();
        updateSupplies(econ);
    }

    /** Switches the profit card between GE and high-alch valuation (tab buttons). */
    private void setProfitMode(boolean alch)
    {
        if (showAlchPrices == alch) return;
        showAlchPrices = alch;
        styleTabButtons();
        renderProfit();
    }

    /** Renders the profit card from the cached snapshot in the selected valuation. */
    private void renderProfit()
    {
        SkillEconomyTracker.Snapshot econ = lastEcon;
        long profit  = showAlchPrices ? econ.sessionProfitAlch() : econ.sessionProfitGe();
        long made    = showAlchPrices ? econ.sessionOutputAlch   : econ.sessionOutputGe;
        long spent   = showAlchPrices ? econ.sessionInputAlch    : econ.sessionInputGe;
        long today   = showAlchPrices ? econ.todayProfitAlch()   : econ.todayProfitGe();

        setRate(profitRateVal, profit, lastEconActiveMs);
        setProfit(profitSessionVal, profit);
        // HTML so the "Today" value can carry its own profit/loss colour while
        // the labels stay white (the JLabel's base colour).
        String todayHex = today < 0 ? "#eb6e6e" : "#69dc8c";
        profitBreakdown.setText("<html>Used <font color='#969eb2'>"
                + XpFormat.compactUpper(made)
                + "</font> &nbsp;·&nbsp; Spent <font color='#969eb2'>"
                + XpFormat.compactUpper(spent)
                + "</font> &nbsp;·&nbsp; Today <font color='" + todayHex + "'>"
                + signedCompact(today) + "</font></html>");
    }

    /**
     * Actions until the next level, from this session's average XP per action:
     * {@code ceil(xpToNext / (totalGained / actions))}. Returns 0 when there is
     * no session data to average yet.
     */
    private static long actionsRemaining(RuneAlyticsXpSkillState st, long xpToNext)
    {
        long gained = st.getTotalGained();
        long actions = st.getActions();
        if (xpToNext <= 0 || gained <= 0 || actions <= 0) return 0L;
        return (xpToNext * actions + gained - 1) / gained;
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
     * Builds the tabbed profit card: section title, the GE-Prices / High-Alch
     * tab buttons, a 1×2 grid (PROFIT / HR and SESSION cells) and a muted
     * made/spent/today breakdown line. The tab buttons switch which valuation
     * every figure on the card uses.
     */
    private JPanel buildProfitCard()
    {
        JPanel p = card();
        p.add(sectionLabel("PROFIT"));
        p.add(Box.createRigidArea(new Dimension(0, 6)));

        JPanel tabs = new JPanel(new GridLayout(1, 2, 6, 0));
        tabs.setOpaque(false);
        tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
        tabs.add(gePricesBtn);
        tabs.add(highAlchBtn);
        tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        p.add(tabs);
        styleTabButtons();
        p.add(Box.createRigidArea(new Dimension(0, 6)));

        JPanel grid = new JPanel(new GridLayout(1, 2, 6, 6));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.add(statCell("PROFIT / HR", profitRateVal, PROFIT_POS));
        grid.add(statCell("SESSION", profitSessionVal, PROFIT_POS));
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        p.add(grid);
        p.add(Box.createRigidArea(new Dimension(0, 4)));

        profitBreakdown.setFont(DROP_FONT);
        // White base = the Used/Spent/Today labels; values are coloured
        // inline via HTML in renderProfit().
        profitBreakdown.setForeground(Color.WHITE);
        profitBreakdown.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(profitBreakdown);
        return p;
    }

    /**
     * Builds one profit tab button in the same style as the panel's Sync /
     * Reset buttons (Calibri bold, filled background, rounded line border).
     */
    private JButton tabButton(String text)
    {
        JButton b = new JButton(text);
        b.setFont(new Font("Calibri", Font.BOLD, 12));
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        b.setHorizontalAlignment(SwingConstants.CENTER);
        b.setMargin(new Insets(0, 0, 0, 0));
        return b;
    }

    /** Selected tab gets the Sync-button blue; the other gets the muted pause-button grey. */
    private void styleTabButtons()
    {
        styleTab(gePricesBtn, !showAlchPrices);
        styleTab(highAlchBtn, showAlchPrices);
    }

    private static void styleTab(JButton b, boolean selected)
    {
        b.setForeground(selected ? Color.WHITE : MUTED);
        b.setBackground(selected ? new Color(30, 50, 80) : new Color(40, 44, 60));
        b.setBorder(new CompoundBorder(
                new LineBorder(selected ? new Color(80, 120, 180) : new Color(90, 96, 120), 1, true),
                new EmptyBorder(5, 8, 5, 8)));
    }

    /**
     * Installs the right-click reset menu on the skill header card (and its
     * child components, so the click lands anywhere on the card): reset this
     * skill's session XP, reset only its XP/hr timing, or both.
     */
    private void installResetMenu(JComponent... targets)
    {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem title = new JMenuItem("Reset…");
        title.setEnabled(false);
        menu.add(title);
        menu.addSeparator();

        JMenuItem resetRate = new JMenuItem("Reset XP/hr");
        resetRate.addActionListener(e ->
        {
            if (skill != null && onResetSkillRate != null) onResetSkillRate.accept(skill);
        });
        JMenuItem resetSession = new JMenuItem("Reset session XP");
        resetSession.addActionListener(e ->
        {
            if (skill != null && onResetSkill != null) onResetSkill.accept(skill);
        });
        JMenuItem resetBoth = new JMenuItem("Reset both");
        resetBoth.addActionListener(e ->
        {
            if (skill == null) return;
            if (onResetSkillRate != null) onResetSkillRate.accept(skill);
            if (onResetSkill != null) onResetSkill.accept(skill);
        });
        menu.add(resetRate);
        menu.add(resetSession);
        menu.add(resetBoth);

        MouseAdapter popup = new MouseAdapter()
        {
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }

            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }

            private void maybeShow(MouseEvent e)
            {
                if (e.isPopupTrigger()) menu.show(e.getComponent(), e.getX(), e.getY());
            }
        };
        for (JComponent target : targets)
        {
            target.addMouseListener(popup);
        }
    }

    private JLabel subSectionLabel(String text)
    {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Calibri", Font.BOLD, 11));
        // Same teal as the "KNOW MORE. PLAY SMARTER." tagline, for visibility.
        l.setForeground(TEAL);
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
