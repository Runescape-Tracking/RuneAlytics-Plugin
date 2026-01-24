package com.runealytics;

import com.runealytics.LootTrackerPanel;
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
    private static final String VIEW_LOOT       = "loot";

    private final MatchmakingPanel matchmakingPanel;
    private final RuneAlyticsSettingsPanel settingsPanel;
    private LootTrackerPanel lootTrackerPanel;

    private final JPanel contentPanel = new JPanel();
    private final CardLayout cardLayout = new CardLayout();

    private final JButton matchmakingButton = new JButton();
    private final JButton settingsButton = new JButton();
    private final JButton lootButton = new JButton();

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

        buildContent();

        // Default view
        showView(VIEW_MATCHMAKING);
    }

    /**
     * Add loot tracker tab to the panel
     * Called from plugin startup
     */
    public void addLootTrackerTab(LootTrackerPanel lootTrackerPanel)
    {
        this.lootTrackerPanel = lootTrackerPanel;

        // Add loot panel to card layout
        contentPanel.add(lootTrackerPanel, VIEW_LOOT);

        // Rebuild nav bar to include loot button
        buildNavBar();

        revalidate();
        repaint();
    }

    private void buildNavBar()
    {
        // Remove old nav bar if exists
        Component[] components = getComponents();
        for (Component comp : components)
        {
            if (comp instanceof JPanel)
            {
                JPanel panel = (JPanel) comp;
                if (panel.getComponentCount() > 0 && panel.getComponent(0) == matchmakingButton)
                {
                    remove(panel);
                    break;
                }
            }
        }

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

        // Loot tracker icon (only add if panel is set)
        if (lootTrackerPanel != null)
        {
            BufferedImage lootImg = ImageUtil.loadImageResource(
                    RuneAlyticsPlugin.class,
                    "/loot_icon.png"
            );
            lootButton.setIcon(new ImageIcon(lootImg));
            lootButton.setToolTipText("Loot Tracker");
            styleNavButton(lootButton, false);
            lootButton.addActionListener(e -> showView(VIEW_LOOT));
        }

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

        if (lootTrackerPanel != null)
        {
            navBar.add(lootButton);
            navBar.add(Box.createRigidArea(new Dimension(4, 0)));
        }

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

        boolean matchmakingActive = VIEW_MATCHMAKING.equals(view);
        boolean lootActive = VIEW_LOOT.equals(view);
        boolean settingsActive = VIEW_SETTINGS.equals(view);

        // Re-style buttons based on which view is active
        styleNavButton(matchmakingButton, matchmakingActive);
        if (lootTrackerPanel != null)
        {
            styleNavButton(lootButton, lootActive);
        }
        styleNavButton(settingsButton, settingsActive);

        matchmakingButton.setEnabled(!matchmakingActive);
        if (lootTrackerPanel != null)
        {
            lootButton.setEnabled(!lootActive);
        }
        settingsButton.setEnabled(!settingsActive);

        if (matchmakingActive)
        {
            matchmakingPanel.refreshLoginState();
        }
        else if (settingsActive)
        {
            settingsPanel.refreshLoginState();
        }
    }
}