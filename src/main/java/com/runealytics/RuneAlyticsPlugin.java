package com.runealytics;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.*; // General API
import net.runelite.api.widgets.Widget; // Specific import for Widget
import net.runelite.api.events.*;

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
    // ==================== WIDGET GROUP IDs ====================
    // These are the RuneLite widget group IDs for reward/loot interfaces.
    // When onWidgetLoaded fires with one of these IDs, we know loot is incoming
    // via PlayerLootReceived and set lastChestSource accordingly.

    /** Barrows reward chest interface */
    private static final int WIDGET_BARROWS_REWARD = 155;

    /** Chambers of Xeric (CoX) reward chest — uses RuneLite's InterfaceID constant */
    private static final int WIDGET_COX_REWARD = 234;

    /** Theatre of Blood (ToB) reward chest */
    private static final int WIDGET_TOB_REWARD = 513;

    /** Tombs of Amascut (ToA) reward chest */
    private static final int WIDGET_TOA_REWARD = 773;

    /** Corrupted Gauntlet reward chest */
    private static final int WIDGET_CORRUPTED_GAUNTLET_REWARD = 700;

    /** Clue scroll reward casket */
    private static final int WIDGET_CLUE_REWARD = 73;

    /** Tempoross reward pool */
    private static final int WIDGET_TEMPOROSS_REWARD = 229;

    /**
     * Varlamore lair bosses reward interface (Eldric the Ice King, Branda the Fire Queen).
     * Fires when the boss dies and the loot chest/room becomes available.
     * Widget groupId confirmed in-game: 174
     */
    static final int WIDGET_VARLAMORE_LAIR_REWARD = 174;

    /**
     * Yama (Path of Glouphrie area boss) reward interface.
     * TODO: Verify this widget ID against current OSRS client — update if wrong.
     * Check with: log.info("Widget loaded: {}", groupId) and open the loot interface.
     */
    private static final int WIDGET_YAMA_REWARD = 810;

    /**
     * Fortis Colosseum reward interface.
     * This fires after completing a Colosseum wave set.
     */
    private static final int WIDGET_COLOSSEUM_REWARD = 867;

    // ==================== TIMING CONSTANTS ====================

    /**
     * How long (ms) after an NPC death to accept nearby ground item spawns
     * as belonging to that kill. Keep short to avoid false attribution.
     */
    private static final long GROUND_ITEM_WINDOW_MS = 3_000;

    /**
     * How long (ms) before we give up waiting for Tempoross inventory changes
     * and reset the snapshot state.
     */
    private static final long TEMPOROSS_LOOT_WINDOW_MS = 10_000;

    /** Seconds before we clear lastKilledBoss to prevent stale attributions */
    private static final long BOSS_CLEAR_TIMEOUT_SECONDS = 30;

    // ==================== INJECTED DEPENDENCIES ====================

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

    // ==================== UI ====================

    /** The main plugin panel shown in the RuneLite sidebar */
    @Getter
    private RuneAlyticsPanel mainPanel;

    /** The sidebar navigation button */
    private NavigationButton navButton;

    // ==================== LOOT TRACKING STATE ====================

    /**
     * Inventory snapshot taken just BEFORE Tempoross reward is collected,
     * so we can diff new items afterward. Null when not awaiting Tempoross loot.
     */
    private List<ItemStack> inventoryBeforeTempoross = null;

    /**
     * Whether we are currently waiting for a Tempoross inventory change
     * after the completion message fired.
     */
    private boolean waitingForTemporossLoot = false;

    /**
     * The most recently killed boss NPC. Used to attribute nearby ground
     * item spawns to the correct kill source. Cleared after BOSS_CLEAR_TIMEOUT_SECONDS.
     */
    private  NPC lastKilledBoss;

    /**
     * Timestamp of when lastKilledBoss was set. Used to expire the attribution window.
     */
    private Instant lastKillTime;

    /**
     * Source name for the next PlayerLootReceived event.
     * Set by onWidgetLoaded (chest opened) or onChatMessage (completion message).
     * Cleared immediately after being consumed in onPlayerLootReceived.
     */
    private String lastChestSource = null;

    /**
     * XP values from the previous StatChanged event per skill.
     * Used to calculate the delta (xpGained) for each skill event.
     */
    private final Map<Skill, Integer> previousXp = new EnumMap<>(Skill.class);

    // ==================== STARTUP ====================

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

    // ==================== NPC LOOT (ground drops) ====================

    /**
     * Fired by RuneLite when an NPC dies and drops items on the ground.
     * This covers ALL standard ground-drop kills: giants, dragons, wilderness bosses,
     * Vorkath, Zulrah, Cerberus, Hydra, GWD bosses, DKS, etc.
     *
     * <p>FIX: Previously routed through processPlayerLoot() which lost the NPC object
     * (no combat level, no NPC ID). Now routes through processNpcLoot() so full NPC
     * data is available and deduplication is NPC-specific.</p>
     *
     * @param event the NpcLootReceived event containing the NPC and item list
     */
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
            log.warn("onNpcLootReceived: NPC is null, skipping");
            return;
        }

        // Track for ground item attribution (ItemSpawned events)
        lastKilledBoss = npc;
        lastKillTime = Instant.now();

        Collection<net.runelite.client.game.ItemStack> runeliteItems = event.getItems();
        if (runeliteItems == null || runeliteItems.isEmpty())
        {
            log.debug("onNpcLootReceived: No items for {}", npc.getName());
            return;
        }

        // Convert RuneLite ItemStacks → our internal ItemStack type
        List<ItemStack> items = new ArrayList<>();
        for (net.runelite.client.game.ItemStack rlItem : runeliteItems)
        {
            items.add(new ItemStack(rlItem.getId(), rlItem.getQuantity()));
        }

        log.info("NPC loot received from {} (id={}, cb={}): {} items",
                npc.getName(), npc.getId(), npc.getCombatLevel(), items.size());

        // Route through processNpcLoot so NPC ID + combat level are preserved
        // and boss-specific dedup logic applies correctly
        lootManager.processNpcLoot(npc, items);
    }

    // ==================== PLAYER / CHEST LOOT ====================

    /**
     * Fired by RuneLite for non-NPC loot sources: raid chests, Barrows chest,
     * Gauntlet reward, PvP loot piles, etc.
     *
     * <p>The event itself carries no source name, so we rely on {@link #lastChestSource}
     * which is set by {@link #onWidgetLoaded} (chest UI opens) or
     * {@link #onChatMessage} (completion message). If neither fires first,
     * we fall back to "Unknown Chest" rather than silently discarding.</p>
     *
     * @param event the PlayerLootReceived event with the item list
     */
    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        Collection<net.runelite.client.game.ItemStack> runeliteItems = event.getItems();
        if (runeliteItems == null || runeliteItems.isEmpty())
        {
            log.debug("onPlayerLootReceived: empty item list, skipping");
            return;
        }

        // Consume the pending source label; fall back to "Unknown Chest"
        String source = (lastChestSource != null && !lastChestSource.isEmpty())
                ? lastChestSource
                : "Unknown Chest";
        lastChestSource = null; // reset so the next event gets a clean slate

        // Normalise Tempoross variants into one label
        if (source.contains("Reward pool") || source.contains("Casket (Tempoross)"))
        {
            source = "Tempoross";
        }

        log.info("Player loot received from '{}': {} items", source, runeliteItems.size());

        List<ItemStack> items = new ArrayList<>();
        for (net.runelite.client.game.ItemStack rlItem : runeliteItems)
        {
            items.add(new ItemStack(rlItem.getId(), rlItem.getQuantity()));
        }

        lootManager.processPlayerLoot(source, items);
    }

    // ==================== WIDGET / UI DETECTION ====================

    /**
     * Fires whenever a new interface widget is loaded.  We use this to detect
     * which reward chest was just opened and prime {@link #lastChestSource}
     * before the matching PlayerLootReceived event arrives.
     *
     * <p>Widget group IDs are defined as constants at the top of this class.
     * If you add new content, add a constant there and a branch here.</p>
     *
     * @param event the WidgetLoaded event with the interface group ID
     */
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        int groupId = event.getGroupId();
        log.info("RuneAlytics - Widget loaded: groupId={}", groupId);

        if (groupId == WIDGET_BARROWS_REWARD) // 155
        {
            lastChestSource = "Barrows";
            clientThread.invokeLater(() -> processChestLoot(lastChestSource, 141));
        }
        else if (groupId == WIDGET_COX_REWARD) // 234
        {
            lastChestSource = "Chambers of Xeric";
            clientThread.invokeLater(() -> processChestLoot(lastChestSource, 122));
        }
        else if (groupId == WIDGET_TOB_REWARD) // 513
        {
            lastChestSource = "Theatre of Blood";
            clientThread.invokeLater(() -> processChestLoot(lastChestSource, 612));
        }
        else if (groupId == WIDGET_VARLAMORE_LAIR_REWARD) { // 174
            log.info("Varlamore lair reward interface detected");
            // Ensure source is set if chat detection was missed
            if (lastChestSource == null) lastChestSource = "Royal Titans";
            clientThread.invokeLater(this::processVariableReward);
        }
        else if (groupId == WIDGET_TOA_REWARD) // 773
        {
            lastChestSource = "Tombs of Amascut";
            clientThread.invokeLater(() -> processChestLoot(lastChestSource, 141));
        }
        else if (groupId == WIDGET_CORRUPTED_GAUNTLET_REWARD) // 700
        {
            lastChestSource = "Corrupted Gauntlet";
            clientThread.invokeLater(() -> processChestLoot(lastChestSource, 141));
        }
        else if (groupId == WIDGET_YAMA_REWARD) // 810
        {
            lastChestSource = "Yama";
            clientThread.invokeLater(() -> processChestLoot(lastChestSource, 141));
        }
        else if (groupId == WIDGET_COLOSSEUM_REWARD) // 867
        {
            lastChestSource = "Fortis Colosseum";
            clientThread.invokeLater(() -> processChestLoot(lastChestSource, 141));
        }
        else if (groupId == WIDGET_CLUE_REWARD) // 73
        {
            clientThread.invokeLater(this::processClueReward);
        }
        else if (groupId == WIDGET_TEMPOROSS_REWARD) // 229
        {
            clientThread.invokeLater(this::processTemporossReward);
        }
    }

    /**
     * Helper to process reward containers by looking at the specific container ID
     */
    private void processChestLoot(String source, int containerId)
    {
        // Use the integer containerId directly
        ItemContainer container = client.getItemContainer(containerId);
        if (container != null)
        {
            List<ItemStack> lootItems = new ArrayList<>();
            for (Item item : container.getItems())
            {
                if (item.getId() != -1 && item.getQuantity() > 0)
                {
                    lootItems.add(new ItemStack(item.getId(), item.getQuantity()));
                }
            }

            if (!lootItems.isEmpty())
            {
                log.info("RuneAlytics: Successfully captured {} items from {}", lootItems.size(), source);
                lootManager.processPlayerLoot(source, lootItems);
            }
        }
    }

    /**
     * Processes loot from a non-NPC source: raid chests, Barrows chest,
     * Gauntlet, Tempoross, clue scrolls, etc.
     *
     * <p>A 2-second deduplication window IS applied here to prevent double-recording
     * when both the widget-read path AND the PlayerLootReceived event fire for the
     * same loot (e.g. Tempoross can arrive via both inventory-diff and widget read).</p>
     *
     * @param sourceName the display name of the loot source (e.g. "Barrows", "Zulrah")
     * @param items      the item list in our internal ItemStack format
     */
    private void processVariableReward()
    {
        // These variables (client, executorService, etc.) exist ONLY in this file
        executorService.schedule(() -> clientThread.invokeLater(() ->
        {
            List<ItemStack> items = new ArrayList<>();

            for (int i = 0; i < 100; i++)
            {
                Widget w = client.getWidget(WIDGET_VARLAMORE_LAIR_REWARD, i);
                if (w == null) continue;

                if (w.getItemId() > 0 && w.getItemQuantity() > 0)
                {
                    items.add(new ItemStack(w.getItemId(), w.getItemQuantity()));
                }

                Widget[] children = w.getChildren();
                if (children != null)
                {
                    for (Widget child : children)
                    {
                        if (child != null && child.getItemId() > 0 && child.getItemQuantity() > 0)
                        {
                            items.add(new ItemStack(child.getItemId(), child.getItemQuantity()));
                        }
                    }
                }
            }

            // We resolve the name HERE where 'lastChestSource' is visible
            String sourceName = (lastChestSource != null) ? lastChestSource : "Royal Titans";

            if (!items.isEmpty())
            {
                // We hand the items and the name to the Manager
                lootManager.processPlayerLoot(sourceName, items);
                lastChestSource = null;
            }
        }), 200, TimeUnit.MILLISECONDS);
    }

    // ==================== GROUND ITEM SPAWN ====================

    /**
     * Fired when any item appears on the ground.  We use this as a supplemental
     * loot source for rare cases where NpcLootReceived doesn't fire but items
     * do appear near a recently killed boss (e.g., certain special-attack kills
     * or very new content not yet supported by RuneLite's loot tracker).
     *
     * <p>This does NOT duplicate items already captured by onNpcLootReceived —
     * it is purely a fallback that only fires when lastKilledBoss is set and
     * the item spawns within GROUND_ITEM_WINDOW_MS of the kill.</p>
     *
     * @param event the ItemSpawned event
     */
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

        // Expire the attribution window
        long elapsedMs = Instant.now().toEpochMilli() - lastKillTime.toEpochMilli();
        if (elapsedMs > GROUND_ITEM_WINDOW_MS)
        {
            return;
        }

        // Only attribute items that spawn near the boss's last known tile
        TileItem tileItem = event.getItem();
        WorldPoint itemLoc = event.getTile().getWorldLocation();
        WorldPoint bossLoc = lastKilledBoss.getWorldLocation();

        if (itemLoc == null || bossLoc == null || itemLoc.distanceTo(bossLoc) > 5)
        {
            return;
        }

        // Log for debugging — actual processing happens via NpcLootReceived.
        // Only upgrade this to actual processing if you find a boss that
        // drops items via ItemSpawned but NOT via NpcLootReceived.
        log.debug("Ground item near {}: id={} qty={} ({}ms after kill)",
                lastKilledBoss.getName(),
                tileItem.getId(),
                tileItem.getQuantity(),
                elapsedMs);
    }

    // ==================== CHAT MESSAGE DETECTION ====================

    /**
     * Listens for game/spam chat messages to detect:
     * <ul>
     *   <li>Raid / activity completion — sets lastChestSource for upcoming PlayerLootReceived</li>
     *   <li>Tempoross subdual — triggers an inventory snapshot</li>
     *   <li>Kill count messages — passed to lootManager for KC tracking</li>
     * </ul>
     *
     * @param event the ChatMessage event
     */
    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
        {
            return;
        }

        String msg = event.getMessage();
        String lower = msg.toLowerCase();

        // Tempoross completion — snapshot inventory before reward items arrive
        if (lower.contains("subdued the spirit") || lower.contains("you have helped to subdue"))
        {
            log.info("Tempoross completion message detected — snapshotting inventory");
            clientThread.invokeLater(() -> {
                inventoryBeforeTempoross = getCurrentInventory();
                waitingForTemporossLoot = true;
                log.info("Tempoross inventory snapshot: {} items", inventoryBeforeTempoross.size());
            });
            return;
        }

        // Kill count messages (e.g. "Your Zulrah kill count is: 42")
        if (msg.contains("kill count is:") || msg.contains("killcount is:"))
        {
            lootManager.parseKillCountMessage(msg);
        }

        // Detect which chest/activity completed so PlayerLootReceived is attributed
        detectChestSource(lower);
    }


    // ==================== INVENTORY CHANGE (Tempoross) ====================

    /**
     * Fires when any item container changes.  We use this exclusively for
     * Tempoross: after the subdual message we snapshot the inventory, then
     * watch here for new items to appear and record them as Tempoross loot.
     *
     * @param event the ItemContainerChanged event
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!config.enableLootTracking() || !waitingForTemporossLoot)
        {
            return;
        }

        if (event.getContainerId() != InventoryID.INVENTORY.getId())
        {
            return;
        }

        // Let the inventory settle one more tick before diffing
        clientThread.invokeLater(() -> {
            if (inventoryBeforeTempoross == null)
            {
                log.warn("Tempoross: no inventory snapshot found — resetting");
                waitingForTemporossLoot = false;
                return;
            }

            List<ItemStack> current = getCurrentInventory();
            List<ItemStack> newItems = findNewItems(inventoryBeforeTempoross, current);

            if (newItems.isEmpty())
            {
                log.debug("Tempoross: no new items yet, still waiting");
                return; // keep waiting — items may arrive on the next tick
            }

            log.info("Tempoross: found {} new items from inventory diff", newItems.size());
            lootManager.processPlayerLoot("Tempoross", newItems);

            // Reset state so we don't double-count
            waitingForTemporossLoot = false;
            inventoryBeforeTempoross = null;
        });
    }

    // ==================== GAME TICK ====================

    /**
     * Fires every game tick (~600ms).  Used to:
     * <ul>
     *   <li>Expire the lastKilledBoss attribution after BOSS_CLEAR_TIMEOUT_SECONDS</li>
     * </ul>
     *
     * @param tick the GameTick event
     */
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (lastKillTime != null
                && ChronoUnit.SECONDS.between(lastKillTime, Instant.now()) > BOSS_CLEAR_TIMEOUT_SECONDS)
        {
            lastKilledBoss = null;
            lastKillTime = null;
        }
    }

    // ==================== GAME STATE ====================

    /**
     * Handles login/logout state changes.  Resets transient loot tracking state
     * on logout so stale sources don't bleed into the next session.
     *
     * @param event the GameStateChanged event
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState gs = event.getGameState();
        log.info("Game state changed: {}", gs);

        if (gs == GameState.LOGGED_IN)
        {
            state.setLoggedIn(true);
        }
        else if (gs == GameState.LOGIN_SCREEN || gs == GameState.HOPPING)
        {
            state.setLoggedIn(false);

            // Clear all transient loot state on logout
            lastKilledBoss = null;
            lastKillTime = null;
            lastChestSource = null;
            waitingForTemporossLoot = false;
            inventoryBeforeTempoross = null;
        }
    }

    // ==================== PLAYER SPAWNED ====================

    /**
     * Fires when the local player appears in the world (login or world-hop).
     * Triggers auto-verification check and loads local loot data.
     *
     * @param event the PlayerSpawned event
     */
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

        // Load only from local storage — no automatic server sync
        executorService.schedule(() -> {
            log.info("Post-login: verified={}, user={}",
                    state.isVerified(), state.getVerifiedUsername());
            if (state.isVerified())
            {
                log.info("Loading local loot data only");
                lootManager.loadFromStorage();
            }
            else
            {
                log.warn("Not verified — skipping data load");
            }
        }, 2, TimeUnit.SECONDS);
    }

    // ==================== XP TRACKING ====================

    /**
     * Fires when any skill's XP changes.  Calculates the delta and records
     * it asynchronously via XpTrackerManager if above the configured minimum.
     *
     * @param statChanged the StatChanged event
     */
    @Subscribe
    public void onStatChanged(StatChanged statChanged)
    {
        if (!config.enableXpTracking() || !state.isVerified())
        {
            return;
        }

        Skill skill = statChanged.getSkill();
        if (skill == Skill.OVERALL)
        {
            return;
        }

        int currentXp = statChanged.getXp();
        Integer prevXp = previousXp.get(skill);

        // First time we see this skill — just store baseline
        if (prevXp == null)
        {
            previousXp.put(skill, currentXp);
            return;
        }

        int xpGained = currentXp - prevXp;
        previousXp.put(skill, currentXp);

        if (xpGained <= 0 || xpGained < config.minXpGain())
        {
            return;
        }

        String username = state.getVerifiedUsername();
        String token = config.authToken();

        if (username == null || username.isEmpty() || token == null || token.isEmpty())
        {
            return;
        }

        int level = statChanged.getLevel();
        log.info("XP gain: {} +{} (total={}, level={})", skill.getName(), xpGained, currentXp, level);

        executorService.submit(() ->
                xpTrackerManager.recordXpGain(token, username, skill, xpGained, currentXp, level));
    }

    // ==================== SCHEDULED SYNC ====================

    /**
     * Runs every 60 seconds to upload any unsynced local kills to the server.
     * Only fires when the player is logged in and verified.
     */
    @Schedule(
            period = 60000,
            unit = ChronoUnit.MILLIS,
            asynchronous = true
    )
    public void syncDataScheduled()
    {
        if (!config.syncLootToServer() || !state.isLoggedIn() || !state.isVerified())
        {
            return;
        }

        log.debug("Scheduled sync: uploading unsynced kills");
        lootManager.uploadUnsyncedKills();
    }

    // ==================== SPECIAL LOOT PROCESSING ====================

    /**
     * Reads items directly from the Tempoross reward pool widget (group 229).
     * Called from onWidgetLoaded as an alternative path for Tempoross loot
     * when the inventory-diff method hasn't captured items yet.
     *
     * <p>Widget child layout: parent=229, children contain item/quantity pairs.</p>
     */
    private void processTemporossReward()
    {
        Widget rewardWidget = client.getWidget(WIDGET_TEMPOROSS_REWARD, 1);

        // Try sibling child indices if the primary one is null
        if (rewardWidget == null || rewardWidget.getChildren() == null)
        {
            for (int i = 0; i < 20; i++)
            {
                Widget w = client.getWidget(WIDGET_TEMPOROSS_REWARD, i);
                if (w != null && w.getChildren() != null)
                {
                    log.debug("Found Tempoross widget at {},{}", WIDGET_TEMPOROSS_REWARD, i);
                    rewardWidget = w;
                    break;
                }
            }
        }

        if (rewardWidget == null)
        {
            log.warn("Tempoross reward widget not found — relying on inventory diff");
            return;
        }

        Widget[] children = rewardWidget.getChildren();
        if (children == null || children.length == 0)
        {
            log.warn("Tempoross reward widget has no children");
            return;
        }

        List<ItemStack> items = new ArrayList<>();
        for (Widget child : children)
        {
            if (child != null && child.getItemId() > 0 && child.getItemQuantity() > 0)
            {
                items.add(new ItemStack(child.getItemId(), child.getItemQuantity()));
            }
        }

        if (items.isEmpty())
        {
            log.warn("No valid items in Tempoross reward widget");
            return;
        }

        log.info("Tempoross widget: processing {} items", items.size());
        lootManager.processPlayerLoot("Tempoross", items);

        // Prevent double-counting from inventory diff
        waitingForTemporossLoot = false;
        inventoryBeforeTempoross = null;
    }

    /**
     * Reads items from the clue scroll reward casket widget (group 73, child 10).
     * The source label is whatever was last set in lastChestSource by detectChestSource().
     *
     * <p>Widget structure: parent=73, child=10 holds the reward item icons.</p>
     */
    private void processClueReward()
    {
        Widget rewardWidget = client.getWidget(WIDGET_CLUE_REWARD, 10);

        if (rewardWidget == null)
        {
            log.warn("Clue reward widget (73,10) not found");
            return;
        }

        Widget[] children = rewardWidget.getChildren();
        if (children == null || children.length == 0)
        {
            log.warn("Clue reward widget has no children");
            return;
        }

        List<ItemStack> items = new ArrayList<>();
        for (Widget child : children)
        {
            if (child != null && child.getItemId() > 0 && child.getItemQuantity() > 0)
            {
                items.add(new ItemStack(child.getItemId(), child.getItemQuantity()));
            }
        }

        if (items.isEmpty())
        {
            log.warn("No valid items in clue reward widget");
            return;
        }

        // lastChestSource was set by detectChestSource() in onChatMessage
        String source = (lastChestSource != null && !lastChestSource.isEmpty())
                ? lastChestSource
                : "Clue Scroll";
        lastChestSource = null;

        log.info("Clue reward: {} items from '{}'", items.size(), source);
        lootManager.processPlayerLoot(source, items);
    }

    // ==================== UTILITY ====================

    /**
     * Parses chat message text to determine the source name for the next
     * PlayerLootReceived event. Sets lastChestSource when a completion message
     * is recognised. Call this from onChatMessage with the lowercased message.
     *
     * @param lower the lowercased chat message text
     */
    private void detectChestSource(String lower)
    {
        // Tempoross — handled via inventory snapshot, not PlayerLootReceived
        if (lower.contains("subdued the spirit") || lower.contains("you have helped to subdue"))
        {
            return; // handled in onChatMessage
        }

        // Wintertodt
        if (lower.contains("the cold of the wintertodt") || lower.contains("wintertodt"))
        {
            lastChestSource = "Wintertodt";
            log.info("Source set: Wintertodt");
            return;
        }

        // Clue scrolls
        if (lower.contains("you have completed") && lower.contains("treasure trail"))
        {
            if      (lower.contains("beginner")) lastChestSource = "Beginner Clue";
            else if (lower.contains("easy"))     lastChestSource = "Easy Clue";
            else if (lower.contains("medium"))   lastChestSource = "Medium Clue";
            else if (lower.contains("hard"))     lastChestSource = "Hard Clue";
            else if (lower.contains("elite"))    lastChestSource = "Elite Clue";
            else if (lower.contains("master"))   lastChestSource = "Master Clue";
            else                                  lastChestSource = "Clue Scroll";
            log.info("Source set: {}", lastChestSource);
            return;
        }

        if (lower.contains("branda the fire queen") || lower.contains("eldric the ice king") || lower.contains("royal titans"))
        {
            lastChestSource = "Royal Titans";
            log.info("RuneAlytics: Source set to Royal Titans from chat");
            return;
        }

        // Raids
        if (lower.contains("congratulations - your raid is complete")
                || lower.contains("congratulations! your raid is complete"))
        {
            // onWidgetLoaded already set lastChestSource for CoX/ToA;
            // this fallback covers edge cases where widget fires after chat
            if (lastChestSource == null)
            {
                lastChestSource = "Chambers of Xeric";
                log.info("Source set (chat fallback): Chambers of Xeric");
            }
            return;
        }

        if (lower.contains("theatre of blood") && lower.contains("complete"))
        {
            if (lastChestSource == null)
            {
                lastChestSource = "Theatre of Blood";
                log.info("Source set (chat fallback): Theatre of Blood");
            }
            return;
        }

        if (lower.contains("tombs of amascut") && lower.contains("complete"))
        {
            if (lastChestSource == null)
            {
                lastChestSource = "Tombs of Amascut";
                log.info("Source set (chat fallback): Tombs of Amascut");
            }
            return;
        }

        // Gauntlet
        if (lower.contains("gauntlet") && lower.contains("complete"))
        {
            if (lastChestSource == null)
            {
                lastChestSource = lower.contains("corrupted")
                        ? "Corrupted Gauntlet"
                        : "The Gauntlet";
                log.info("Source set (chat fallback): {}", lastChestSource);
            }
        }
    }

    /**
     * Builds a snapshot of the player's current inventory as a list of ItemStacks.
     * Returns an empty list if the inventory container is unavailable.
     *
     * @return non-null list of items currently in the inventory
     */
    private List<ItemStack> getCurrentInventory()
    {
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null)
        {
            return Collections.emptyList();
        }

        List<ItemStack> items = new ArrayList<>();
        Item[] containerItems = inventory.getItems();

        if (containerItems != null)
        {
            for (Item item : containerItems)
            {
                if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                {
                    items.add(new ItemStack(item.getId(), item.getQuantity()));
                }
            }
        }

        return items;
    }

    /**
     * Returns the items that appear in {@code after} but not (or in greater
     * quantity than) in {@code before}.  Used to diff inventory snapshots for
     * Tempoross reward detection.
     *
     * @param before inventory snapshot taken before rewards
     * @param after  inventory snapshot taken after rewards
     * @return list of net-new ItemStacks gained between the two snapshots
     */
    private List<ItemStack> findNewItems(List<ItemStack> before, List<ItemStack> after)
    {
        // Sum quantities per item ID in the before snapshot
        Map<Integer, Integer> beforeMap = new HashMap<>();
        for (ItemStack item : before)
        {
            beforeMap.merge(item.getId(), item.getQuantity(), Integer::sum);
        }

        // Sum quantities per item ID in the after snapshot
        Map<Integer, Integer> afterMap = new HashMap<>();
        for (ItemStack item : after)
        {
            afterMap.merge(item.getId(), item.getQuantity(), Integer::sum);
        }

        // Return only the delta (items that increased in quantity)
        List<ItemStack> newItems = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : afterMap.entrySet())
        {
            int itemId = entry.getKey();
            int delta = entry.getValue() - beforeMap.getOrDefault(itemId, 0);
            if (delta > 0)
            {
                newItems.add(new ItemStack(itemId, delta));
            }
        }

        return newItems;
    }

    /**
     * Checks whether the stored auth token is still valid for the currently
     * logged-in RSN.  Updates RuneAlyticsState and refreshes the settings panel UI.
     */
    private void checkVerificationStatus()
    {
        if (client.getLocalPlayer() == null)
        {
            log.warn("Local player is null — cannot check verification");
            return;
        }

        String rsn = client.getLocalPlayer().getName();
        String token = config.authToken();

        if (rsn == null || rsn.isEmpty() || token == null || token.isEmpty())
        {
            log.debug("Missing RSN or token — skipping auto-verification");
            return;
        }

        log.info("Auto-verifying RSN '{}' with stored token", rsn);

        executorService.submit(() -> {
            try
            {
                RunealyticsApiClient apiClient = injector.getInstance(RunealyticsApiClient.class);
                boolean verified = apiClient.verifyToken(token, rsn);

                if (verified)
                {
                    log.info("Auto-verification succeeded for '{}'", rsn);
                    state.setVerified(true);
                    state.setVerifiedUsername(rsn);
                    state.setVerificationCode(token);
                }
                else
                {
                    log.warn("Auto-verification failed for '{}' — token may be invalid", rsn);
                    state.setVerified(false);
                    state.setVerifiedUsername(null);
                }

                SwingUtilities.invokeLater(() -> {
                    if (mainPanel != null)
                    {
                        RuneAlyticsSettingsPanel settingsPanel =
                                injector.getInstance(RuneAlyticsSettingsPanel.class);
                        settingsPanel.updateVerificationStatus(verified, verified ? rsn : null);
                        mainPanel.revalidate();
                        mainPanel.repaint();
                    }
                });
            }
            catch (Exception e)
            {
                log.error("Exception during auto-verification for '{}'", rsn, e);
            }
        });
    }

    /**
     * Logs the current plugin configuration for debug purposes.
     * Called once during startUp().
     */
    private void logConfiguration()
    {
        log.info("=== RUNEALYTICS CONFIGURATION ===");
        log.info("Enable Loot Tracking : {}", config.enableLootTracking());
        log.info("Track All NPCs       : {}", config.trackAllNpcs());
        log.info("Min Loot Value       : {}", config.minimumLootValue());
        log.info("Sync to Server       : {}", config.syncLootToServer());
        log.info("Auto Verification    : {}", config.enableAutoVerification());
        log.info("API URL              : {}", config.apiUrl());
        log.info("=================================");
    }

    /**
     * Loads the plugin icon from resources, falling back to a generated
     * orange square if the image file is missing.
     *
     * @return the plugin icon as a BufferedImage
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
            log.debug("Plugin icon not found, using fallback", e);
        }

        // Fallback: orange square
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