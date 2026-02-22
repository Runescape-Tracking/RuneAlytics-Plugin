package com.runealytics;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
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
import java.util.*;
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
    @Inject private ClientThread clientThread;
    @Inject private RunealyticsConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;
    @Inject private ConfigManager configManager;
    @Inject private ItemManager itemManager;
    @Inject private LootTrackerManager lootManager;
    @Inject private RuneAlyticsState state;
    @Inject private ScheduledExecutorService executorService;
    @Inject private XpTrackerManager xpTrackerManager;

    @Getter
    private RuneAlyticsPanel mainPanel;
    private NavigationButton navButton;

    private List<com.runealytics.ItemStack> inventoryBeforeTempoross = null;
    private NPC lastKilledBoss;
    private Instant lastKillTime;
    private String lastChestSource = "Unknown Chest";
    private boolean waitingForTemporossLoot = false;
    private long temporossCompletionTime = 0;
    private static final long TEMPOROSS_LOOT_WINDOW_MS = 10000; // 10 seconds
    private final Map<Skill, Integer> previousXp = new EnumMap<>(Skill.class);

    @Override
    protected void startUp()
    {
        log.info("RuneAlytics started");
        logConfiguration();

        mainPanel = injector.getInstance(RuneAlyticsPanel.class);
        LootTrackerPanel lootPanel = injector.getInstance(LootTrackerPanel.class);
        mainPanel.addLootTrackerTab(lootPanel);

        navButton = NavigationButton.builder()
                .tooltip("RuneAlytics")
                .icon(loadPluginIcon())
                .priority(5)
                .panel(mainPanel)
                .build();

        clientToolbar.addNavigation(navButton);
        lootManager.setPanel(lootPanel);
        lootManager.initialize();

        log.info("RuneAlytics plugin fully initialized");
    }

    @Override
    protected void shutDown()
    {
        log.info("RuneAlytics plugin shutting down");

        if (lootManager != null)
        {
            lootManager.shutdown();
        }

        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
        }

        state.reset();
        log.info("RuneAlytics plugin shut down complete");
    }

    @Provides
    RunealyticsConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(RunealyticsConfig.class);
    }

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

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        NPC npc = event.getNpc();
        String npcName = npc.getName();

        log.info("NPC Loot received from: {}", npcName);

        Collection<net.runelite.client.game.ItemStack> runeliteItems = event.getItems();

        // Convert RuneLite ItemStacks to our custom ItemStack format
        List<com.runealytics.ItemStack> items = new ArrayList<>();
        for (net.runelite.client.game.ItemStack rlItem : runeliteItems)
        {
            items.add(new com.runealytics.ItemStack(rlItem.getId(), rlItem.getQuantity()));
        }

        log.info("Processing {} items from NPC: {}", items.size(), npcName);
        lootManager.processPlayerLoot(npcName, items);
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        // Get RuneLite ItemStacks
        Collection<net.runelite.client.game.ItemStack> runeliteItems = event.getItems();

        // Use lastChestSource which we set from chat messages or widget detection
        String source = lastChestSource != null ? lastChestSource : "Unknown";

        log.info("Loot received: {} items from {}", runeliteItems.size(), source);

        // Normalize Tempoross sources
        if (source.contains("Reward pool") || source.contains("Tempoross") || source.contains("Casket (Tempoross)"))
        {
            source = "Tempoross";
            log.info("Normalized to: Tempoross");
        }

        // Convert RuneLite ItemStacks to our custom ItemStack format
        List<com.runealytics.ItemStack> items = new ArrayList<>();
        for (net.runelite.client.game.ItemStack rlItem : runeliteItems)
        {
            items.add(new com.runealytics.ItemStack(rlItem.getId(), rlItem.getQuantity()));
        }

        // Process the loot
        lootManager.processPlayerLoot(source, items);

        // Clear the chest source after use
        lastChestSource = null;
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        int groupId = event.getGroupId();

        log.info("Widget loaded: {}", groupId);

        if (groupId == 155)
        {
            log.info("Barrows reward chest opened");
            lastChestSource = "Barrows";
        }
        else if (groupId == 725 || groupId == InterfaceID.CHAMBERS_OF_XERIC_REWARD)
        {
            log.info("CoX reward chest opened");
            lastChestSource = "Chambers of Xeric";
        }
        else if (groupId == 513)
        {
            log.info("ToB reward chest opened");
            lastChestSource = "Theatre of Blood";
        }
        else if (groupId == 73)
        {
            log.info("Clue scroll reward casket opened - processing items");
            clientThread.invokeLater(() -> processClueReward());
        }
        else if (groupId == 773)
        {
            log.info("ToA reward chest opened");
            lastChestSource = "Tombs of Amascut";
        }
        else if (groupId == 229)
        {
            // Tempoross reward pool - items were already added to inventory
            log.info("Tempoross reward pool opened - capturing loot from widget");
            clientThread.invokeLater(() -> processTemporossReward());
        }
        else if (groupId == 700)
        {
            log.info("Corrupted Gauntlet reward chest opened");
            lastChestSource = "Corrupted Gauntlet";
        }
    }

    /**
     * Process Tempoross reward items from the reward pool widget
     */
    private void processTemporossReward()
    {
        // Widget 229 is the Tempoross reward pool
        // The items are in a child widget
        Widget rewardWidget = client.getWidget(229, 1);

        if (rewardWidget == null)
        {
            log.warn("Tempoross reward widget not found, trying alternative");
            // Try different child IDs
            for (int i = 0; i < 20; i++)
            {
                Widget w = client.getWidget(229, i);
                if (w != null && w.getChildren() != null)
                {
                    log.info("Found Tempoross widget at 229, {}", i);
                    rewardWidget = w;
                    break;
                }
            }
        }

        if (rewardWidget == null)
        {
            log.warn("Could not find Tempoross reward widget");
            return;
        }

        Widget[] children = rewardWidget.getChildren();
        if (children == null || children.length == 0)
        {
            log.warn("No items in Tempoross reward widget");
            return;
        }

        List<com.runealytics.ItemStack> temporossItems = new ArrayList<>();

        for (Widget child : children)
        {
            if (child == null)
            {
                continue;
            }

            int itemId = child.getItemId();
            int quantity = child.getItemQuantity();

            if (itemId > 0 && quantity > 0)
            {
                temporossItems.add(new com.runealytics.ItemStack(itemId, quantity));
                log.debug("Found Tempoross reward item: {} x{}", itemId, quantity);
            }
        }

        if (temporossItems.isEmpty())
        {
            log.warn("No valid items found in Tempoross reward pool");
            return;
        }

        log.info("Processing {} items from Tempoross", temporossItems.size());
        lootManager.processPlayerLoot("Tempoross", temporossItems);
    }

    /**
     * Process clue scroll reward items from the casket interface
     */
    private void processClueReward()
    {
        // Widget IDs for clue scroll reward interface
        // Parent: 73, Child: 10 contains the items
        Widget rewardWidget = client.getWidget(73, 10);

        if (rewardWidget == null)
        {
            log.warn("Clue reward widget not found");
            return;
        }

        Widget[] children = rewardWidget.getChildren();
        if (children == null || children.length == 0)
        {
            log.warn("No items in clue reward widget");
            return;
        }

        List<ItemStack> clueItems = new ArrayList<>();

        for (Widget child : children)
        {
            if (child == null)
            {
                continue;
            }

            int itemId = child.getItemId();
            int quantity = child.getItemQuantity();

            if (itemId > 0 && quantity > 0)
            {
                clueItems.add(new ItemStack(itemId, quantity));
                log.debug("Found clue reward item: {} x{}", itemId, quantity);
            }
        }

        if (clueItems.isEmpty())
        {
            log.warn("No valid items found in clue reward casket");
            return;
        }

        log.info("Processing {} items from {}", clueItems.size(), lastChestSource);

        // Process the loot using the chest source we detected from chat
        lootManager.processPlayerLoot(lastChestSource, clueItems);
    }

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

        if (itemLoc != null && bossLoc != null && itemLoc.distanceTo(bossLoc) <= 5)
        {
            log.debug("Ground item near {}: {} x{}",
                    lastKilledBoss.getName(),
                    item.getId(),
                    item.getQuantity());
        }
    }

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

        String lowerMsg = msg.toLowerCase();

        // Tempoross completion
        if (lowerMsg.contains("subdued the spirit") ||
                lowerMsg.contains("you have helped to subdue"))
        {
            log.info("Tempoross completion - capturing inventory snapshot");

            // Snapshot inventory BEFORE loot is added
            clientThread.invokeLater(() -> {
                inventoryBeforeTempoross = getCurrentInventory();
                waitingForTemporossLoot = true;
                log.info("Inventory snapshot taken, {} items", inventoryBeforeTempoross.size());
            });
            return;
        }

        // Rest of your chat handling...
        if (msg.contains("kill count is:") || msg.contains("killcount is:"))
        {
            lootManager.parseKillCountMessage(msg);
        }

        detectChestSource(msg);
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!config.enableLootTracking() || !waitingForTemporossLoot)
        {
            return;
        }

        // Only process inventory changes
        if (event.getContainerId() != InventoryID.INVENTORY.getId())
        {
            return;
        }

        // Process on next tick to let items settle
        clientThread.invokeLater(() -> {
            List<com.runealytics.ItemStack> currentInventory = getCurrentInventory();

            if (inventoryBeforeTempoross == null)
            {
                log.warn("No Tempoross inventory snapshot");
                waitingForTemporossLoot = false;
                return;
            }

            List<com.runealytics.ItemStack> newItems = findNewItems(inventoryBeforeTempoross, currentInventory);

            if (newItems.isEmpty())
            {
                log.debug("No new Tempoross items detected yet");
                return;
            }

            log.info("Found {} new items from Tempoross", newItems.size());

            // Process the loot
            lootManager.processPlayerLoot("Tempoross", newItems);

            // Reset state
            waitingForTemporossLoot = false;
            inventoryBeforeTempoross = null;
        });
    }

    /**
     * Get current inventory as list of ItemStacks
     */
    private List<com.runealytics.ItemStack> getCurrentInventory()
    {
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null)
        {
            return new ArrayList<>();
        }

        List<com.runealytics.ItemStack> items = new ArrayList<>();
        Item[] containerItems = inventory.getItems();

        if (containerItems != null)
        {
            for (Item item : containerItems)
            {
                if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                {
                    items.add(new com.runealytics.ItemStack(item.getId(), item.getQuantity()));
                }
            }
        }

        return items;
    }

    /**
     * Find items that are in current inventory but not in previous
     */
    private List<com.runealytics.ItemStack> findNewItems(List<com.runealytics.ItemStack> before, List<com.runealytics.ItemStack> after)
    {
        List<com.runealytics.ItemStack> newItems = new ArrayList<>();

        // Create map of before inventory
        Map<Integer, Integer> beforeMap = new HashMap<>();
        for (com.runealytics.ItemStack item : before)
        {
            beforeMap.put(item.getId(), beforeMap.getOrDefault(item.getId(), 0) + item.getQuantity());
        }

        // Create map of after inventory
        Map<Integer, Integer> afterMap = new HashMap<>();
        for (com.runealytics.ItemStack item : after)
        {
            int itemId = item.getId();
            int afterQty = afterMap.getOrDefault(itemId, 0) + item.getQuantity();
            afterMap.put(itemId, afterQty);
        }

        // Find new/increased items
        for (Map.Entry<Integer, Integer> entry : afterMap.entrySet())
        {
            int itemId = entry.getKey();
            int afterQty = entry.getValue();
            int beforeQty = beforeMap.getOrDefault(itemId, 0);

            if (afterQty > beforeQty)
            {
                newItems.add(new com.runealytics.ItemStack(itemId, afterQty - beforeQty));
            }
        }

        return newItems;
    }

    private void detectChestSource(String msg)
    {
        String lowerMsg = msg.toLowerCase();

        // Tempoross completion messages
        if (lowerMsg.contains("tempoross") ||
                lowerMsg.contains("spirit of the sea") ||
                lowerMsg.contains("subdue"))
        {
            lastChestSource = "Tempoross";
            log.info("Completion detected: Tempoross");
            return;
        }

        // Wintertodt
        if (lowerMsg.contains("the cold of the wintertodt") || lowerMsg.contains("wintertodt"))
        {
            lastChestSource = "Wintertodt";
            log.info("Completion detected: Wintertodt");
            return;
        }

        // Clue Scrolls
        if (lowerMsg.contains("you have completed") && lowerMsg.contains("treasure trail"))
        {
            if (lowerMsg.contains("beginner"))
            {
                lastChestSource = "Beginner Clue";
                log.info("Clue scroll completed: Beginner Clue");
            }
            else if (lowerMsg.contains("easy"))
            {
                lastChestSource = "Easy Clue";
                log.info("Clue scroll completed: Easy Clue");
            }
            else if (lowerMsg.contains("medium"))
            {
                lastChestSource = "Medium Clue";
                log.info("Clue scroll completed: Medium Clue");
            }
            else if (lowerMsg.contains("hard"))
            {
                lastChestSource = "Hard Clue";
                log.info("Clue scroll completed: Hard Clue");
            }
            else if (lowerMsg.contains("elite"))
            {
                lastChestSource = "Elite Clue";
                log.info("Clue scroll completed: Elite Clue");
            }
            else if (lowerMsg.contains("master"))
            {
                lastChestSource = "Master Clue";
                log.info("Clue scroll completed: Master Clue");
            }
            else
            {
                lastChestSource = "Clue Scroll";
                log.info("Clue scroll completed: Unknown tier");
            }

            log.info("Completion detected: {}", lastChestSource);
            return;
        }

        // Chambers of Xeric
        if (lowerMsg.contains("congratulations - your raid is complete"))
        {
            lastChestSource = "Chambers of Xeric";
            log.info("Completion detected: Chambers of Xeric");
            return;
        }

        // Theatre of Blood
        if (lowerMsg.contains("theatre of blood") && lowerMsg.contains("complete"))
        {
            lastChestSource = "Theatre of Blood";
            log.info("Completion detected: Theatre of Blood");
            return;
        }

        // Tombs of Amascut
        if (lowerMsg.contains("tombs of amascut") || lowerMsg.contains("congratulations! your raid is complete"))
        {
            lastChestSource = "Tombs of Amascut";
            log.info("Completion detected: Tombs of Amascut");
            return;
        }

        // Gauntlet variants
        if (lowerMsg.contains("gauntlet") && lowerMsg.contains("complete"))
        {
            if (lowerMsg.contains("corrupted"))
            {
                lastChestSource = "Corrupted Gauntlet";
                log.info("Completion detected: Corrupted Gauntlet");
            }
            else
            {
                lastChestSource = "The Gauntlet";
                log.info("Completion detected: The Gauntlet");
            }
            return;
        }

        // Generic chest detection
        if (lowerMsg.contains("you open the chest") ||
                lowerMsg.contains("you loot the chest") ||
                lowerMsg.contains("you search the chest"))
        {
            if (lastChestSource == null)
            {
                lastChestSource = "Unknown Chest";
                log.info("Completion detected: Unknown Chest");
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
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

            lastKilledBoss = null;
            lastKillTime = null;
            lastChestSource = "Unknown Chest";
        }
    }

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned event)
    {
        if (event.getPlayer() != client.getLocalPlayer())
        {
            return;
        }

        log.info("Local player spawned");

        if (config.enableAutoVerification() && !state.isVerified())
        {
            checkVerificationStatus();
        }

        // ONLY load local data - NO automatic server sync
        executorService.schedule(() -> {
            log.info("=== POST-LOGIN LOCAL LOAD ===");
            log.info("Username: {}", state.getVerifiedUsername());
            log.info("Is Verified: {}", state.isVerified());
            log.info("==============================");

            if (state.isVerified())
            {
                log.info("✓ Loading LOCAL loot data only");
                lootManager.loadFromStorage();
            }
            else
            {
                log.warn("✗ Not verified - skipping data load");
            }
        }, 2, TimeUnit.SECONDS);
    }

    // ==================== XP TRACKING ====================

    @Subscribe
    public void onStatChanged(StatChanged statChanged)
    {
        if (!config.enableXpTracking() || !state.isVerified())
        {
            return;
        }

        Skill skill = statChanged.getSkill();

        // Skip total level
        if (skill == Skill.OVERALL)
        {
            return;
        }

        int currentXp = statChanged.getXp();
        Integer previousXpValue = previousXp.get(skill);

        // First time seeing this skill
        if (previousXpValue == null)
        {
            previousXp.put(skill, currentXp);
            return;
        }

        // Calculate XP gain
        int xpGained = currentXp - previousXpValue;

        // Update stored XP
        previousXp.put(skill, currentXp);

        // Check minimum XP gain threshold
        if (xpGained < config.minXpGain())
        {
            return;
        }

        // Only record positive XP gains
        if (xpGained <= 0)
        {
            return;
        }

        String username = state.getVerifiedUsername();
        String token = config.authToken();

        if (username == null || username.isEmpty() || token == null || token.isEmpty())
        {
            return;
        }

        int currentLevel = statChanged.getLevel();

        log.info("XP Gain Detected: {} +{} XP (Total: {}, Level: {})",
                skill.getName(), xpGained, currentXp, currentLevel);

        // Record asynchronously
        executorService.submit(() -> {
            xpTrackerManager.recordXpGain(
                    token,
                    username,
                    skill,
                    xpGained,
                    currentXp,
                    currentLevel
            );
        });
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

        if (!state.isLoggedIn() || !state.isVerified())
        {
            return;
        }

        log.debug("Running scheduled sync - uploading unsynced kills");
        lootManager.uploadUnsyncedKills();
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

        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(new java.awt.Color(255, 165, 0));
        g.fillRect(0, 0, 32, 32);
        g.setColor(new java.awt.Color(255, 215, 0));
        g.drawRect(0, 0, 31, 31);
        g.dispose();
        return img;
    }
}