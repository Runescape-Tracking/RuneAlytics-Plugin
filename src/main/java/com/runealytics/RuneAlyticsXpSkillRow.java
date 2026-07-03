package com.runealytics;

import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
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
 * causes no structural relayout. Clicking the row opens that skill's detail view
 * via the supplied callback.</p>
 */
class RuneAlyticsXpSkillRow extends JPanel
{
    private static final Font NAME_FONT  = new Font("Calibri", Font.BOLD, 13);
    private static final Font LVL_FONT    = new Font("Calibri", Font.PLAIN, 11);
    private static final Font VALUE_FONT  = new Font("Calibri", Font.BOLD, 12);
    private static final Font SMALL_FONT  = new Font("Calibri", Font.PLAIN, 10);

    private static final Color HOVER_BG = new Color(34, 41, 60);

    private final Skill skill;
    private final RunealyticsConfig config;

    private final JLabel nameLabel   = new JLabel();
    private final JLabel levelLabel  = new JLabel();
    private final JLabel gainedLabel = new JLabel();
    private final JLabel rateLabel   = new JLabel();
    private final JLabel toNextLabel = new JLabel();
    private final XpProgressBar bar  = new XpProgressBar();
    private final JPanel bottomRow;

    RuneAlyticsXpSkillRow(Skill skill, SkillIconManager iconManager, RunealyticsConfig config,
                          Consumer<Skill> onClick)
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
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

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
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        levelLabel.setFont(LVL_FONT);
        levelLabel.setForeground(MUTED);
        levelLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameCol.add(nameLabel);
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
        gainedLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        gainedLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        rateLabel.setFont(SMALL_FONT);
        rateLabel.setForeground(XP_GREEN);
        rateLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        rateLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        valueCol.add(gainedLabel);
        valueCol.add(rateLabel);

        top.add(left,     BorderLayout.CENTER);
        top.add(valueCol, BorderLayout.EAST);

        // ── Bottom row: [progress bar] .......... xp to next ──
        bottomRow = new JPanel(new BorderLayout(6, 0));
        bottomRow.setOpaque(false);
        bottomRow.setBorder(new EmptyBorder(4, 0, 0, 0));
        bottomRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        toNextLabel.setFont(SMALL_FONT);
        toNextLabel.setForeground(MUTED);
        toNextLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        bottomRow.add(bar,        BorderLayout.CENTER);
        bottomRow.add(toNextLabel, BorderLayout.EAST);

        add(top);
        add(Box.createRigidArea(new Dimension(0, 2)));
        add(bottomRow);

        addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e) { if (onClick != null) onClick.accept(skill); }
            @Override public void mouseEntered(MouseEvent e) { setBackground(HOVER_BG); }
            @Override public void mouseExited(MouseEvent e)  { setBackground(CARD_BG); }
        });
    }

    Skill getSkill()
    {
        return skill;
    }

    /** Refreshes this row's values from the given state. Runs on the EDT. */
    void update(RuneAlyticsXpSkillState st, long nowMs)
    {
        boolean ignoreAfk = config.xpIgnoreAfk();
        long afk = Math.max(1, config.xpAfkTimeout()) * 60_000L;

        levelLabel.setText("Lvl. " + st.displayLevel());
        gainedLabel.setText(XpFormat.compactUpper(st.getTotalGained()));

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
            if (config.xpShowXpToNext())
            {
                long toNext = st.xpToNextLevel();
                toNextLabel.setText(toNext > 0
                        ? XpFormat.compactUpper(toNext) + " to " + (st.displayLevel() + 1)
                        : "maxed");
                toNextLabel.setVisible(true);
            }
            else
            {
                toNextLabel.setVisible(false);
            }
        }
    }

    static String prettyName(Skill skill)
    {
        String n = skill.getName();
        if (n.isEmpty()) return n;
        return Character.toUpperCase(n.charAt(0)) + n.substring(1).toLowerCase();
    }
}
