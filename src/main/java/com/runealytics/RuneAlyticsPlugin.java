package com.runealytics;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.runealytics.RunealyticsApiClient;
import com.runealytics.LootTrackerManager;
import com.runealytics.LootTrackerPanel;
import com.runealytics.RuneAlyticsPanel;
import com.runealytics.RuneAlyticsSettingsPanel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@PluginDescriptor(
        name = "RuneAlytics",
        description = "Complete analytics and tracking for Old School RuneScape",
        tags = {"analytics", "tracking", "loot", "stats", "runealytics"}
)
public class RuneAlyticsPlugin extends Plugin
{
    // ==================== INJECTED DEPENDENCIES ====================

    @Inject private Client client;
    @Inject private RunealyticsConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;
    @Inject private ConfigManager configManager;
    @Inject private ItemManager itemManager;
    @Inject private LootTrackerManager lootManager;
    @Inject private RuneAlyticsState state;
    @Inject private ScheduledExecutorService executorService;

    @Getter
    private RuneAlyticsPanel mainPanel;
    private NavigationButton navButton;

    // ==================== TRACKING STATE ====================

    // Boss death tracking for ground loot attribution
    private NPC lastKilledBoss;
    private Instant lastKillTime;

    // Chest source tracking
    private String lastChestSource = "Unknown Chest";

    // ==================== LIFECYCLE ====================

    @Override
    protected void startUp()
    {
        log.info("RuneAlytics started");
        logConfiguration();

        // Create the main panel with tabs
        mainPanel = injector.getInstance(RuneAlyticsPanel.class);

        // Add the loot tracker tab to the main panel
        LootTrackerPanel lootPanel = injector.getInstance(LootTrackerPanel.class);
        mainPanel.addLootTrackerTab(lootPanel);

        // Create navigation button
        navButton = NavigationButton.builder()
                .tooltip("RuneAlytics")
                .icon(loadPluginIcon())
                .priority(5)
                .panel(mainPanel)
                .build();

        clientToolbar.addNavigation(navButton);

        // Connect the panel to the loot manager BEFORE initializing
        lootManager.setPanel(lootPanel);

        // Initialize loot manager (this loads local data and sets up the panel)
        lootManager.initialize();

        log.info("RuneAlytics plugin fully initialized");
    }

    @Override
    protected void shutDown()
    {
        log.info("RuneAlytics plugin shutting down");

        // Save loot data before shutdown
        if (lootManager != null)
        {
            lootManager.shutdown();
        }

        // Remove navigation button
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
        }

        // Reset state
        state.reset();

        log.info("RuneAlytics plugin shut down complete");
    }

    @Provides
    RunealyticsConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(RunealyticsConfig.class);
    }

    // ==================== CONFIGURATION LOGGING ====================

    private void logConfiguration()
    {
        log.info("=== CONFIGURATION ===");
        log.info("Enable Loot Tracking: {}", config.enableLootTracking());
        log.info("Track All NPCs: {}", config.trackAllNpcs());
        log.info("Minimum Loot Value: {}", config.minimumLootValue());
        log.info("Sync to Server: {}", config.syncLootToServer());
        log.info("Auto Verification: {}", config.enableAutoVerification());
        log.info("API URL: {}", config.apiUrl());
        log.info("====================");
    }

    // ==================== NPC LOOT ====================

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        NPC npc = event.getNpc();
        if (npc == null)
        {
            log.debug("NPC is null in loot event");
            return;
        }

        Collection<net.runelite.client.game.ItemStack> items = event.getItems();
        if (items == null || items.isEmpty())
        {
            return;
        }

        boolean isBoss = lootManager.isBoss(npc.getId(), npc.getName());
        boolean trackAllNpcs = config.trackAllNpcs();

        // Check if we should track this NPC
        if (!isBoss && !trackAllNpcs)
        {
            log.debug("Skipping non-boss NPC: {} (trackAllNpcs={})", npc.getName(), trackAllNpcs);
            return;
        }

        log.info("Processing loot from {}: {} items", npc.getName(), items.size());

        // Convert RuneLite ItemStack to custom ItemStack
        List<ItemStack> converted = new ArrayList<>();
        for (net.runelite.client.game.ItemStack item : items)
        {
            converted.add(new ItemStack(item.getId(), item.getQuantity()));
        }

        // Process the loot
        lootManager.processNpcLoot(npc, converted);
    }

    // ==================== PLAYER LOOT (PvP, Chests, etc.) ====================

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        Player player = event.getPlayer();
        String source = player != null ? player.getName() : "Unknown";

        Collection<net.runelite.client.game.ItemStack> items = event.getItems();
        if (items == null || items.isEmpty())
        {
            return;
        }

        // Check if this is from our own player (chest loot)
        boolean isOwnPlayer = client.getLocalPlayer() != null &&
                source.equals(client.getLocalPlayer().getName());

        if (isOwnPlayer)
        {
            // This is chest loot - use the last detected chest source
            source = lastChestSource;
            log.info("Processing chest loot from: {} ({} items)", source, items.size());
        }
        else
        {
            log.info("Processing player loot from: {} ({} items)", source, items.size());
        }

        // Convert items
        List<ItemStack> converted = new ArrayList<>();
        for (net.runelite.client.game.ItemStack item : items)
        {
            converted.add(new ItemStack(item.getId(), item.getQuantity()));
        }

        // Process the loot
        lootManager.processPlayerLoot(source, converted);
    }

    // ==================== WIDGET EVENTS (for chests) ====================

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        int groupId = event.getGroupId();

        // Common widget group IDs:
        // 155 = Barrows reward
        // 207 = Achievement Diary reward
        // 725 = Chambers of Xeric reward
        // 513 = Theatre of Blood reward

        if (groupId == 155)
        {
            log.debug("Barrows reward widget detected");
            lastChestSource = "Barrows";
        }
        else if (groupId == 725)
        {
            log.debug("CoX reward widget detected");
            lastChestSource = "Chambers of Xeric";
        }
        else if (groupId == 513)
        {
            log.debug("ToB reward widget detected");
            lastChestSource = "Theatre of Blood";
        }
    }

    // ==================== GROUND ITEM SPAWN ====================

    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        // Only track ground items near recently killed bosses
        if (lastKilledBoss == null || lastKillTime == null)
        {
            return;
        }

        // Only within 10 seconds of kill
        if (ChronoUnit.SECONDS.between(lastKillTime, Instant.now()) > 10)
        {
            return;
        }

        TileItem item = event.getItem();
        WorldPoint itemLoc = event.getTile().getWorldLocation();
        WorldPoint bossLoc = lastKilledBoss.getWorldLocation();

        // Only if within 5 tiles of boss
        if (itemLoc != null && bossLoc != null && itemLoc.distanceTo(bossLoc) <= 5)
        {
            log.debug("Ground item near {}: {} x{}",
                    lastKilledBoss.getName(),
                    item.getId(),
                    item.getQuantity());
        }
    }

    // ==================== CHAT PARSING ====================

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        String msg = event.getMessage();
        ChatMessageType type = event.getType();

        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
        {
            return;
        }

        // Parse kill count messages
        if (msg.contains("kill count is:") || msg.contains("killcount is:"))
        {
            lootManager.parseKillCountMessage(msg);
        }

        // Detect chest opening messages
        detectChestSource(msg);
    }

    /**
     * Detect and set chest source from chat messages
     */
    private void detectChestSource(String msg)
    {
        String lowerMsg = msg.toLowerCase();

        // Gauntlet
        if (lowerMsg.contains("reward awaits") || lowerMsg.contains("you open the chest"))
        {
            if (lowerMsg.contains("corrupted"))
            {
                lastChestSource = "Corrupted Gauntlet";
            }
            else if (lowerMsg.contains("gauntlet"))
            {
                lastChestSource = "The Gauntlet";
            }
            log.info("Chest detected: {}", lastChestSource);
        }

        // Completion messages
        if (lowerMsg.contains("congratulations") || lowerMsg.contains("completed"))
        {
            if (lowerMsg.contains("corrupted gauntlet"))
            {
                lastChestSource = "Corrupted Gauntlet";
            }
            else if (lowerMsg.contains("gauntlet"))
            {
                lastChestSource = "The Gauntlet";
            }
            else if (lowerMsg.contains("barrows"))
            {
                lastChestSource = "Barrows";
            }
            else if (lowerMsg.contains("theatre of blood") || lowerMsg.contains("verzik"))
            {
                lastChestSource = "Theatre of Blood";
            }
            else if (lowerMsg.contains("chambers of xeric") || lowerMsg.contains("great olm"))
            {
                lastChestSource = "Chambers of Xeric";
            }
            else if (lowerMsg.contains("tombs of amascut"))
            {
                lastChestSource = "Tombs of Amascut";
            }

            log.info("Completion detected: {}", lastChestSource);
        }

        // Direct chest mentions
        if (lowerMsg.contains("barrows") && !lowerMsg.contains("kill"))
        {
            lastChestSource = "Barrows";
        }
    }

    // ==================== GAME STATE ====================

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // Clear old boss reference after 30 seconds
        if (lastKillTime != null && ChronoUnit.SECONDS.between(lastKillTime, Instant.now()) > 30)
        {
            lastKilledBoss = null;
            lastKillTime = null;
        }

    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        log.info("Game state changed: {}", event.getGameState());

        if (event.getGameState() == GameState.LOGGED_IN)
        {
            log.info("Player logged in");
            state.setLoggedIn(true);
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN ||
                event.getGameState() == GameState.HOPPING)
        {
            log.info("Player logged out or hopping");
            state.setLoggedIn(false);

            // Reset tracking state
            lastKilledBoss = null;
            lastKillTime = null;
            lastChestSource = "Unknown Chest";
        }
    }

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned event)
    {
        // Only check for local player
        if (event.getPlayer() != client.getLocalPlayer())
        {
            return;
        }

        log.info("Local player spawned");

        // Check verification status
        if (config.enableAutoVerification() && !state.isVerified())
        {
            checkVerificationStatus();
        }

        // Schedule sync check after player is fully loaded
        executorService.schedule(() -> {
            log.info("=== POST-LOGIN SYNC CHECK ===");
            log.info("Username: {}", state.getVerifiedUsername());
            log.info("Is Verified: {}", state.isVerified());
            log.info("Sync Enabled: {}", config.syncLootToServer());
            log.info("==============================");

            if (state.isVerified() && config.syncLootToServer())
            {
                log.info("✓ Starting post-login sync");

                // Load from local storage first
                lootManager.loadFromStorage();

                // Then sync with server
                lootManager.syncWithServerOnStartup();
            }
            else
            {
                log.warn("✗ Sync conditions not met:");
                log.warn("  - Verified: {}", state.isVerified());
                log.warn("  - Sync enabled: {}", config.syncLootToServer());

                // Still load local data even if not syncing
                if (state.isVerified())
                {
                    lootManager.loadFromStorage();
                }
            }
        }, 5, TimeUnit.SECONDS);
    }

    // ==================== VERIFICATION ====================

    /**
     * Check verification status using stored auth token
     */
    private void checkVerificationStatus()
    {
        if (client.getLocalPlayer() == null)
        {
            log.warn("Local player is null, cannot check verification");
            return;
        }

        String rsn = client.getLocalPlayer().getName();
        if (rsn == null || rsn.isEmpty())
        {
            log.warn("RSN is null or empty, cannot check verification");
            return;
        }

        String token = config.authToken();
        if (token == null || token.isEmpty())
        {
            log.debug("No auth token found for auto-verification");
            return;
        }

        log.info("Checking verification status for {} with token", rsn);

        executorService.submit(() -> {
            try
            {
                RunealyticsApiClient apiClient = injector.getInstance(RunealyticsApiClient.class);
                boolean verified = apiClient.verifyToken(token, rsn);

                if (verified)
                {
                    log.info("✓ Account {} auto-verified successfully", rsn);
                    state.setVerified(true);
                    state.setVerifiedUsername(rsn);
                    state.setVerificationCode(token);

                    // Update UI
                    SwingUtilities.invokeLater(() -> {
                        if (mainPanel != null)
                        {
                            RuneAlyticsSettingsPanel settingsPanel =
                                    injector.getInstance(RuneAlyticsSettingsPanel.class);
                            settingsPanel.updateVerificationStatus(true, rsn);
                            mainPanel.revalidate();
                            mainPanel.repaint();
                        }
                    });
                }
                else
                {
                    log.warn("✗ Verification failed for {}. Token may be invalid.", rsn);
                    state.setVerified(false);
                    state.setVerifiedUsername(null);

                    // Update UI
                    SwingUtilities.invokeLater(() -> {
                        if (mainPanel != null)
                        {
                            RuneAlyticsSettingsPanel settingsPanel =
                                    injector.getInstance(RuneAlyticsSettingsPanel.class);
                            settingsPanel.updateVerificationStatus(false, null);
                        }
                    });
                }
            }
            catch (Exception e)
            {
                log.error("Failed to verify token for {}", rsn, e);
            }
        });
    }

    // ==================== AUTO SYNC ====================

    @Schedule(
            period = 60000, // Every 60 seconds
            unit = ChronoUnit.MILLIS,
            asynchronous = true
    )
    public void syncDataScheduled()
    {
        if (!config.syncLootToServer())
        {
            return;
        }

        if (!state.isLoggedIn() || !state.isVerified())
        {
            return;
        }

        log.debug("Running scheduled sync");
        lootManager.uploadUnsyncedKills();
    }

    // ==================== UI REFRESH ====================

    /**
     * Refresh the loot panel UI
     */
    public void refreshLootPanel()
    {
        if (mainPanel != null)
        {
            SwingUtilities.invokeLater(() -> {
                mainPanel.revalidate();
                mainPanel.repaint();
            });
        }
    }

    // ==================== HELPERS ====================

    /**
     * Load plugin icon or create fallback
     */
    private BufferedImage loadPluginIcon()
    {
        try
        {
            BufferedImage img = ImageUtil.loadImageResource(getClass(), "/runealytics_icon.png");
            if (img != null)
            {
                return img;
            }
        }
        catch (Exception e)
        {
            log.debug("Failed to load icon, using fallback", e);
        }

        // Fallback: create a simple colored square
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(new java.awt.Color(255, 165, 0)); // Orange
        g.fillRect(0, 0, 32, 32);
        g.setColor(new java.awt.Color(255, 215, 0)); // Gold border
        g.drawRect(0, 0, 31, 31);
        g.dispose();
        return img;
    }
}