package com.runealytics;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class RuneAlyticsPanel extends PluginPanel
{
    private static final String VIEW_DUEL_ARENA = "duel_arena";
    private static final String VIEW_SETTINGS   = "settings";

    private final DuelArenaMatchPanel duelArenaMatchPanel;
    private final RuneAlyticsSettingsPanel settingsPanel;

    private final JPanel contentPanel = new JPanel();
    private final CardLayout cardLayout = new CardLayout();

    private final JButton duelButton = new JButton();
    private final JButton settingsButton = new JButton();

    @Inject
    public RuneAlyticsPanel(
            DuelArenaMatchPanel duelArenaMatchPanel,
            RuneAlyticsSettingsPanel settingsPanel
    )
    {
        this.duelArenaMatchPanel = duelArenaMatchPanel;
        this.settingsPanel = settingsPanel;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);

        buildNavBar();
        buildContent();

        // Default view
        showView(VIEW_DUEL_ARENA);
    }

    private void buildNavBar()
    {
        JPanel navBar = new JPanel();
        navBar.setLayout(new BoxLayout(navBar, BoxLayout.X_AXIS));
        navBar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        navBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        navBar.setOpaque(true);

        // Duel Arena icon
        BufferedImage duelImg = ImageUtil.loadImageResource(
                RuneAlyticsPlugin.class,
                "/duel_arena_icon.png"
        );
        duelButton.setIcon(new ImageIcon(duelImg));
        duelButton.setToolTipText("Duel Arena");
        styleNavButton(duelButton, false);
        duelButton.addActionListener(e -> showView(VIEW_DUEL_ARENA));

        // Settings icon
        BufferedImage settingsImg = ImageUtil.loadImageResource(
                RuneAlyticsPlugin.class,
                "/settings_icon.png"
        );
        settingsButton.setIcon(new ImageIcon(settingsImg));
        settingsButton.setToolTipText("Settings");
        styleNavButton(settingsButton, false);
        settingsButton.addActionListener(e -> {
            showView(VIEW_SETTINGS);
            settingsPanel.refreshLoginState();
        });

        navBar.add(duelButton);
        navBar.add(Box.createRigidArea(new Dimension(4, 0)));
        navBar.add(settingsButton);
        navBar.add(Box.createHorizontalGlue());

        add(navBar, BorderLayout.NORTH);
    }

    private void styleNavButton(JButton button, boolean active)
    {
        button.setMargin(new Insets(2, 6, 2, 6));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        button.setContentAreaFilled(true);
        button.setOpaque(true);
        button.setForeground(ColorScheme.TEXT_COLOR);
        button.setBackground(
                active
                        ? ColorScheme.DARK_GRAY_COLOR.brighter()
                        : ColorScheme.DARKER_GRAY_COLOR
        );
    }

    private void buildContent()
    {
        contentPanel.setLayout(cardLayout);
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        contentPanel.setOpaque(true);

        contentPanel.add(duelArenaMatchPanel, VIEW_DUEL_ARENA);
        contentPanel.add(settingsPanel, VIEW_SETTINGS);

        add(contentPanel, BorderLayout.CENTER);
    }

    private void showView(String view)
    {
        cardLayout.show(contentPanel, view);

        boolean duelActive = VIEW_DUEL_ARENA.equals(view);

        // Re-style buttons based on which view is active
        styleNavButton(duelButton, duelActive);
        styleNavButton(settingsButton, !duelActive);

        duelButton.setEnabled(!duelActive);
        settingsButton.setEnabled(duelActive);
    }
}
