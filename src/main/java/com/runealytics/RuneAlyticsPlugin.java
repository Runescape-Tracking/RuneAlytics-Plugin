package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

@PluginDescriptor(
        name = "RuneAlytics",
        description = "Plugin by RuneAlytics"
)
public class RuneAlyticsPlugin extends Plugin
{
    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private RuneAlyticsPanel runeAlyticsPanel;

    @Inject
    private DuelArenaMatchPanel duelArenaMatchPanel;

    @Inject
    private RuneAlyticsSettingsPanel runeAlyticsSettingsPanel;

    @Inject
    private RuneAlyticsSettingsPanel settingsPanel;

    @Inject
    private Client client;

    private NavigationButton navButton;

    @Override
    protected void startUp()
    {
        BufferedImage icon = ImageUtil.loadImageResource(
                RuneAlyticsPlugin.class,
                "/icon.png"
        );

        navButton = NavigationButton.builder()
                .tooltip("RuneAlytics")
                .icon(icon)
                .priority(5)
                .panel(runeAlyticsPanel)
                .build();

        clientToolbar.addNavigation(navButton);

        // Initial state push when plugin starts
        boolean loggedIn = isLoggedIn();
        duelArenaMatchPanel.setLoggedIn(loggedIn);
        runeAlyticsSettingsPanel.refreshLoginState();

        System.out.println("RuneAlytics plugin started");
    }

    @Override
    protected void shutDown()
    {
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }

        System.out.println("RuneAlytics plugin stopped");
    }

    private boolean isLoggedIn()
    {
        return client.getGameState() == GameState.LOGGED_IN
                && client.getLocalPlayer() != null;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        boolean loggedIn = event.getGameState() == GameState.LOGGED_IN
                && client.getLocalPlayer() != null;

        duelArenaMatchPanel.setLoggedIn(loggedIn);
        runeAlyticsSettingsPanel.refreshLoginState();
    }
}
