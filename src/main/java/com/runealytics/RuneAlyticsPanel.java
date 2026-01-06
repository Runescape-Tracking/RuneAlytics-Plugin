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
    private static final String VIEW_MATCHMAKING = "matchmaking";
    private static final String VIEW_SETTINGS   = "settings";

    private final MatchmakingPanel matchmakingPanel;
    private final RuneAlyticsSettingsPanel settingsPanel;

    private final JPanel contentPanel = new JPanel();
    private final CardLayout cardLayout = new CardLayout();

    private final JButton matchmakingButton = new JButton();
    private final JButton settingsButton = new JButton();

    @Inject
    public RuneAlyticsPanel(
            MatchmakingPanel matchmakingPanel,
            RuneAlyticsSettingsPanel settingsPanel
    )
    {
        this.matchmakingPanel = matchmakingPanel;
        this.settingsPanel = settingsPanel;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);

        buildNavBar();
        buildContent();

        // Default view
        showView(VIEW_MATCHMAKING);
    }

    private void buildNavBar()
    {
        JPanel navBar = new JPanel();
        navBar.setLayout(new BoxLayout(navBar, BoxLayout.X_AXIS));
        navBar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        navBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        navBar.setOpaque(true);

        // Matchmaking icon
        BufferedImage duelImg = ImageUtil.loadImageResource(
                RuneAlyticsPlugin.class,
                "/duel_arena_icon.png"
        );
        matchmakingButton.setIcon(new ImageIcon(duelImg));
        matchmakingButton.setToolTipText("Matchmaking");
        styleNavButton(matchmakingButton, false);
        matchmakingButton.addActionListener(e -> showView(VIEW_MATCHMAKING));

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

        navBar.add(matchmakingButton);
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

        contentPanel.add(matchmakingPanel, VIEW_MATCHMAKING);
        contentPanel.add(settingsPanel, VIEW_SETTINGS);

        add(contentPanel, BorderLayout.CENTER);
    }

    private void showView(String view)
    {
        cardLayout.show(contentPanel, view);

        boolean duelActive = VIEW_MATCHMAKING.equals(view);

        // Re-style buttons based on which view is active
        styleNavButton(matchmakingButton, duelActive);
        styleNavButton(settingsButton, !duelActive);

        matchmakingButton.setEnabled(!duelActive);
        settingsButton.setEnabled(duelActive);

        if (duelActive)
        {
            matchmakingPanel.refreshLoginState();
        }
    }
}
