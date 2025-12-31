package com.runealytics;

import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

public final class RuneAlyticsUi
{
    private RuneAlyticsUi() {}

    // ---------- PALETTE / CONSTANTS ----------

    // Slightly softer accent colors for statuses
    private static final Color POSITIVE_COLOR = new Color(105, 220, 140);
    private static final Color NEGATIVE_COLOR = new Color(255, 110, 110);
    private static final Color MUTED_TEXT     = new Color(200, 200, 200);
    private static final Color CARD_BORDER    = new Color(60, 60, 60, 180);

    private static final int CARD_CORNER_RADIUS = 6;

    // ---------- ROOT / PANELS ----------

    /** Standard root panel for settings-like screens */
    public static void styleRootPanel(JPanel panel)
    {
        panel.setLayout(new BorderLayout());
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setOpaque(true);
    }

    /** Root vertical content panel with padding */
    public static JPanel rootContentPanel()
    {
        JPanel panel = verticalPanel();
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setOpaque(true);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        return panel;
    }

    /** Generic vertical panel */
    public static JPanel verticalPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    /** Generic horizontal "form row" */
    public static JPanel formRow(Component... components)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (int i = 0; i < components.length; i++)
        {
            row.add(components[i]);
            if (i < components.length - 1)
            {
                row.add(Box.createRigidArea(new Dimension(6, 0)));
            }
        }
        return row;
    }

    /** Card-style panel (dark background + padding + subtle border) */
    public static JPanel cardPanel()
    {
        JPanel panel = verticalPanel();
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setOpaque(true);

        Border outer = new LineBorder(CARD_BORDER, 1, true);
        Border inner = new EmptyBorder(10, 10, 10, 10);
        panel.setBorder(new CompoundBorder(outer, inner));

        return panel;
    }

    /** Style the Material tab strip in one place */
    public static void styleTabStrip(JComponent tabStrip)
    {
        tabStrip.setLayout(new BoxLayout(tabStrip, BoxLayout.X_AXIS));
        tabStrip.setBorder(new EmptyBorder(4, 4, 4, 4));
        tabStrip.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        tabStrip.setOpaque(true);
    }

    /** Style the main display panel behind tab content */
    public static void styleDisplayPanel(JPanel display)
    {
        display.setBackground(ColorScheme.DARK_GRAY_COLOR);
        display.setOpaque(true);
    }

    // ---------- LABELS ----------

    public static JLabel titleLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        label.setForeground(ColorScheme.TEXT_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    public static JLabel subtitleLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(MUTED_TEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    public static JLabel bodyLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setForeground(ColorScheme.TEXT_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** For grey “value” labels like Username, Last Sync, etc. */
    public static JLabel valueLabel(String text)
    {
        JLabel label = bodyLabel(text);
        label.setForeground(MUTED_TEXT);
        return label;
    }

    public static JLabel statusLabel()
    {
        JLabel label = new JLabel(" ");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(ColorScheme.TEXT_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    public static void stylePositiveStatus(JLabel label)
    {
        label.setForeground(POSITIVE_COLOR);
    }

    public static void styleNegativeStatus(JLabel label)
    {
        label.setForeground(NEGATIVE_COLOR);
    }

    // ---------- INPUTS ----------

    public static JTextField inputField()
    {
        JTextField field = new JTextField();

        field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        field.setForeground(ColorScheme.TEXT_COLOR);
        field.setCaretColor(ColorScheme.TEXT_COLOR);

        Border border = new CompoundBorder(
                new LineBorder(ColorScheme.DARKER_GRAY_COLOR.brighter(), 1, true),
                new EmptyBorder(3, 6, 3, 6)
        );
        field.setBorder(border);

        field.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height)
        );

        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        return field;
    }

    // ---------- BUTTONS ----------

    public static JButton primaryButton(String text)
    {
        JButton button = new JButton(text);

        button.setMargin(new Insets(3, 12, 3, 12));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        button.setBackground(ColorScheme.BRAND_ORANGE);
        button.setForeground(Color.BLACK);

        button.setBorder(new CompoundBorder(
                new LineBorder(ColorScheme.BRAND_ORANGE.darker(), 1, true),
                new EmptyBorder(2, 10, 2, 10)
        ));

        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        return button;
    }

    public static JButton secondaryButton(String text)
    {
        JButton button = new JButton(text);

        button.setMargin(new Insets(3, 10, 3, 10));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        button.setForeground(ColorScheme.TEXT_COLOR);

        button.setBorder(new CompoundBorder(
                new LineBorder(CARD_BORDER, 1, true),
                new EmptyBorder(2, 8, 2, 8)
        ));

        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        return button;
    }

    // ---------- TEXT AREAS / API RESPONSE ----------

    /** Generic multi-line info block */
    public static JTextArea infoTextArea(String text)
    {
        JTextArea infoText = new JTextArea(text);
        infoText.setEditable(false);
        infoText.setLineWrap(true);
        infoText.setWrapStyleWord(true);
        infoText.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        infoText.setForeground(MUTED_TEXT);
        infoText.setBorder(new EmptyBorder(10, 10, 10, 10));
        infoText.setAlignmentX(Component.LEFT_ALIGNMENT);
        infoText.setFont(infoText.getFont().deriveFont(Font.PLAIN, 11f));
        return infoText;
    }

    public static JTextArea apiResponseArea()
    {
        JTextArea area = new JTextArea(5, 30);

        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);

        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        area.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        area.setForeground(ColorScheme.TEXT_COLOR);
        area.setBorder(new EmptyBorder(4, 4, 4, 4));

        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    public static JPanel apiResponseCard(String title, JTextArea area)
    {
        JPanel card = cardPanel();

        JLabel header = bodyLabel(title);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));

        JScrollPane scroll = new JScrollPane(area);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        scroll.setOpaque(false);
        scroll.setPreferredSize(new Dimension(100, 200));

        card.add(header);
        card.add(vSpace(6));
        card.add(scroll);

        return card;
    }

    // ---------- SPACERS ----------

    public static Component vSpace(int px)
    {
        return Box.createRigidArea(new Dimension(0, px));
    }

    public static Component hSpace(int px)
    {
        return Box.createRigidArea(new Dimension(px, 0));
    }
}
