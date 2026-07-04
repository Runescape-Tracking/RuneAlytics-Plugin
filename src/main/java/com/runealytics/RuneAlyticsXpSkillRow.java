package com.runealytics;

import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

import static com.runealytics.RuneAlyticsXpTrackerPanel.CARD_BG;
import static com.runealytics.RuneAlyticsXpTrackerPanel.CARD_BORDER;
import static com.runealytics.RuneAlyticsXpTrackerPanel.GOLD;
import static com.runealytics.RuneAlyticsXpTrackerPanel.MUTED;
import static com.runealytics.RuneAlyticsXpTrackerPanel.XP_GREEN;

/**
 * One skill row in the XP Tracker skill list.
 *
 * <p>Created once per skill and reused — {@link #update} only mutates label text
 * and the progress fraction, so polling it from the refresh timer is cheap and
 * causes no structural relayout. Left-clicking the row opens that skill's detail
 * view; right-clicking opens a menu to reset the skill's XP/hr or session XP.</p>
 */
class RuneAlyticsXpSkillRow extends JPanel
{
    private static final Font NAME_FONT  = new Font("Calibri", Font.BOLD, 13);
    private static final Font LVL_FONT    = new Font("Calibri", Font.PLAIN, 11);
    private static final Font VALUE_FONT  = new Font("Calibri", Font.BOLD, 12);
    private static final Font SMALL_FONT  = new Font("Calibri", Font.PLAIN, 10);
    private static final Font LIVE_FONT   = new Font("Calibri", Font.BOLD, 9);

    private static final Color HOVER_BG = new Color(34, 41, 60);
    private static final Color DIM_ACCENT = new Color(90, 96, 110);

    /** "LIVE" shows at full opacity for this long after the last XP gain… */
    private static final long LIVE_WINDOW_MS = 15_000L;
    /** …then fades out over this long before disappearing. */
    private static final long LIVE_FADE_MS   = 2_500L;

    private final Skill skill;
    private final RunealyticsConfig config;
    private final Color skillColor;

    // LIVE fade state (driven by the panel's animation tick).
    private long lastGainWallMs = 0L;
    private boolean hiddenNow = false;
    private int lastLiveAlpha = -1;

    private final JLabel nameLabel   = new JLabel();
    private final JLabel starLabel   = new JLabel("★");
    private final JLabel liveLabel   = new JLabel("● LIVE");
    private final JLabel levelLabel  = new JLabel();
    private final JLabel gainedLabel = new JLabel();
    private final JLabel rateLabel   = new JLabel();
    private final JLabel infoLabel   = new JLabel();
    private final XpProgressBar bar  = new XpProgressBar();
    private final JPanel bottomRow;
    private final JMenuItem hideItem = new JMenuItem("Hide skill");
    private final JMenuItem favItem  = new JMenuItem("Favorite skill");

    RuneAlyticsXpSkillRow(Skill skill, SkillIconManager iconManager, RunealyticsConfig config,
                          Consumer<Skill> onClick, Consumer<Skill> onResetRate,
                          Consumer<Skill> onResetSession, Consumer<Skill> onToggleHide,
                          Consumer<Skill> onToggleFavorite)
    {
        this.skill      = skill;
        this.config     = config;
        this.skillColor = SkillColors.of(skill);

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(CARD_BG);
        setOpaque(true);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER, 1, true),
                new EmptyBorder(6, 8, 6, 8)));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

        // ── Top row: [icon] name / level ............ gained  rate ──
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Skill sprite on a circular MEDIUM_GRAY backing so it stays legible.
        XpSkillIcon iconLabel = new XpSkillIcon(24);
        try
        {
            iconLabel.setImage(iconManager.getSkillImage(skill, true));
        }
        catch (Exception ignored)
        {
            // Fall back to an empty circle if icons are unavailable.
        }

        JPanel nameCol = new JPanel();
        nameCol.setLayout(new BoxLayout(nameCol, BoxLayout.Y_AXIS));
        nameCol.setOpaque(false);
        nameLabel.setFont(NAME_FONT);
        nameLabel.setForeground(skillColor);
        nameLabel.setText(prettyName(skill));
        bar.setAccent(skillColor);

        // Name line: [Skill name]  ● LIVE (green, only while actively training)
        JPanel nameLine = new JPanel();
        nameLine.setLayout(new BoxLayout(nameLine, BoxLayout.X_AXIS));
        nameLine.setOpaque(false);
        nameLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        starLabel.setFont(LIVE_FONT);
        starLabel.setForeground(GOLD);
        starLabel.setVisible(false);
        liveLabel.setFont(LIVE_FONT);
        liveLabel.setForeground(XP_GREEN);
        liveLabel.setVisible(false);
        nameLine.add(nameLabel);
        nameLine.add(Box.createRigidArea(new Dimension(4, 0)));
        nameLine.add(starLabel);
        nameLine.add(Box.createRigidArea(new Dimension(4, 0)));
        nameLine.add(liveLabel);
        nameLine.add(Box.createHorizontalGlue());

        levelLabel.setFont(LVL_FONT);
        levelLabel.setForeground(MUTED);
        levelLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameCol.add(nameLine);
        nameCol.add(levelLabel);

        JPanel left = new JPanel(new BorderLayout(6, 0));
        left.setOpaque(false);
        left.add(iconLabel, BorderLayout.WEST);
        left.add(nameCol,   BorderLayout.CENTER);

        JPanel valueCol = new JPanel();
        valueCol.setLayout(new BoxLayout(valueCol, BoxLayout.Y_AXIS));
        valueCol.setOpaque(false);
        gainedLabel.setFont(VALUE_FONT);
        gainedLabel.setForeground(GOLD);
        gainedLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        rateLabel.setFont(SMALL_FONT);
        rateLabel.setForeground(XP_GREEN);
        rateLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        valueCol.add(gainedLabel);
        valueCol.add(rateLabel);

        top.add(left,     BorderLayout.CENTER);
        top.add(valueCol, BorderLayout.EAST);

        // ── Bottom row: [progress bar] .......... time-to-level · xp to next ──
        bottomRow = new JPanel(new BorderLayout(6, 0));
        bottomRow.setOpaque(false);
        bottomRow.setBorder(new EmptyBorder(4, 0, 0, 0));
        bottomRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        infoLabel.setFont(SMALL_FONT);
        infoLabel.setForeground(MUTED);
        infoLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        bottomRow.add(bar,       BorderLayout.CENTER);
        bottomRow.add(infoLabel, BorderLayout.EAST);

        add(top);
        add(Box.createRigidArea(new Dimension(0, 2)));
        add(bottomRow);

        // ── Right-click menu: hide + resets ──
        JPopupMenu menu = new JPopupMenu();
        JMenuItem header = new JMenuItem(prettyName(skill));
        header.setEnabled(false);
        menu.add(header);
        menu.addSeparator();
        favItem.addActionListener(e -> { if (onToggleFavorite != null) onToggleFavorite.accept(skill); });
        hideItem.addActionListener(e -> { if (onToggleHide != null) onToggleHide.accept(skill); });
        JMenuItem resetRateItem = new JMenuItem("Reset XP/hr");
        resetRateItem.addActionListener(e -> { if (onResetRate != null) onResetRate.accept(skill); });
        JMenuItem resetSessionItem = new JMenuItem("Reset session XP");
        resetSessionItem.addActionListener(e -> { if (onResetSession != null) onResetSession.accept(skill); });
        menu.add(favItem);
        menu.add(hideItem);
        menu.addSeparator();
        menu.add(resetRateItem);
        menu.add(resetSessionItem);

        // Install one mouse handler on the row and every descendant so a click or
        // right-click anywhere on the row works (Swing does not bubble to parents).
        installMouse(this, onClick, menu);
    }

    private void installMouse(Component c, Consumer<Skill> onClick, JPopupMenu menu)
    {
        MouseAdapter ma = new MouseAdapter()
        {
            @Override public void mousePressed(MouseEvent e)  { maybePopup(e, menu); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e, menu); }

            @Override public void mouseClicked(MouseEvent e)
            {
                if (SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger() && onClick != null)
                {
                    onClick.accept(skill);
                }
            }

            @Override public void mouseEntered(MouseEvent e) { setBackground(HOVER_BG); repaint(); }

            @Override public void mouseExited(MouseEvent e)
            {
                Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(),
                        RuneAlyticsXpSkillRow.this);
                if (!contains(p)) { setBackground(CARD_BG); repaint(); }
            }
        };
        c.addMouseListener(ma);
        if (c instanceof java.awt.Container)
        {
            for (Component child : ((java.awt.Container) c).getComponents())
            {
                installMouse(child, onClick, menu);
            }
        }
    }

    private void maybePopup(MouseEvent e, JPopupMenu menu)
    {
        if (e.isPopupTrigger())
        {
            menu.show(e.getComponent(), e.getX(), e.getY());
        }
    }

    Skill getSkill()
    {
        return skill;
    }

    /**
     * Refreshes this row's values. Runs on the EDT.
     *
     * @param activeNow active-session-clock ms (pauses when logged out) used for
     *                  all rate/time calculations
     */
    void update(RuneAlyticsXpSkillState st, long activeNow, boolean hidden, boolean favorite)
    {
        boolean ignoreAfk = config.xpIgnoreAfk();
        long afk = Math.max(1, config.xpAfkTimeout()) * 60_000L;

        // Hidden rows (only shown while "reveal hidden" is on) are dimmed and never
        // marked LIVE, but still update their values.
        hideItem.setText(hidden ? "Unhide skill" : "Hide skill");
        favItem.setText(favorite ? "Unfavorite skill" : "Favorite skill");
        if (starLabel.isVisible() != favorite) starLabel.setVisible(favorite);
        nameLabel.setForeground(hidden ? MUTED : skillColor);
        bar.setAccent(hidden ? DIM_ACCENT : skillColor);

        // LIVE indicator: full for 15s after the last gain, then fades out.
        this.lastGainWallMs = st.getLastGainWallMs();
        this.hiddenNow = hidden;
        applyLiveFade(System.currentTimeMillis());

        levelLabel.setText("Lvl. " + st.displayLevel());
        // True XP-gained value (e.g. 1,441,027), never abbreviated.
        gainedLabel.setText(XpFormat.comma(st.getTotalGained()));

        if (config.xpShowPerHour())
        {
            rateLabel.setText(XpFormat.compactUpper(st.xpPerHour(activeNow, ignoreAfk, afk)) + "/hr");
            rateLabel.setVisible(true);
        }
        else
        {
            rateLabel.setVisible(false);
        }

        boolean showBottom = !config.xpCompactMode();
        bottomRow.setVisible(showBottom);
        if (showBottom)
        {
            bar.setFraction(st.levelProgress());
            infoLabel.setText(buildInfo(st, activeNow, ignoreAfk, afk));
            infoLabel.setVisible(true);
        }
    }

    /**
     * Animation tick (driven by the panel) that fades the LIVE indicator without
     * needing a full data refresh — cheap: it no-ops unless the alpha changed.
     */
    void tickLiveFade(long nowMs)
    {
        applyLiveFade(nowMs);
    }

    /**
     * Recomputes the LIVE dot+text opacity: full for {@link #LIVE_WINDOW_MS} after
     * the last gain, then fading to nothing over {@link #LIVE_FADE_MS}. Hidden rows
     * never show LIVE.
     */
    private void applyLiveFade(long nowMs)
    {
        int alpha;
        if (hiddenNow || lastGainWallMs <= 0L)
        {
            alpha = 0;
        }
        else
        {
            long since = nowMs - lastGainWallMs;
            if (since < LIVE_WINDOW_MS)
            {
                alpha = 255;
            }
            else if (since < LIVE_WINDOW_MS + LIVE_FADE_MS)
            {
                double t = (since - LIVE_WINDOW_MS) / (double) LIVE_FADE_MS;
                alpha = (int) Math.round(255.0 * (1.0 - t));
            }
            else
            {
                alpha = 0;
            }
        }

        if (alpha == lastLiveAlpha) return; // nothing changed — skip the repaint
        lastLiveAlpha = alpha;

        if (alpha <= 0)
        {
            if (liveLabel.isVisible()) liveLabel.setVisible(false);
        }
        else
        {
            liveLabel.setForeground(new Color(
                    XP_GREEN.getRed(), XP_GREEN.getGreen(), XP_GREEN.getBlue(), alpha));
            if (!liveLabel.isVisible()) liveLabel.setVisible(true);
            liveLabel.repaint();
        }
    }

    /** Builds the "time-to-level · xp-to-next" info string for the bottom row. */
    private String buildInfo(RuneAlyticsXpSkillState st, long activeNow, boolean ignoreAfk, long afk)
    {
        long toNext = st.xpToNextLevel();
        if (toNext <= 0) return "maxed";

        String ttl = XpFormat.timeToLevel(st.timeToNextLevelMs(activeNow, ignoreAfk, afk));
        String toNextStr = config.xpShowXpToNext()
                ? XpFormat.compactUpper(toNext) + " to " + (st.displayLevel() + 1)
                : "";

        boolean hasTtl = !"—".equals(ttl);
        if (hasTtl && !toNextStr.isEmpty()) return ttl + "  ·  " + toNextStr;
        if (hasTtl)                          return ttl;
        return toNextStr;
    }

    static String prettyName(Skill skill)
    {
        String n = skill.getName();
        if (n.isEmpty()) return n;
        return Character.toUpperCase(n.charAt(0)) + n.substring(1).toLowerCase();
    }
}
