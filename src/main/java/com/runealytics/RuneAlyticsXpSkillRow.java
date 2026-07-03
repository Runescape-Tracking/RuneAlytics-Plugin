package com.runealytics;

import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
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
import static com.runealytics.RuneAlyticsXpTrackerPanel.TEXT;
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
    private static final Font SMALL_FONT  = new Font("Calibri", Font.PLAIN, 11);
    private static final Font LIVE_FONT   = new Font("Calibri", Font.BOLD, 9);

    private static final Color HOVER_BG = new Color(34, 41, 60);

    private final Skill skill;
    private final RunealyticsConfig config;

    private final JLabel nameLabel   = new JLabel();
    private final JLabel liveLabel   = new JLabel("● LIVE");
    private final JLabel levelLabel  = new JLabel();
    private final JLabel gainedLabel = new JLabel();
    private final JLabel rateLabel   = new JLabel();
    private final JLabel infoLabel   = new JLabel();
    private final XpProgressBar bar  = new XpProgressBar();
    private final JPanel bottomRow;

    RuneAlyticsXpSkillRow(Skill skill, SkillIconManager iconManager, RunealyticsConfig config,
                          Consumer<Skill> onClick, Consumer<Skill> onResetRate,
                          Consumer<Skill> onResetSession)
    {
        this.skill  = skill;
        this.config = config;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(CARD_BG);
        setOpaque(true);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER, 1, true),
                new EmptyBorder(6, 8, 6, 8)));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));

        // ── Top row: [icon] name / level ............ gained  rate ──
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel iconLabel = new JLabel();
        try
        {
            java.awt.image.BufferedImage img = iconManager.getSkillImage(skill, true);
            if (img != null) iconLabel.setIcon(new ImageIcon(img));
        }
        catch (Exception ignored)
        {
            // Fall back to a text-only row if icons are unavailable.
        }
        iconLabel.setPreferredSize(new Dimension(22, 22));

        JPanel nameCol = new JPanel();
        nameCol.setLayout(new BoxLayout(nameCol, BoxLayout.Y_AXIS));
        nameCol.setOpaque(false);
        nameLabel.setFont(NAME_FONT);
        nameLabel.setForeground(TEXT);
        nameLabel.setText(prettyName(skill));

        // Name line: [Skill name]  ● LIVE (green, only while actively training)
        JPanel nameLine = new JPanel();
        nameLine.setLayout(new BoxLayout(nameLine, BoxLayout.X_AXIS));
        nameLine.setOpaque(false);
        nameLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        liveLabel.setFont(LIVE_FONT);
        liveLabel.setForeground(XP_GREEN);
        liveLabel.setVisible(false);
        nameLine.add(nameLabel);
        nameLine.add(Box.createRigidArea(new Dimension(6, 0)));
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

        // ── Right-click reset menu ──
        JPopupMenu menu = new JPopupMenu();
        JMenuItem header = new JMenuItem(prettyName(skill));
        header.setEnabled(false);
        menu.add(header);
        menu.addSeparator();
        JMenuItem resetRateItem = new JMenuItem("Reset XP/hr");
        resetRateItem.addActionListener(e -> { if (onResetRate != null) onResetRate.accept(skill); });
        JMenuItem resetSessionItem = new JMenuItem("Reset session XP");
        resetSessionItem.addActionListener(e -> { if (onResetSession != null) onResetSession.accept(skill); });
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

    /** Refreshes this row's values from the given state. Runs on the EDT. */
    void update(RuneAlyticsXpSkillState st, long nowMs, boolean live)
    {
        boolean ignoreAfk = config.xpIgnoreAfk();
        long afk = Math.max(1, config.xpAfkTimeout()) * 60_000L;

        if (liveLabel.isVisible() != live) liveLabel.setVisible(live);
        levelLabel.setText("Lvl. " + st.displayLevel());
        // True XP-gained value (e.g. 1,441,027), never abbreviated.
        gainedLabel.setText(XpFormat.comma(st.getTotalGained()));

        if (config.xpShowPerHour())
        {
            rateLabel.setText(XpFormat.compactUpper(st.xpPerHour(nowMs, ignoreAfk, afk)) + "/hr");
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
            infoLabel.setText(buildInfo(st, nowMs, ignoreAfk, afk));
            infoLabel.setVisible(true);
        }
    }

    /** Builds the "time-to-level · xp-to-next" info string for the bottom row. */
    private String buildInfo(RuneAlyticsXpSkillState st, long nowMs, boolean ignoreAfk, long afk)
    {
        long toNext = st.xpToNextLevel();
        if (toNext <= 0) return "maxed";

        String ttl = XpFormat.timeToLevel(st.timeToNextLevelMs(nowMs, ignoreAfk, afk));
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
