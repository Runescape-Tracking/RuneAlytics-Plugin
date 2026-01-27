package com.runealytics;

import com.google.gson.Gson;
import com.google.inject.Provides;
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

        // Log config values WITH ACTUAL VALUES
        log.info("=== CONFIGURATION ===");
        log.info("Enable Loot Tracking: {}", config.enableLootTracking());
        log.info("Track All NPCs: {} ← CHECK THIS VALUE", config.trackAllNpcs());
        log.info("Minimum Loot Value: {}", config.minimumLootValue());
        log.info("Sync to Server: {}", config.syncLootToServer());
        log.info("Auto Verification: {}", config.enableAutoVerification());
        log.info("====================");

        // Create the main panel with tabs
        mainPanel = injector.getInstance(RuneAlyticsPanel.class);

        // Add the loot tracker tab to the main panel
        LootTrackerPanel lootPanel = injector.getInstance(LootTrackerPanel.class);
        mainPanel.addLootTrackerTab(lootPanel);

        navButton = NavigationButton.builder()
                .tooltip("RuneAlytics")
                .icon(loadPluginIcon())
                .priority(5)
                .panel(mainPanel)
                .build();

        clientToolbar.addNavigation(navButton);

        // Connect the panel to the loot manager BEFORE initializing
        lootManager.setPanel(lootPanel); // ← ADD THIS LINE

        // Initialize loot manager (this loads local data and syncs with server)
        lootManager.initialize();
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

        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
        }
    }

    @Provides
    RunealyticsConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(RunealyticsConfig.class);
    }

    // ==================== NPC LOOT ====================

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        log.info("=== NPC LOOT RECEIVED EVENT ===");

        if (!config.enableLootTracking())
        {
            log.info("Loot tracking disabled in config");
            return;
        }

        NPC npc = event.getNpc();
        if (npc == null)
        {
            log.info("NPC is null");
            return;
        }

        log.info("NPC: {} (ID: {}, Combat: {})", npc.getName(), npc.getId(), npc.getCombatLevel());

        Collection<net.runelite.client.game.ItemStack> items = event.getItems();
        if (items == null || items.isEmpty())
        {
            log.info("No items in loot");
            return;
        }

        log.info("Loot contains {} items", items.size());

        boolean isBoss = lootManager.isBoss(npc.getId(), npc.getName());
        boolean trackAllNpcs = config.trackAllNpcs();

        log.info("Is boss: {}, Track all NPCs: {}", isBoss, trackAllNpcs);

        // Check if we should track this NPC
        if (!isBoss && !trackAllNpcs)
        {
            log.info("Not a tracked boss and trackAllNpcs is disabled, skipping");
            return;
        }

        // Convert RuneLite ItemStack to custom ItemStack
        List<ItemStack> converted = new ArrayList<>();
        for (net.runelite.client.game.ItemStack item : items)
        {
            String itemName = itemManager.getItemComposition(item.getId()).getName();
            log.info("  Item: {} (ID: {}, Qty: {})", itemName, item.getId(), item.getQuantity());
            converted.add(new ItemStack(item.getId(), item.getQuantity()));
        }

        log.info("Processing loot for {} with {} items", npc.getName(), converted.size());
        lootManager.processNpcLoot(npc, converted);
        log.info("=== END NPC LOOT RECEIVED ===");
    }

    // ==================== PLAYER LOOT (PvP, Chests, etc.) ====================

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        log.info("=== PLAYER LOOT RECEIVED EVENT ===");
        log.info("!!! Event toString: {}", event.toString());

        if (!config.enableLootTracking())
        {
            log.info("Loot tracking disabled in config");
            return;
        }

        Player player = event.getPlayer();
        String source = player != null ? player.getName() : "Unknown";
        log.info("Source Player: {}", source);
        log.info("Last Chest Source: {}", lastChestSource);
        log.info("Local Player Name: {}", client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "null");

        Collection<net.runelite.client.game.ItemStack> items = event.getItems();
        if (items == null || items.isEmpty())
        {
            log.info("No items in loot");
            return;
        }

        log.info("Loot contains {} items", items.size());

        // List all items
        for (net.runelite.client.game.ItemStack item : items)
        {
            String itemName = itemManager.getItemComposition(item.getId()).getName();
            log.info("  !!! ITEM: {} (ID: {}, Qty: {})", itemName, item.getId(), item.getQuantity());
        }

        // Check if this is from our own player (chest loot)
        boolean isOwnPlayer = client.getLocalPlayer() != null &&
                source.equals(client.getLocalPlayer().getName());

        log.info("Is own player: {}", isOwnPlayer);

        if (isOwnPlayer)
        {
            // This is chest loot
            source = lastChestSource;
            log.info("!!! USING CHEST SOURCE: {}", source);
        }

        // Convert items
        List<ItemStack> converted = new ArrayList<>();
        for (net.runelite.client.game.ItemStack item : items)
        {
            converted.add(new ItemStack(item.getId(), item.getQuantity()));
        }

        log.info("!!! PROCESSING LOOT FROM: {} ({} items)", source, converted.size());
        lootManager.processPlayerLoot(source, converted);
        log.info("=== END PLAYER LOOT RECEIVED ===");
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
        log.info("=== WIDGET LOADED: GroupId = {} ===", groupId);

        // Common widget group IDs:
        // 155 = Barrows reward
        // 207 = Achievement Diary reward
        // Check what widget loads for Gauntlet chest

        if (groupId == 155)
        {
            log.info("!!! REWARD WIDGET DETECTED - Current chest source: {}", lastChestSource);
        }
    }

    // ==================== BOSS DEATH ====================

    @Subscribe
    public void onActorDeath(ActorDeath event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        if (!(event.getActor() instanceof NPC))
        {
            return;
        }

        NPC npc = (NPC) event.getActor();

        if (lootManager.isBoss(npc.getId(), npc.getName()))
        {
            lastKilledBoss = npc;
            lastKillTime = Instant.now();

            log.debug("Boss killed: {}", npc.getName());
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

        if (lastKilledBoss == null || lastKillTime == null)
        {
            return;
        }

        if (ChronoUnit.SECONDS.between(lastKillTime, Instant.now()) > 10)
        {
            return;
        }

        TileItem item = event.getItem();
        WorldPoint itemLoc = event.getTile().getWorldLocation();
        WorldPoint bossLoc = lastKilledBoss.getWorldLocation();

        if (itemLoc.distanceTo(bossLoc) <= 5)
        {
            log.debug(
                    "Ground loot near {}: itemId={} qty={}",
                    lastKilledBoss.getName(),
                    item.getId(),
                    item.getQuantity()
            );

            //lootManager.processGroundItem(lastKilledBoss, item);
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

        log.info("=== CHAT [{}]: {} ===", type, msg);

        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
        {
            return;
        }

        if (msg.contains("kill count is:"))
        {
            lootManager.parseKillCountMessage(msg);
        }

        // Detect "Your reward awaits you in the nearby chest" or similar
        if (msg.contains("reward awaits") || msg.contains("You open the chest"))
        {
            lastChestSource = "Corrupted Gauntlet";
            log.info("!!! CHEST OPENING DETECTED - Setting source to: {}", lastChestSource);
        }

        // Detect completion messages
        if (msg.contains("Congratulations, you've completed") && msg.contains("Gauntlet"))
        {
            if (msg.contains("Corrupted"))
            {
                lastChestSource = "Corrupted Gauntlet";
            }
            else
            {
                lastChestSource = "The Gauntlet";
            }
            log.info("!!! GAUNTLET COMPLETION DETECTED - Setting source to: {}", lastChestSource);
        }

        // Generic chest messages
        if (msg.contains("Barrows"))
        {
            lastChestSource = "Barrows";
        }
        else if (msg.contains("Theatre of Blood") || msg.contains("Verzik"))
        {
            lastChestSource = "Theatre of Blood";
        }
        else if (msg.contains("Chambers of Xeric") || msg.contains("Great Olm"))
        {
            lastChestSource = "Chambers of Xeric";
        }
        else if (msg.contains("Tombs of Amascut"))
        {
            lastChestSource = "Tombs of Amascut";
        }
    }



    // ==================== GAME STATE ====================

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (lastKillTime != null &&
                ChronoUnit.SECONDS.between(lastKillTime, Instant.now()) > 30)
        {
            lastKilledBoss = null;
            lastKillTime = null;
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        log.info("=== GAME STATE CHANGED: {} ===", event.getGameState());

        if (event.getGameState() == GameState.LOGGED_IN)
        {
            log.info("Player logged in (waiting for player spawn for verification)");
            state.setLoggedIn(true);

            // Don't check username here - it's null at this point
            // Wait for onPlayerSpawned event instead
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            log.info("Player logged out");
            state.setLoggedIn(false);
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

        log.info("Local player spawned, checking verification");

        // Now the player is definitely available
        if (config.enableAutoVerification() && !state.isVerified())
        {
            checkVerificationStatus();
        }

        // ⚠️ THIS PART IS MISSING OR NOT WORKING ⚠️
        executorService.schedule(() -> {
            log.info("=== POST-LOGIN SYNC CHECK ===");
            log.info("Username: {}", state.getVerifiedUsername());
            log.info("Is Verified: {}", state.isVerified());
            log.info("Sync Enabled: {}", config.syncLootToServer());
            log.info("==============================");

            if (state.isVerified() && config.syncLootToServer())
            {
                log.info("✓ Conditions met - triggering loot sync");
                lootManager.onPlayerLoggedInAndVerified();
            }
            else
            {
                log.warn("✗ Sync conditions not met:");
                log.warn("  - Verified: {}", state.isVerified());
                log.warn("  - Sync enabled: {}", config.syncLootToServer());
            }
        }, 5, TimeUnit.SECONDS);
    }

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

        log.info("Checking verification status for {} with token: {}...",
                rsn,
                token.substring(0, Math.min(10, token.length())));

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

                    SwingUtilities.invokeLater(() -> {
                        if (mainPanel != null)
                        {
                            RuneAlyticsSettingsPanel settingsPanel = injector.getInstance(RuneAlyticsSettingsPanel.class);
                            settingsPanel.updateVerificationStatus(true, rsn);

                            mainPanel.revalidate();
                            mainPanel.repaint();
                        }
                    });
                }
                else
                {
                    log.warn("✗ Verification failed for {}. Token may be invalid or account not verified on server.", rsn);
                    state.setVerified(false);
                    state.setVerifiedUsername(null);

                    SwingUtilities.invokeLater(() -> {
                        if (mainPanel != null)
                        {
                            RuneAlyticsSettingsPanel settingsPanel = injector.getInstance(RuneAlyticsSettingsPanel.class);
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
            period = 60000,
            unit = ChronoUnit.MILLIS,
            asynchronous = true
    )
    public void syncDataScheduled()
    {
        if (!config.syncLootToServer())
        {
            return;
        }

        if (!state.isLoggedIn())
        {
            return;
        }

        lootManager.syncPendingLoot();
    }

    // ==================== HELPERS ====================

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
}