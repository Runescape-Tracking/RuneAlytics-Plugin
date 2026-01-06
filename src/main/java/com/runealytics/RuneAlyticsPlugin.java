package com.runealytics;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import net.runelite.api.*;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

@PluginDescriptor(
        name = "RuneAlytics",
        description = "Sync your OSRS data with RuneAlytics.com",
        tags = {"stats", "tracking", "analytics", "sync", "deathmatch"}
)
public class RuneAlyticsPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(RuneAlyticsPlugin.class);
    private static final String VERIFY_STATUS_URL = "https://runealytics.com/api/check-verification";

    @Inject
    private Client client;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ScheduledExecutorService executorService;

    @Inject
    private RuneAlyticsPanel runeAlyticsPanel;

    @Inject
    private MatchmakingPanel matchmakingPanel;

    @Inject
    private MatchmakingManager matchmakingManager;

    @Inject
    private RuneAlyticsSettingsPanel settingsPanel;

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private RuneAlyticsState runeAlyticsState;

    @Inject
    private RunealyticsApiClient apiClient;

    @Inject
    private BankDataManager bankDataManager;

    @Inject
    private XpTrackerManager xpTrackerManager;

    @Inject
    private RunealyticsConfig config;

    private NavigationButton navButton;
    private boolean verificationCheckedForCurrentSession = false;
    private final Map<Skill, Integer> lastXpMap = new HashMap<>();

    @Override
    protected void startUp()
    {
        log.info("RuneAlytics plugin started!");

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

        updateLoginStateFromClient();
        matchmakingPanel.refreshLoginState();
        settingsPanel.refreshLoginState();

        verificationCheckedForCurrentSession = false;

        log.info("RuneAlytics plugin initialization complete");
    }

    @Override
    protected void shutDown()
    {
        log.info("RuneAlytics plugin stopped!");

        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }

        runeAlyticsState.reset();
        verificationCheckedForCurrentSession = false;
        lastXpMap.clear();
        matchmakingManager.reset();
    }

    @Provides
    RunealyticsConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(RunealyticsConfig.class);
    }

    private void updateLoginStateFromClient()
    {
        boolean loggedIn = client.getGameState() == GameState.LOGGED_IN
                && client.getLocalPlayer() != null;

        runeAlyticsState.setLoggedIn(loggedIn);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        boolean loggedIn = event.getGameState() == GameState.LOGGED_IN
                && client.getLocalPlayer() != null;

        runeAlyticsState.setLoggedIn(loggedIn);

        matchmakingPanel.refreshLoginState();
        settingsPanel.refreshLoginState();

        if (event.getGameState() == GameState.LOGGED_IN)
        {
            log.info("GameStateChanged: LOGGED_IN, will check verification on GameTick");
            verificationCheckedForCurrentSession = false;
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN
                || event.getGameState() == GameState.HOPPING)
        {
            log.info("GameStateChanged: logged out/hopping; clearing verification state");
            runeAlyticsState.reset();
            verificationCheckedForCurrentSession = false;
            lastXpMap.clear();
            settingsPanel.refreshLoginState();
            matchmakingPanel.refreshLoginState();
            matchmakingManager.reset();
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (!runeAlyticsState.isLoggedIn())
        {
            return;
        }

        matchmakingManager.onGameTick();

        if (verificationCheckedForCurrentSession)
        {
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null)
        {
            log.debug("GameTick: localPlayer is null; waiting...");
            return;
        }

        String rsn = localPlayer.getName();
        log.debug("GameTick RSN: {}", rsn);

        if (rsn == null || rsn.isEmpty())
        {
            log.debug("GameTick: RSN still null/empty; will try again next tick");
            return;
        }

        verificationCheckedForCurrentSession = true;
        runeAlyticsState.setVerifiedUsername(rsn);

        initializeXpTracking();
        checkVerificationAsync(rsn);
    }

    private void checkVerificationAsync(String rsn)
    {
        executorService.submit(() -> {
            boolean verified = false;
            String verificationCode = null;

            try
            {
                if (rsn == null || rsn.isEmpty())
                {
                    log.warn("RSN is empty in async call; skipping verification");
                    return;
                }

                RequestBody body = new FormBody.Builder()
                        .add("osrs_rsn", rsn)
                        .build();

                log.info("Checking verification status for RSN: {}", rsn);

                Request request = new Request.Builder()
                        .url(VERIFY_STATUS_URL)
                        .post(body)
                        .header("X-RuneAlytics-Client", "RuneLite-Plugin")
                        .build();

                try (Response response = httpClient.newCall(request).execute())
                {
                    String responseBody = response.body() != null ? response.body().string() : "";

                    log.info("Verification check - HTTP status: {}", response.code());
                    log.debug("Verification response: {}", responseBody);

                    if (response.isSuccessful())
                    {
                        verified = responseBody.contains("\"verified\":true");
                        if (verified)
                        {
                            verificationCode = RuneAlyticsJson.extractStringField(responseBody, "verification_code");
                        }
                    }
                }
            }
            catch (IOException e)
            {
                log.error("Error during verification check", e);
            }

            boolean finalVerified = verified;
            String finalVerificationCode = verificationCode;

            SwingUtilities.invokeLater(() -> {
                runeAlyticsState.setVerified(finalVerified);
                runeAlyticsState.setVerifiedUsername(finalVerified ? rsn : null);
                runeAlyticsState.setVerificationCode(finalVerified ? finalVerificationCode : null);

                if (finalVerified && finalVerificationCode != null && !finalVerificationCode.isEmpty())
                {
                    config.authToken(finalVerificationCode);
                }

                settingsPanel.updateVerificationStatus(finalVerified, runeAlyticsState.getVerifiedUsername());
                matchmakingPanel.refreshLoginState();
                log.info("Final verification state: {}, verificationCode={}", finalVerified, finalVerificationCode);
            });
        });
    }

    private void initializeXpTracking()
    {
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            for (Skill skill : Skill.values())
            {
                int xp = client.getSkillExperience(skill);
                lastXpMap.put(skill, xp);
            }
            log.debug("XP tracking initialized");
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged statChanged)
    {
        if (!runeAlyticsState.isVerified() || !runeAlyticsState.isLoggedIn())
        {
            return;
        }

        Skill skill = statChanged.getSkill();
        int currentXp = statChanged.getXp();
        Integer previousXp = lastXpMap.get(skill);

        if (previousXp != null && currentXp > previousXp)
        {
            int xpGained = currentXp - previousXp;
            lastXpMap.put(skill, currentXp);

            String username = runeAlyticsState.getVerifiedUsername();

            executorService.submit(() -> {
                try
                {
                    xpTrackerManager.recordXpGain(
                            config.authToken(),
                            username,
                            skill,
                            xpGained,
                            currentXp,
                            client.getRealSkillLevel(skill)
                    );
                    log.debug("Recorded {} XP gain in {}", xpGained, skill.getName());
                }
                catch (Exception e)
                {
                    log.error("Failed to record XP gain", e);
                }
            });
        }
        else if (previousXp == null)
        {
            lastXpMap.put(skill, currentXp);
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!runeAlyticsState.isVerified() || !runeAlyticsState.isLoggedIn())
        {
            return;
        }

        if (event.getContainerId() == InventoryID.BANK.getId())
        {
            ItemContainer bankContainer = event.getItemContainer();
            if (bankContainer != null)
            {
                String username = runeAlyticsState.getVerifiedUsername();

                executorService.submit(() -> {
                    try
                    {
                        bankDataManager.syncBankData(
                                config.authToken(),
                                username,
                                bankContainer
                        );
                        log.info("Bank data synced successfully");
                        settingsPanel.updateLastSyncTime();
                    }
                    catch (Exception e)
                    {
                        log.error("Failed to sync bank data", e);
                    }
                });
            }
        }
    }

    public void verifyToken(String token, Runnable onSuccess, Runnable onFailure)
    {
        executorService.submit(() -> {
            try
            {
                boolean verified = apiClient.verifyToken(token);
                if (verified)
                {
                    runeAlyticsState.setVerified(true);

                    String username = client.getLocalPlayer() != null
                            ? client.getLocalPlayer().getName()
                            : null;
                    runeAlyticsState.setVerifiedUsername(username);

                    apiClient.autoVerifyAccount(token, username);

                    if (onSuccess != null)
                    {
                        onSuccess.run();
                    }

                    log.info("Token verified and account auto-verified for {}", username);
                }
                else
                {
                    if (onFailure != null)
                    {
                        onFailure.run();
                    }
                    log.warn("Token verification failed");
                }
            }
            catch (Exception e)
            {
                log.error("Error during token verification", e);
                if (onFailure != null)
                {
                    onFailure.run();
                }
            }
        });
    }

    public boolean isVerified()
    {
        return runeAlyticsState.isVerified();
    }

    public String getVerifiedUsername()
    {
        return runeAlyticsState.getVerifiedUsername();
    }
}
