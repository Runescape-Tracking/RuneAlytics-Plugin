package com.runealytics;

import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Collapsible panel for boss loot sections
 */
public class CollapsibleBossPanel extends JPanel
{
    private final JPanel headerPanel;
    private final JPanel contentPanel;
    private boolean collapsed = false;
    private final JLabel toggleIcon;

    public CollapsibleBossPanel(String bossName, int killCount, long totalValue)
    {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(new EmptyBorder(2, 2, 2, 2));

        // Header
        headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerPanel.setBorder(new EmptyBorder(4, 4, 4, 4));
        headerPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));

        // Toggle icon
        toggleIcon = new JLabel("▼");
        toggleIcon.setForeground(Color.GRAY);
        toggleIcon.setFont(new Font("Dialog", Font.PLAIN, 10));

        // Boss info
        JLabel nameLabel = new JLabel(bossName + " × " + killCount);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(new Font("Runescape", Font.BOLD, 11));

        JLabel valueLabel = new JLabel(formatGp(totalValue));
        valueLabel.setForeground(Color.WHITE);
        valueLabel.setFont(new Font("Runescape", Font.PLAIN, 11));

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftPanel.setOpaque(false);
        leftPanel.add(toggleIcon);
        leftPanel.add(nameLabel);

        headerPanel.add(leftPanel, BorderLayout.WEST);
        headerPanel.add(valueLabel, BorderLayout.EAST);

        // Content panel
        contentPanel = new JPanel();
        contentPanel.setLayout(new BorderLayout());
        contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        contentPanel.setBorder(new EmptyBorder(4, 20, 4, 4));

        // Click to toggle
        headerPanel.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                toggleCollapse();
            }

            @Override
            public void mouseEntered(MouseEvent e)
            {
                headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR.brighter());
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            }
        });

        add(headerPanel, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
    }

    public void setContent(JComponent content)
    {
        contentPanel.removeAll();
        contentPanel.add(content, BorderLayout.CENTER);
        contentPanel.revalidate();
    }

    private void toggleCollapse()
    {
        collapsed = !collapsed;
        contentPanel.setVisible(!collapsed);
        toggleIcon.setText(collapsed ? "▶" : "▼");
        revalidate();
        repaint();
    }

    private String formatGp(long value)
    {
        if (value >= 1000000)
        {
            return String.format("%.1fM gp", value / 1000000.0);
        }
        else if (value >= 1000)
        {
            return String.format("%.1fK gp", value / 1000.0);
        }
        return value + " gp";
    }
}