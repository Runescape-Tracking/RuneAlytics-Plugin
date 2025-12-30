package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.*;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.IOException;

@PluginDescriptor(
        name = "RuneAlytics",
        description = "Plugin by RuneAlytics"
)
public class RuneAlyticsPlugin extends Plugin
{
    private static final String VERIFY_STATUS_URL =
            "https://runealytics.com/api/check-verification";

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

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private RuneAlyticsState runeAlyticsState;

    private NavigationButton navButton;

    // New: track whether we’ve already checked verification for this login session
    private boolean verificationCheckedForCurrentSession = false;

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

        boolean loggedIn = isLoggedIn();
        duelArenaMatchPanel.setLoggedIn(loggedIn);
        runeAlyticsSettingsPanel.refreshLoginState();

        // On startup we just reset the flag; GameTick will handle the first check
        verificationCheckedForCurrentSession = false;

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

        runeAlyticsState.setVerified(false);
        verificationCheckedForCurrentSession = false;

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

        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Just entered LOGGED_IN: reset flag so GameTick will run the check once
            System.out.println("[RuneAlytics] GameStateChanged: LOGGED_IN, will check verification on GameTick.");
            verificationCheckedForCurrentSession = false;
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN
                || event.getGameState() == GameState.HOPPING)
        {
            // Going back to login / hopping – clear verification
            System.out.println("[RuneAlytics] GameStateChanged: logged out/hopping; clearing verification state.");
            runeAlyticsState.setVerified(false);
            verificationCheckedForCurrentSession = false;
            runeAlyticsSettingsPanel.refreshLoginState();
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // Only care if logged in
        if (!isLoggedIn())
        {
            return;
        }

        // Don’t spam the API: only once per login
        if (verificationCheckedForCurrentSession)
        {
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null)
        {
            System.out.println("[RuneAlytics] GameTick: localPlayer is null; waiting...");
            return;
        }

        String rsn = localPlayer.getName();
        System.out.println("[RuneAlytics] GameTick RSN: " + rsn);

        if (rsn == null || rsn.isEmpty())
        {
            // Name not populated yet; wait for another tick
            System.out.println("[RuneAlytics] GameTick: RSN still null/empty; will try again next tick.");
            return;
        }

        // At this point we have a proper RSN; run the verification check once
        verificationCheckedForCurrentSession = true;
        checkVerificationAsync(rsn);
    }

    private void checkVerificationAsync(String rsn)
    {
        new Thread(() -> {
            boolean verified = false;

            try
            {
                if (rsn == null || rsn.isEmpty())
                {
                    System.out.println("[RuneAlytics] RSN is empty in async call; skipping verification.");
                    return;
                }

                // Build POST body with osrs_rsn as a form field
                RequestBody body = new FormBody.Builder()
                        .add("osrs_rsn", rsn)
                        .build();

                System.out.println("[RuneAlytics] Checking verification status for RSN: " + rsn);
                System.out.println("[RuneAlytics] POST " + VERIFY_STATUS_URL);

                Request request = new Request.Builder()
                        .url(VERIFY_STATUS_URL)
                        .post(body) // ✅ proper POST with body
                        .header("X-RuneAlytics-Client", "RuneLite-Plugin")
                        .build();

                try (Response response = httpClient.newCall(request).execute())
                {
                    String responseBody = response.body() != null ? response.body().string() : "";

                    System.out.println("[RuneAlytics] HTTP status: " + response.code());
                    System.out.println("[RuneAlytics] Response body: " + responseBody);

                    if (response.isSuccessful())
                    {
                        // TEMP: simple check; replace with proper JSON parsing when ready
                        verified = responseBody.contains("\"verified\":true");
                    }
                }
            }
            catch (IOException e)
            {
                System.out.println("[RuneAlytics] Error during verification check: " + e.getMessage());
                e.printStackTrace();
            }

            boolean finalVerified = verified;

            SwingUtilities.invokeLater(() -> {
                runeAlyticsState.setVerified(finalVerified);
                runeAlyticsSettingsPanel.refreshLoginState();
                System.out.println("[RuneAlytics] Final verification state: " + finalVerified);
            });
        }).start();
    }
}