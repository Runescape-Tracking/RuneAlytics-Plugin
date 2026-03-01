package com.runealytics;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static com.runealytics.RuneAlyticsPanel.FEATURE_LOOT;
import static com.runealytics.RuneAlyticsPanel.FEATURE_MATCHES;

@Slf4j
@PluginDescriptor(
        name        = "RuneAlytics",
        description = "Complete analytics and tracking for Old School RuneScape",
        tags        = {"analytics", "tracking", "loot", "stats", "runealytics"}
)
public class RuneAlyticsPlugin extends Plugin
{
    // ═════════════════════════════════════════════════════════════════════════
    //  WIDGET GROUP-IDs  (mirrors the constants defined in LootTrackerManager)
    // ═════════════════════════════════════════════════════════════════════════
    // These are referenced only inside this plugin to dispatch to the manager.

    // ── Standard reward containers ────────────────────────────────────────────
    /** Barrows reward chest (container-read path) */
    static final int WIDGET_BARROWS           = LootTrackerManager.WIDGET_BARROWS;
    /** Chambers of Xeric chest */
    static final int WIDGET_COX               = LootTrackerManager.WIDGET_COX;
    /** Theatre of Blood chest */
    static final int WIDGET_TOB               = LootTrackerManager.WIDGET_TOB;
    /** Tombs of Amascut chest */
    static final int WIDGET_TOA               = LootTrackerManager.WIDGET_TOA;
    /** Corrupted Gauntlet chest */
    static final int WIDGET_CORRUPTED_GAUNTLET= LootTrackerManager.WIDGET_CORRUPTED_GAUNTLET;
    /** Normal Gauntlet chest */
    static final int WIDGET_GAUNTLET          = LootTrackerManager.WIDGET_GAUNTLET;
    /** Nightmare / Phosani chest */
    static final int WIDGET_NIGHTMARE         = LootTrackerManager.WIDGET_NIGHTMARE;
    /** Zalcano chest */
    static final int WIDGET_ZALCANO           = LootTrackerManager.WIDGET_ZALCANO;

    // ── Widget-tree reads (no fixed InventoryID) ─────────────────────────────
    /** Tempoross reward pool */
    static final int WIDGET_TEMPOROSS         = LootTrackerManager.WIDGET_TEMPOROSS;
    /** Wintertodt reward crate */
    static final int WIDGET_WINTERTODT        = LootTrackerManager.WIDGET_WINTERTODT;
    /** Clue scroll casket */
    static final int WIDGET_CLUE              = LootTrackerManager.WIDGET_CLUE;
    /** Royal Titans (Varlamore lair) */
    static final int WIDGET_ROYAL_TITANS      = LootTrackerManager.WIDGET_ROYAL_TITANS;
    /** Yama reward */
    static final int WIDGET_YAMA              = LootTrackerManager.WIDGET_YAMA;
    /** Fortis Colosseum chest */
    static final int WIDGET_COLOSSEUM         = LootTrackerManager.WIDGET_COLOSSEUM;
    /** Hespori flower chest */
    static final int WIDGET_HESPORI           = LootTrackerManager.WIDGET_HESPORI;

    // ── Timing ────────────────────────────────────────────────────────────────
    /** ms after a kill during which spawning ground items are attributed to it */
    private static final long GROUND_ITEM_WINDOW_MS      = 3_000;
    /** seconds before we clear lastKilledBoss to avoid stale attributions */
    private static final long BOSS_CLEAR_TIMEOUT_SECONDS = 30;

    // ═════════════════════════════════════════════════════════════════════════
    //  INJECTED FIELDS
    // ═════════════════════════════════════════════════════════════════════════

    @Inject private Client                   client;
    @Inject private ClientThread             clientThread;
    @Inject private RunealyticsConfig        config;
    @Inject private ClientToolbar            clientToolbar;
    @Inject private OkHttpClient             okHttpClient;
    @Inject private Gson                     gson;
    @Inject private ConfigManager            configManager;
    @Inject private ItemManager              itemManager;
    @Inject private LootTrackerManager       lootManager;
    @Inject private RuneAlyticsState         state;
    @Inject private ScheduledExecutorService executorService;
    @Inject private XpTrackerManager         xpTrackerManager;
    @Inject private RunealyticsApiClient     apiClient;

    // ── UI ───────────────────────────────────────────────────────────────────
    @Getter private RuneAlyticsPanel mainPanel;
    private NavigationButton navButton;

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT TRACKING STATE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Inventory snapshot taken just before Tempoross / Wintertodt rewards arrive.
     * Used to diff new items out of the inventory rather than reading the widget.
     */
    private List<ItemStack> inventorySnapshot = null;

    /**
     * Whether we are waiting for Tempoross inventory changes after the
     * subdual chat message.
     */
    private boolean waitingForTemporossLoot = false;

    /**
     * Whether we are waiting for Wintertodt inventory changes after the
     * reward crate message.
     */
    private boolean waitingForWintertodtLoot = false;

    /**
     * The NPC that most recently dropped loot, used to attribute nearby
     * ground-item spawns (ItemSpawned fallback path).
     */
    private NPC lastKilledBoss;

    /**
     * When {@link #lastKilledBoss} was set; used to expire the attribution window.
     */
    private Instant lastKillTime;

    /**
     * Buffer of ground items collected within the attribution window.
     * Flushed as a batch 500ms after the first item arrives.
     */
    private final List<ItemStack> groundItemBuffer = new ArrayList<>();

    /**
     * Guards against scheduling multiple ground-item flush tasks at once.
     */
    private final AtomicBoolean groundItemFlushScheduled = new AtomicBoolean(false);

    /**
     * Source name for the next PlayerLootReceived event.
     * Set by onWidgetLoaded or onChatMessage; consumed and cleared in
     * onPlayerLootReceived.
     */
    private String lastChestSource = null;

    /** Previous XP values per skill for delta calculation. */
    private final Map<Skill, Integer> previousXp = new EnumMap<>(Skill.class);

    // ═════════════════════════════════════════════════════════════════════════
    //  STARTUP / SHUTDOWN
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void startUp()
    {
        log.info("RuneAlytics starting");
        logConfiguration();

        mainPanel = injector.getInstance(RuneAlyticsPanel.class);
        LootTrackerPanel lootPanel = injector.getInstance(LootTrackerPanel.class);

        // Register tabs with their feature-flag keys.
        // The server controls visibility; tabs not enabled for this account are hidden.
        mainPanel.addTab("Loot Tracker",  FEATURE_LOOT,    lootPanel);
        // Uncomment when MatchFinderPanel exists:
        MatchmakingPanel matchmakingPanel = injector.getInstance(MatchmakingPanel.class);
        mainPanel.addTab("Match Finder", FEATURE_MATCHES, matchmakingPanel);

        navButton = NavigationButton.builder()
                .tooltip("RuneAlytics")
                .icon(loadPluginIcon())
                .priority(5)
                .panel(mainPanel)
                .build();

        clientToolbar.addNavigation(navButton);
        lootManager.setPanel(lootPanel);
        lootManager.initialize();

        log.info("RuneAlytics started");
    }

    @Override
    protected void shutDown()
    {
        log.info("RuneAlytics shutting down");
        lootManager.shutdown();
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        state.reset();
    }

    private void clearTransientLootState()
    {
        lastKilledBoss           = null;
        lastKillTime             = null;
        lastChestSource          = null;
        inventorySnapshot        = null;
        waitingForTemporossLoot  = false;
        waitingForWintertodtLoot = false;
        groundItemBuffer.clear();
        groundItemFlushScheduled.set(false);
    }


    @Provides
    RunealyticsConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(RunealyticsConfig.class);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 1 – NPC GROUND DROPS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fired by RuneLite when an NPC drops items on the ground.
     * Covers all standard monsters, bosses, wilderness NPCs, etc.
     */
    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        if (!config.enableLootTracking()) return;

        NPC npc = event.getNpc();
        if (npc == null) return;

        // Track for ground-item fallback attribution
        lastKilledBoss = npc;
        lastKillTime   = Instant.now();

        Collection<net.runelite.client.game.ItemStack> rlItems = event.getItems();
        if (rlItems == null || rlItems.isEmpty()) return;

        List<ItemStack> items = new ArrayList<>();
        for (net.runelite.client.game.ItemStack i : rlItems)
            items.add(new ItemStack(i.getId(), i.getQuantity()));

        lootManager.processNpcLoot(npc, items);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 2 – PLAYER / CHEST LOOT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fired by RuneLite for non-NPC loot sources: raids, Barrows, Gauntlet,
     * PvP piles, etc.  We consume {@link #lastChestSource} (set by
     * onWidgetLoaded / onChatMessage) to label the source correctly.
     */
    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        if (!config.enableLootTracking()) return;

        Collection<net.runelite.client.game.ItemStack> rlItems = event.getItems();
        if (rlItems == null || rlItems.isEmpty()) return;

        // Consume pending source; fall back to "Unknown Chest"
        String source = (lastChestSource != null && !lastChestSource.isEmpty())
                ? lastChestSource : "Unknown Chest";
        lastChestSource = null;

        // Normalise Tempoross variants
        if (source.toLowerCase().contains("reward pool")
                || source.toLowerCase().contains("casket (tempoross)"))
            source = "Tempoross";

        List<ItemStack> items = new ArrayList<>();
        for (net.runelite.client.game.ItemStack i : rlItems)
            items.add(new ItemStack(i.getId(), i.getQuantity()));

        lootManager.processPlayerLoot(source, items);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 3 – WIDGET LOADED  (primes source + triggers container reads)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fires whenever a UI widget is loaded.  We use this to:
     * <ol>
     *   <li>Set {@link #lastChestSource} so PlayerLootReceived is labelled.</li>
     *   <li>Directly read the reward container / widget tree (more reliable than
     *       waiting for PlayerLootReceived in some cases).</li>
     * </ol>
     */
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (!config.enableLootTracking()) return;

        int gid = event.getGroupId();
        log.info("WidgetLoaded: groupId={}", gid);

        // ── Standard container reads ──────────────────────────────────────────
        if (gid == WIDGET_BARROWS)
        {
            lastChestSource = "Barrows";
            lootManager.readRewardContainer("Barrows", 141);
        }
        else if (gid == WIDGET_COX)
        {
            lastChestSource = "Chambers of Xeric";
            lootManager.readRewardContainer("Chambers of Xeric", 122);
        }
        else if (gid == WIDGET_TOB)
        {
            lastChestSource = "Theatre of Blood";
            lootManager.readRewardContainer("Theatre of Blood", 612);
        }
        else if (gid == WIDGET_TOA)
        {
            lastChestSource = "Tombs of Amascut";
            lootManager.readRewardContainer("Tombs of Amascut", 801);
        }
        else if (gid == WIDGET_CORRUPTED_GAUNTLET)
        {
            lastChestSource = "Corrupted Gauntlet";
            lootManager.readRewardContainer("Corrupted Gauntlet", 179);
        }
        else if (gid == WIDGET_GAUNTLET)
        {
            lastChestSource = "The Gauntlet";
            lootManager.readRewardContainer("The Gauntlet", 179);
        }
        else if (gid == WIDGET_NIGHTMARE)
        {
            // Determine Phosani vs normal from the source primed by chat
            String nm = (lastChestSource != null && lastChestSource.contains("Phosani"))
                    ? "Phosani's Nightmare" : "The Nightmare";
            lastChestSource = nm;
            lootManager.readRewardContainer(nm, 646);
        }
        else if (gid == WIDGET_ZALCANO)
        {
            lastChestSource = "Zalcano";
            lootManager.readRewardContainer("Zalcano", 631);
        }

        // ── Widget-tree reads (no standard InventoryID) ───────────────────────
        else if (gid == WIDGET_ROYAL_TITANS)
        {
            if (lastChestSource == null) lastChestSource = "Royal Titans";
            lootManager.readWidgetLoot(lastChestSource, WIDGET_ROYAL_TITANS, 100);
        }
        else if (gid == WIDGET_YAMA)
        {
            lastChestSource = "Yama";
            lootManager.readWidgetLoot("Yama", WIDGET_YAMA, 100);
        }
        else if (gid == WIDGET_COLOSSEUM)
        {
            lastChestSource = "Fortis Colosseum";
            lootManager.readWidgetLoot("Fortis Colosseum", WIDGET_COLOSSEUM, 150);
        }
        else if (gid == WIDGET_HESPORI)
        {
            lastChestSource = "Hespori";
            lootManager.readWidgetLoot("Hespori", WIDGET_HESPORI, 60);
        }
        else if (gid == WIDGET_WINTERTODT)
        {
            lastChestSource = "Wintertodt";
            // Also start inventory-diff path as a fallback
            clientThread.invokeLater(() -> {
                inventorySnapshot     = getCurrentInventory();
                waitingForWintertodtLoot = true;
            });
            lootManager.readWidgetLoot("Wintertodt", WIDGET_WINTERTODT, 80);
        }
        else if (gid == WIDGET_TEMPOROSS)
        {
            // Widget path – try reading the reward pool widget tree
            lootManager.readWidgetLoot("Tempoross", WIDGET_TEMPOROSS, 80);
            // Inventory-diff is the reliable fallback (already started via chat)
        }
        else if (gid == WIDGET_CLUE)
        {
            // clue source was set by onChatMessage (treasure trail message)
            String src = (lastChestSource != null) ? lastChestSource : "Clue Scroll";
            lastChestSource = null;
            lootManager.readClueReward(src);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 4 – INVENTORY DIFF  (Tempoross / Wintertodt fallback)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fires when any item container changes.  Used exclusively as a fallback
     * for Tempoross and Wintertodt when the widget-read path does not capture
     * all items (can depend on client timing).
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!config.enableLootTracking()) return;
        if (event.getContainerId() != InventoryID.INVENTORY.getId()) return;
        if (!waitingForTemporossLoot && !waitingForWintertodtLoot) return;

        clientThread.invokeLater(() -> {
            if (inventorySnapshot == null) return;

            List<ItemStack> current = getCurrentInventory();
            List<ItemStack> gained  = diffInventory(inventorySnapshot, current);

            if (gained.isEmpty()) return;  // wait another tick

            if (waitingForTemporossLoot)
            {
                lootManager.processInventoryDiff("Tempoross", gained);
                waitingForTemporossLoot = false;
            }
            else if (waitingForWintertodtLoot)
            {
                lootManager.processInventoryDiff("Wintertodt", gained);
                waitingForWintertodtLoot = false;
            }

            inventorySnapshot = null;
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 5 – GROUND ITEM SPAWN  (fallback for unsupported new content)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fires when any item appears on the ground.  Only used as a fallback:
     * items spawning within GROUND_ITEM_WINDOW_MS of a recorded kill and
     * within 5 tiles are batched and sent to the manager as a group.
     *
     * <p>This does NOT duplicate items from onNpcLootReceived because the
     * manager applies its own deduplication via kill-record timestamps.</p>
     */
    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (!config.enableLootTracking() || lastKilledBoss == null || lastKillTime == null)
            return;

        long elapsedMs = Instant.now().toEpochMilli() - lastKillTime.toEpochMilli();
        if (elapsedMs > GROUND_ITEM_WINDOW_MS) return;

        WorldPoint itemLoc = event.getTile().getWorldLocation();
        WorldPoint bossLoc = lastKilledBoss.getWorldLocation();
        if (itemLoc == null || bossLoc == null || itemLoc.distanceTo(bossLoc) > 5) return;

        TileItem tile = event.getItem();
        groundItemBuffer.add(new ItemStack(tile.getId(), tile.getQuantity()));

        if (groundItemFlushScheduled.compareAndSet(false, true))
        {
            final NPC boss = lastKilledBoss;
            executorService.schedule(() -> clientThread.invokeLater(() -> {
                if (!groundItemBuffer.isEmpty())
                {
                    List<ItemStack> batch = new ArrayList<>(groundItemBuffer);
                    groundItemBuffer.clear();
                    groundItemFlushScheduled.set(false);
                    lootManager.processGroundItemBatch(boss, batch);
                }
                else
                {
                    groundItemFlushScheduled.set(false);
                }
            }), 500, TimeUnit.MILLISECONDS);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CHAT MESSAGE  (sets lastChestSource + inventory snapshots)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Listens for game / spam messages to:
     * <ul>
     *   <li>Prime {@link #lastChestSource} for the upcoming PlayerLootReceived</li>
     *   <li>Snapshot inventory before Tempoross / Wintertodt rewards arrive</li>
     *   <li>Forward KC messages to the manager for logging</li>
     * </ul>
     */
    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!config.enableLootTracking()) return;

        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM) return;

        String msg   = event.getMessage();
        String lower = msg.toLowerCase();

        // ── Inventory snapshot triggers ───────────────────────────────────────
        if (lower.contains("subdued the spirit") || lower.contains("you have helped to subdue"))
        {
            clientThread.invokeLater(() -> {
                inventorySnapshot     = getCurrentInventory();
                waitingForTemporossLoot = true;
                log.info("Tempoross: inventory snapshot taken ({} items)",
                        inventorySnapshot.size());
            });
            lastChestSource = "Tempoross";
            return;
        }

        if (lower.contains("supply crate") && lower.contains("wintertodt"))
        {
            clientThread.invokeLater(() -> {
                inventorySnapshot        = getCurrentInventory();
                waitingForWintertodtLoot = true;
            });
        }

        // ── KC messages ───────────────────────────────────────────────────────
        if (msg.contains("kill count is:") || msg.contains("killcount is:"))
        {
            lootManager.parseKillCountMessage(msg);
        }

        // ── Chest source detection ────────────────────────────────────────────
        String detected = lootManager.detectChestSource(lower);
        if (detected != null && lastChestSource == null)
        {
            lastChestSource = detected;
            log.info("Chat: chest source set to '{}'", detected);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GAME TICK  (expire stale kill attribution)
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (lastKillTime != null
                && ChronoUnit.SECONDS.between(lastKillTime, Instant.now())
                > BOSS_CLEAR_TIMEOUT_SECONDS)
        {
            lastKilledBoss = null;
            lastKillTime   = null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GAME STATE CHANGE  (login / logout)
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState gs = event.getGameState();
        log.info("GameState: {}", gs);

        if (gs == GameState.LOGIN_SCREEN)
        {
            state.setLoggedIn(false);

            // Fully hide Loot + Match Finder while at login screen
            SwingUtilities.invokeLater(() -> mainPanel.showLoggedOutState());
            return;
        }

        if (gs == GameState.HOPPING)
        {
            // Do NOT reset panel on world hop
            return;
        }

        if (gs == GameState.LOGGED_IN)
        {
            state.setLoggedIn(true);

            String username = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getName()
                    : null;

            if (username == null || username.isEmpty())
            {
                return;
            }

            final String rsn = username.toLowerCase();

            // If not verified → show verification only
            if (!state.isVerified())
            {
                SwingUtilities.invokeLater(() ->
                        mainPanel.showVerificationOnly());
                return;
            }

            // Verified → fetch feature flags async
            executorService.submit(() ->
            {
                Map<String, Boolean> flags = apiClient.fetchFeatureFlags(rsn);

                SwingUtilities.invokeLater(() ->
                        mainPanel.showMainFeatures(
                                flags.getOrDefault(FEATURE_LOOT, false),
                                flags.getOrDefault(FEATURE_MATCHES, false)
                        )
                );
            });
        }
    }

    private void handlePostLogin()
    {
        String username = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName()
                : null;

        if (username == null || username.isEmpty())
            return;

        // Not verified → show only verification tab
        if (!state.isVerified())
        {
            SwingUtilities.invokeLater(() ->
                    mainPanel.showVerificationOnly());
            return;
        }

        // Verified → fetch feature flags
        executorService.submit(() -> {
            Map<String, Boolean> flags = apiClient.fetchFeatureFlags(username.toLowerCase());

            SwingUtilities.invokeLater(() ->
                    mainPanel.applyFeatureFlags(flags));
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PLAYER SPAWNED  (post-login data load + auto-verification)
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned event)
    {
        if (event.getPlayer() != client.getLocalPlayer()) return;

        log.info("Local player spawned");

        if (config.enableAutoVerification() && !state.isVerified())
        {
            checkVerificationStatus();
        }

        // Load local data 2s after spawn to give the client time to settle
        executorService.schedule(() -> {
            if (state.isVerified())
            {
                log.info("Post-login: loading local loot data");
                lootManager.loadFromStorage();
            }
            else
            {
                log.warn("Not verified – skipping data load");
            }
        }, 2, TimeUnit.SECONDS);

        // Restore the tab the player was last on.
        // Delayed slightly to let the flag-fetch and layout settle first.
        executorService.schedule(
                () -> SwingUtilities.invokeLater(mainPanel::restoreLastTab),
                2_500, TimeUnit.MILLISECONDS
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  XP TRACKING
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (!config.enableXpTracking() || !state.isVerified()) return;

        Skill skill = event.getSkill();
        if (skill == Skill.OVERALL) return;

        int current = event.getXp();
        Integer prev = previousXp.get(skill);

        if (prev == null) { previousXp.put(skill, current); return; }

        int gained = current - prev;
        previousXp.put(skill, current);

        if (gained <= 0 || gained < config.minXpGain()) return;

        String username = state.getVerifiedUsername();
        String token    = config.authToken();
        if (username == null || token == null) return;

        executorService.submit(() ->
                xpTrackerManager.recordXpGain(token, username, skill, gained, current, event.getLevel()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCHEDULED SYNC  (background upload of unsynced kills every 60s)
    // ═════════════════════════════════════════════════════════════════════════

    @net.runelite.client.task.Schedule(
            period = 60000,
            unit   = ChronoUnit.MILLIS,
            asynchronous = true
    )
    public void syncDataScheduled()
    {
        if (!config.syncLootToServer() || !state.isLoggedIn() || !state.isVerified()) return;
        lootManager.uploadUnsyncedKills();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns a snapshot of the player's current inventory as ItemStacks.
     * Returns an empty list if the inventory container is unavailable.
     */
    private List<ItemStack> getCurrentInventory()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return Collections.emptyList();

        List<ItemStack> items = new ArrayList<>();
        for (Item item : inv.getItems())
        {
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                items.add(new ItemStack(item.getId(), item.getQuantity()));
        }
        return items;
    }

    /**
     * Returns the net-new items gained between two inventory snapshots.
     * Items whose quantity increased appear in the result.
     *
     * @param before snapshot taken before the reward
     * @param after  snapshot taken after the reward
     * @return list of gained ItemStacks (delta quantities only)
     */
    private List<ItemStack> diffInventory(List<ItemStack> before, List<ItemStack> after)
    {
        Map<Integer, Integer> beforeMap = new HashMap<>();
        for (ItemStack i : before) beforeMap.merge(i.getId(), i.getQuantity(), Integer::sum);

        Map<Integer, Integer> afterMap = new HashMap<>();
        for (ItemStack i : after) afterMap.merge(i.getId(), i.getQuantity(), Integer::sum);

        List<ItemStack> gained = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : afterMap.entrySet())
        {
            int delta = e.getValue() - beforeMap.getOrDefault(e.getKey(), 0);
            if (delta > 0) gained.add(new ItemStack(e.getKey(), delta));
        }
        return gained;
    }

    /**
     * Checks whether the stored auth token is valid for the currently
     * logged-in RSN, then updates RuneAlyticsState and the settings panel.
     */
    private void checkVerificationStatus()
    {
        if (client.getLocalPlayer() == null) return;
        String rsn   = client.getLocalPlayer().getName();
        String token = config.authToken();
        if (rsn == null || token == null || token.isEmpty()) return;

        executorService.submit(() -> {
            try
            {
                RunealyticsApiClient api = injector.getInstance(RunealyticsApiClient.class);
                boolean verified = api.verifyToken(token, rsn);

                if (verified)
                {
                    state.setVerified(true);
                    state.setVerifiedUsername(rsn);
                    state.setVerificationCode(token);
                }
                else
                {
                    state.setVerified(false);
                    state.setVerifiedUsername(null);
                }

                SwingUtilities.invokeLater(() -> {
                    RuneAlyticsSettingsPanel sp = injector.getInstance(RuneAlyticsSettingsPanel.class);
                    sp.updateVerificationStatus(verified, verified ? rsn : null);
                });
            }
            catch (Exception e)
            {
                log.error("Auto-verification error for '{}'", rsn, e);
            }
        });
    }

    /** Logs the active configuration at startup for easy debug inspection. */
    private void logConfiguration()
    {
        log.info("=== RUNEALYTICS CONFIG ===");
        log.info("Loot tracking : {}", config.enableLootTracking());
        log.info("Track all NPCs: {}", config.trackAllNpcs());
        log.info("Min loot value: {}", config.minimumLootValue());
        log.info("Sync to server: {}", config.syncLootToServer());
        log.info("Auto-verify   : {}", config.enableAutoVerification());
        log.info("API URL       : {}", config.apiUrl());
        log.info("=========================");
    }

    /**
     * Loads the plugin icon from resources, falling back to an orange square
     * if the image file is missing.
     */
    private BufferedImage loadPluginIcon()
    {
        try
        {
            BufferedImage img = ImageUtil.loadImageResource(getClass(), "/runealytics_icon.png");
            if (img != null) return img;
        }
        catch (Exception e)
        {
            log.debug("Plugin icon not found, using fallback", e);
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