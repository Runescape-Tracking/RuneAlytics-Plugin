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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.runealytics.RuneAlyticsPanel.FEATURE_LOOT;
import static com.runealytics.RuneAlyticsPanel.FEATURE_MATCHES;

@Slf4j
@PluginDescriptor(
        name = "RuneAlytics",
        description = "Advanced loot tracking and analytics",
        tags = {"loot", "tracking", "analytics"},
        enabledByDefault = true
)
public class RuneAlyticsPlugin extends Plugin
{
    // ═════════════════════════════════════════════════════════════════════════
    //  WIDGET GROUP-IDs  (all sourced from {@link LootTrackerManager} constants)
    // ═════════════════════════════════════════════════════════════════════════

    /** Barrows reward chest */
    static final int WIDGET_BARROWS            = LootTrackerManager.WIDGET_BARROWS;
    /** Chambers of Xeric chest */
    static final int WIDGET_COX                = LootTrackerManager.WIDGET_COX;
    /** Theatre of Blood chest */
    static final int WIDGET_TOB                = LootTrackerManager.WIDGET_TOB;
    /** Tombs of Amascut chest */
    static final int WIDGET_TOA                = LootTrackerManager.WIDGET_TOA;
    /** Corrupted Gauntlet chest */
    static final int WIDGET_CORRUPTED_GAUNTLET = LootTrackerManager.WIDGET_CORRUPTED_GAUNTLET;
    /** Normal Gauntlet chest */
    static final int WIDGET_GAUNTLET           = LootTrackerManager.WIDGET_GAUNTLET;
    /** Nightmare / Phosani chest */
    static final int WIDGET_NIGHTMARE          = LootTrackerManager.WIDGET_NIGHTMARE;
    /** Zalcano chest */
    static final int WIDGET_ZALCANO            = LootTrackerManager.WIDGET_ZALCANO;
    /** Tempoross reward pool */
    static final int WIDGET_TEMPOROSS          = LootTrackerManager.WIDGET_TEMPOROSS;
    /** Wintertodt reward crate */
    static final int WIDGET_WINTERTODT         = LootTrackerManager.WIDGET_WINTERTODT;
    /** Clue scroll casket */
    static final int WIDGET_CLUE               = LootTrackerManager.WIDGET_CLUE;
    /** Royal Titans (Varlamore lair) */
    static final int WIDGET_ROYAL_TITANS       = LootTrackerManager.WIDGET_ROYAL_TITANS;
    /** Yama reward */
    static final int WIDGET_YAMA               = LootTrackerManager.WIDGET_YAMA;
    /** Fortis Colosseum chest */
    static final int WIDGET_COLOSSEUM          = LootTrackerManager.WIDGET_COLOSSEUM;
    /** Hespori flower chest */
    static final int WIDGET_HESPORI            = LootTrackerManager.WIDGET_HESPORI;
    /**
     * The Whisperer reward interface (Desert Treasure 2, group 834).
     *
     * @see LootTrackerManager#WIDGET_WHISPERER
     * @see LootTrackerManager#WIDGET_ITEM_SEARCH_DEPTH
     */
    static final int WIDGET_WHISPERER          = LootTrackerManager.WIDGET_WHISPERER;

    // ── Timing ────────────────────────────────────────────────────────────────
    /** ms after a kill during which spawning ground items are attributed to it */
    private static final long GROUND_ITEM_WINDOW_MS      = 3_000;
    /** seconds before we clear lastKilledBoss to avoid stale attributions */
    private static final long BOSS_CLEAR_TIMEOUT_SECONDS = 30;

    // ─────────────────────────────────────────────────────────────────────────
    //  PICKPOCKET / THIEVING TRACKING CONSTANTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * How long (ms) after the most recent "Pickpocket" click we continue treating
     * inventory changes as belonging to that pickpocket action.
     */
    private static final long PICKPOCKET_WINDOW_MS = 1_800;
    private static final long SKILLING_SESSION_MS  = 8_000;

    private static final Set<Skill> SKILLING_TRACKED = java.util.EnumSet.of(
            Skill.WOODCUTTING, Skill.FISHING,  Skill.MINING,    Skill.FARMING,
            Skill.HUNTER,      Skill.HERBLORE, Skill.RUNECRAFT, Skill.FLETCHING,
            Skill.COOKING,     Skill.SMITHING, Skill.CRAFTING,  Skill.AGILITY
    );

    private final Map<String, List<ItemStack>> skillingSnapshot = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long>            skillingExpiry   = new java.util.concurrent.ConcurrentHashMap<>();

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
    @Inject private BankDataManager          bankDataManager;

    // ── UI ───────────────────────────────────────────────────────────────────
    @Getter private RuneAlyticsPanel mainPanel;
    private NavigationButton navButton;

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT TRACKING STATE
    // ═════════════════════════════════════════════════════════════════════════

    private List<ItemStack> inventorySnapshot        = null;
    private boolean         waitingForTemporossLoot  = false;
    private boolean         waitingForWintertodtLoot = false;

    /**
     * True for {@value #WHISPERER_GROUND_ITEM_WINDOW_MS} ms after the Whisperer
     * KC chat message fires. During this window every {@code ItemSpawned} event
     * near the player is collected and attributed to the Whisperer kill.
     */
    private boolean         whispererGroundItemWindow = false;
    /** Timestamp when {@link #whispererGroundItemWindow} was opened. */
    private Instant         whispererKillTime         = null;
    /**
     * Accumulates {@link ItemStack}s collected via {@code ItemSpawned} while
     * {@link #whispererGroundItemWindow} is open.
     */
    private final List<ItemStack> whispererGroundItems = new ArrayList<>();
    private static final long WHISPERER_GROUND_ITEM_WINDOW_MS = 5_000;
    private ScheduledFuture<?> whispererFlushTask;
    private static final int WHISPERER_DEBOUNCE_MS = 450;
    private int whispererParsedKC = -1;

    private NPC             lastKilledBoss           = null;
    private Instant         lastKillTime             = null;

    // ── Ring of Wealth coin auto-pickup detection ─────────────────────────────
    /** Item ID for coins — used to identify RoW auto-collected drops. */
    private static final int ITEM_ID_COINS = 995;
    /** How long (ms) we keep the RoW inventory snapshot after an NPC kill. */
    private static final long ROW_WINDOW_MS = 4_000;
    /** Inventory snapshot taken at NPC kill to diff against after RoW message. */
    private List<ItemStack> rowInventorySnapshot = null;
    /** The boss NPC whose kill opened the current RoW snapshot window. */
    private NPC             rowSnapshotBoss      = null;
    /** Absolute expiry time (ms) for the RoW snapshot window. */
    private long            rowSnapshotExpiry    = 0L;

    private final List<ItemStack>  groundItemBuffer         = new ArrayList<>();
    private final AtomicBoolean    groundItemFlushScheduled = new AtomicBoolean(false);

    private String lastChestSource = null;

    /**
     * Baseline XP snapshot per skill — updated on every {@link StatChanged} event.
     * Used to compute the XP delta (gained this tick) passed to
     * {@link XpTrackerManager#onXpGained}.
     */
    private final Map<Skill, Integer> previousXp = new EnumMap<>(Skill.class);

    // ─────────────────────────────────────────────────────────────────────────
    //  PICKPOCKET / THIEVING STATE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The HTML-stripped NPC / stall name from the most recent "Pickpocket" menu click.
     * Reset to {@code null} when {@link #pickpocketWindowExpiry} passes or when a
     * successful pickpocket loot diff is recorded.
     *
     * <p>Example values: {@code "Master Farmer"}, {@code "Knight of Ardougne"},
     * {@code "Gem Stall"}</p>
     */
    private String  pendingPickpocketNpc = null;

    /**
     * Inventory snapshot taken immediately after (on the next client tick following)
     * a "Pickpocket" click.  Used to diff against the post-pickpocket inventory to
     * determine which items were gained.
     *
     * <p>Updated to the <em>current</em> inventory after each successful diff so
     * that rapid consecutive pickpocket actions are each counted separately.</p>
     */
    private List<ItemStack> pickpocketInventorySnapshot = null;

    /**
     * Absolute time (ms since epoch) at which the pickpocket attribution window closes.
     * Set to {@code System.currentTimeMillis() + PICKPOCKET_WINDOW_MS} on each click,
     * and reset on each successful loot diff so rapid pickpockets keep the window open.
     */
    private long pickpocketWindowExpiry = 0L;

    // ═════════════════════════════════════════════════════════════════════════
    //  STARTUP / SHUTDOWN
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void startUp() throws Exception
    {
        log.info("RuneAlytics starting");
        mainPanel = injector.getInstance(RuneAlyticsPanel.class);

        executorService.execute(() ->
        {
            try
            {
                // Initialize heavy components
                LootTrackerPanel lootPanel = injector.getInstance(LootTrackerPanel.class);
                MatchmakingPanel matchmakingPanel = injector.getInstance(MatchmakingPanel.class);
                RuneAlyticsSettingsPanel settingsPanel = injector.getInstance(RuneAlyticsSettingsPanel.class);

                SwingUtilities.invokeLater(() ->
                {
                    mainPanel.addTab("Loot Tracker", FEATURE_LOOT, lootPanel);
                    mainPanel.addTab("Match Finder", FEATURE_MATCHES, matchmakingPanel);
                    mainPanel.addTab("Settings", FEATURE_VERIFICATION, settingsPanel);

                    navButton = NavigationButton.builder()
                            .tooltip("RuneAlytics")
                            .icon(loadPluginIcon())
                            .priority(1) // Priority 1 ensures it stays near the top/middle
                            .panel(mainPanel)
                            .build();

                    // REMOVE first, then ADD to force a UI refresh
                    clientToolbar.removeNavigation(navButton);
                    clientToolbar.addNavigation(navButton);

                    log.info("RuneAlytics UI forced onto toolbar");
                });
            }
            catch (Exception e)
            {
                log.error("Failed to build RuneAlytics UI", e);
            }
        });
    }

    @Override
    protected void shutDown()
    {
        log.info("RuneAlytics shutting down");

        // Flush any XP that accumulated in the current 30-second window before
        // the executor shuts down, so the player's last session gains are not lost.
        xpTrackerManager.flushImmediate();

        lootManager.shutdown();
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        state.reset();
    }

    private void clearTransientLootState()
    {
        lastKilledBoss            = null;
        lastKillTime              = null;
        lastChestSource           = null;
        inventorySnapshot         = null;
        waitingForTemporossLoot   = false;
        waitingForWintertodtLoot  = false;
        whispererGroundItemWindow = false;
        whispererKillTime         = null;
        whispererGroundItems.clear();
        groundItemBuffer.clear();
        groundItemFlushScheduled.set(false);

        // Clear Ring of Wealth snapshot
        rowInventorySnapshot = null;
        rowSnapshotBoss      = null;
        rowSnapshotExpiry    = 0L;

        // Clear pickpocket state
        pendingPickpocketNpc        = null;
        pickpocketInventorySnapshot = null;
        pickpocketWindowExpiry      = 0L;
    }

    @Provides
    RunealyticsConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(RunealyticsConfig.class);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 1 – NPC GROUND DROPS
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        if (!config.enableLootTracking()) return;

        NPC npc = event.getNpc();
        if (npc == null) return;

        lastKilledBoss = npc;
        lastKillTime   = Instant.now();

        // Snapshot inventory immediately so we can diff it if Ring of Wealth
        // auto-collects coins (those never fire ItemSpawned).
        clientThread.invokeLater(() -> {
            rowInventorySnapshot = getCurrentInventory();
            rowSnapshotBoss      = npc;
            rowSnapshotExpiry    = System.currentTimeMillis() + ROW_WINDOW_MS;
        });

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

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        if (!config.enableLootTracking()) return;

        Collection<net.runelite.client.game.ItemStack> rlItems = event.getItems();
        if (rlItems == null || rlItems.isEmpty()) return;

        String source = (lastChestSource != null && !lastChestSource.isEmpty())
                ? lastChestSource : "Unknown Chest";
        lastChestSource = null;

        if (source.toLowerCase().contains("reward pool")
                || source.toLowerCase().contains("casket (tempoross)"))
            source = "Tempoross";

        List<ItemStack> items = new ArrayList<>();
        for (net.runelite.client.game.ItemStack i : rlItems)
            items.add(new ItemStack(i.getId(), i.getQuantity()));

        lootManager.processPlayerLoot(source, items);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 3 – WIDGET LOADED
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (!config.enableLootTracking()) return;

        int gid = event.getGroupId();
        log.info("WidgetLoaded: groupId={}", gid);

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

        if (gid == WIDGET_WHISPERER)
        {
            log.info("RuneAlytics: Whisperer widget 834 detected — waiting for drops (KC={})",
                    whispererParsedKC);

            whispererGroundItemWindow = true;

            if (whispererFlushTask != null)
                whispererFlushTask.cancel(false);

            whispererFlushTask = executorService.schedule(
                    this::flushWhispererLoot, 450, TimeUnit.MILLISECONDS);
            return;
        }
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
            clientThread.invokeLater(() -> {
                inventorySnapshot        = getCurrentInventory();
                waitingForWintertodtLoot = true;
            });
            lootManager.readWidgetLoot("Wintertodt", WIDGET_WINTERTODT, 80);
        }
        else if (gid == WIDGET_TEMPOROSS)
        {
            lootManager.readWidgetLoot("Tempoross", WIDGET_TEMPOROSS, 80);
        }
        else if (gid == WIDGET_CLUE)
        {
            String src = (lastChestSource != null) ? lastChestSource : "Clue Scroll";
            lastChestSource = null;
            lootManager.readClueReward(src);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 4 – INVENTORY DIFF  (Tempoross / Wintertodt + Pickpocket)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Handles inventory container changes.
     *
     * <p>Serves two purposes:</p>
     * <ol>
     *   <li><b>Tempoross / Wintertodt</b> – existing fallback diff logic.</li>
     *   <li><b>Pickpocket / Thieving</b> – when {@link #pendingPickpocketNpc} is set
     *       and the pickpocket window has not expired, diffs the inventory against
     *       {@link #pickpocketInventorySnapshot} to find the items gained from the
     *       pickpocket.  The snapshot is then advanced to the current inventory so
     *       rapid consecutive pickpockets are each counted as individual loot events.</li>
     * </ol>
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        // ── Bank sync ─────────────────────────────────────────────────────────
        if (event.getContainerId() == InventoryID.BANK.getId()
                && config.enableBankSync()
                && state.isLoggedIn()
                && state.isVerified())
        {
            final String token    = state.getVerificationCode();
            final String username = state.getVerifiedUsername();
            executorService.execute(() ->
                    bankDataManager.syncBankData(
                            token, username,
                            event.getItemContainer(),
                            client.getItemContainer(InventoryID.INVENTORY),
                            client.getItemContainer(InventoryID.EQUIPMENT)));
            return;
        }

        if (!config.enableLootTracking()) return;
        if (event.getContainerId() != InventoryID.INVENTORY.getId()) return;

        clientThread.invokeLater(() ->
        {
            // ── Tempoross / Wintertodt ────────────────────────────────────────
            if ((waitingForTemporossLoot || waitingForWintertodtLoot) && inventorySnapshot != null)
            {
                List<ItemStack> current = getCurrentInventory();
                List<ItemStack> gained  = diffInventory(inventorySnapshot, current);

                if (!gained.isEmpty())
                {
                    if (waitingForTemporossLoot)
                    {
                        lootManager.processInventoryDiff("Tempoross", gained);
                        waitingForTemporossLoot = false;
                        inventorySnapshot = null;
                    }
                    else
                    {
                        lootManager.processInventoryDiff("Wintertodt", gained);
                        waitingForWintertodtLoot = false;
                        inventorySnapshot = null;
                    }
                }
            }

            // ── Skilling loot (runs regardless of pickpocket state) ───────────
            if (!skillingSnapshot.isEmpty() && config.enableLootTracking())
            {
                List<ItemStack> inv = getCurrentInventory();
                long now = System.currentTimeMillis();
                for (String skill : new ArrayList<>(skillingExpiry.keySet()))
                {
                    if (now > skillingExpiry.getOrDefault(skill, 0L))
                    {
                        skillingExpiry.remove(skill);
                        skillingSnapshot.remove(skill);
                        log.debug("Skilling session expired: {}", skill);
                        continue;
                    }
                    List<ItemStack> snap = skillingSnapshot.get(skill);
                    if (snap == null) continue;
                    List<ItemStack> items = diffInventory(snap, inv);
                    if (!items.isEmpty())
                    {
                        lootManager.processSkillLoot(skill, new ArrayList<>(items));
                        skillingSnapshot.put(skill, inv);
                    }
                }
            }

            // ── Pickpocket loot ───────────────────────────────────────────────
            if (!config.enablePickpocketTracking()) return;
            if (pendingPickpocketNpc == null || pickpocketInventorySnapshot == null) return;
            if (System.currentTimeMillis() > pickpocketWindowExpiry)
            {
                log.debug("Pickpocket window expired for '{}'", pendingPickpocketNpc);
                pendingPickpocketNpc        = null;
                pickpocketInventorySnapshot = null;
                return;
            }

            List<ItemStack> current = getCurrentInventory();
            List<ItemStack> gained  = diffInventory(pickpocketInventorySnapshot, current);

            if (!gained.isEmpty())
            {
                String npc = pendingPickpocketNpc;
                List<ItemStack> loot = new ArrayList<>(gained);

                log.debug("Pickpocket diff: '{}' gained {} item type(s)", npc, loot.size());
                lootManager.processPickpocketLoot(npc, loot);

                pickpocketInventorySnapshot = current;
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 6 – PICKPOCKET CLICK DETECTION  (MenuOptionClicked)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Detects "Pickpocket" menu option clicks and snapshots the current inventory
     * so that the subsequent {@link #onItemContainerChanged} call can diff it.
     *
     * <p>Both "Pickpocket" (NPC right-click) and "Pick-pocket" (older variant) are
     * accepted.  The NPC / stall name is extracted from the menu target by stripping
     * RuneScape HTML colour tags.</p>
     *
     * <p>If pickpocket tracking is disabled in config this handler returns early.</p>
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!config.enableLootTracking() || !config.enablePickpocketTracking()) return;

        String option = event.getMenuOption();
        if (option == null) return;

        // Accept both the modern and legacy menu option spellings
        if (!"Pickpocket".equalsIgnoreCase(option) && !"Pick-pocket".equalsIgnoreCase(option))
            return;

        // Strip RuneScape HTML colour tags from the NPC / stall name
        String rawTarget = event.getMenuTarget();
        if (rawTarget == null || rawTarget.isEmpty()) return;

        String npcName = rawTarget.replaceAll("<[^>]*>", "").trim();
        if (npcName.isEmpty()) return;

        log.debug("Pickpocket click detected: option='{}' target='{}'", option, npcName);

        // Snapshot the inventory on the next client tick (invokeLater ensures
        // the snapshot is taken BEFORE the server-side result is applied)
        pendingPickpocketNpc   = npcName;
        pickpocketWindowExpiry = System.currentTimeMillis() + PICKPOCKET_WINDOW_MS;

        clientThread.invokeLater(() ->
        {
            pickpocketInventorySnapshot = getCurrentInventory();
            log.debug("Pickpocket snapshot taken for '{}' ({} slots occupied)",
                    npcName, pickpocketInventorySnapshot.size());
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 5 – GROUND ITEM SPAWN  (fallback for unsupported new content)
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (!config.enableLootTracking()) return;

        TileItem tile = event.getItem();
        WorldPoint itemLoc = event.getTile().getWorldLocation();

        // ── Whisperer personal drops (debounced) ──────────────────────────────
        if (whispererGroundItemWindow)
        {
            Player local = client.getLocalPlayer();
            if (local == null) return;

            WorldPoint playerLoc = local.getWorldLocation();

            if (itemLoc != null && itemLoc.distanceTo(playerLoc) <= 12)
            {
                whispererGroundItems.add(new ItemStack(tile.getId(), tile.getQuantity()));
                log.debug("Whisperer ground item collected: id={} qty={} at {}",
                        tile.getId(), tile.getQuantity(), itemLoc);

                if (whispererFlushTask != null) whispererFlushTask.cancel(false);
                whispererFlushTask = executorService.schedule(
                        this::flushWhispererLoot, 450, TimeUnit.MILLISECONDS);
            }
            return;
        }

        // ── Normal ground item logic ──────────────────────────────────────────
        if (lastKilledBoss == null || lastKillTime == null) return;

        long elapsedMs = Instant.now().toEpochMilli() - lastKillTime.toEpochMilli();
        if (elapsedMs > GROUND_ITEM_WINDOW_MS) return;

        WorldPoint bossLoc = lastKilledBoss.getWorldLocation();
        if (itemLoc == null || bossLoc == null || itemLoc.distanceTo(bossLoc) > 5) return;

        groundItemBuffer.add(new ItemStack(tile.getId(), tile.getQuantity()));

        if (groundItemFlushScheduled.compareAndSet(false, true))
        {
            final NPC boss = lastKilledBoss;

            executorService.schedule(() ->
                            clientThread.invokeLater(() ->
                            {
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
                            }),
                    500, TimeUnit.MILLISECONDS);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CHAT MESSAGE
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!config.enableLootTracking()) return;

        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM) return;

        String msg   = event.getMessage();
        String lower = msg.toLowerCase();

        // ── Tempoross ─────────────────────────────────────────────────────────
        if (lower.contains("subdued the spirit") || lower.contains("you have helped to subdue"))
        {
            clientThread.invokeLater(() -> {
                inventorySnapshot       = getCurrentInventory();
                waitingForTemporossLoot = true;
                log.info("Tempoross: inventory snapshot taken ({} items)", inventorySnapshot.size());
            });
            lastChestSource = "Tempoross";
            return;
        }

        // ── Wintertodt ───────────────────────────────────────────────────────
        if (lower.contains("supply crate") && lower.contains("wintertodt"))
        {
            clientThread.invokeLater(() -> {
                inventorySnapshot        = getCurrentInventory();
                waitingForWintertodtLoot = true;
            });
            lastChestSource = "Wintertodt";
            return;
        }

        // ── The Whisperer ────────────────────────────────────────────────────
        if (lower.contains("whisperer") && lower.contains("kill count"))
        {
            String stripped = msg.replaceAll("<[^>]*>", "");
            Matcher kcM = Pattern
                    .compile("kill count is:?\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
                    .matcher(stripped);

            whispererParsedKC = kcM.find() ? Integer.parseInt(kcM.group(1)) : -1;

            lastChestSource           = "The Whisperer";
            whispererGroundItemWindow = true;
            whispererKillTime         = Instant.now();
            whispererGroundItems.clear();

            log.info("The Whisperer: KC detected (game KC={}) – ground-item collection window opened",
                    whispererParsedKC);
            return;
        }

        // ── Ring of Wealth auto-pickup ────────────────────────────────────────
        // Game message: "Your ring of wealth has automatically picked up the coins."
        // Coins bypass ItemSpawned entirely, so we diff inventory vs the snapshot
        // taken at the NPC kill and append the gain to the existing kill record.
        if (lower.contains("ring of wealth") && lower.contains("automatically picked up"))
        {
            if (rowInventorySnapshot != null && rowSnapshotBoss != null
                    && System.currentTimeMillis() < rowSnapshotExpiry)
            {
                final NPC           boss = rowSnapshotBoss;
                final List<ItemStack> snap = rowInventorySnapshot;
                rowInventorySnapshot = null;
                rowSnapshotBoss      = null;

                clientThread.invokeLater(() -> {
                    List<ItemStack> current = getCurrentInventory();
                    List<ItemStack> gained  = diffInventory(snap, current);
                    List<ItemStack> coins   = new ArrayList<>();
                    for (ItemStack is : gained)
                    {
                        if (is.getId() == ITEM_ID_COINS) coins.add(is);
                    }
                    if (!coins.isEmpty())
                    {
                        String bossName = lootManager.normalizeBossName(boss.getName());
                        lootManager.appendDropsToLastKill(bossName, coins);
                        log.info("Ring of Wealth: {} coins appended to '{}'",
                                coins.get(0).getQuantity(), bossName);
                    }
                });
            }
            return;
        }

        // ── Generic KC parser ────────────────────────────────────────────────
        if (lower.contains("kill count is"))
            lootManager.parseKillCountMessage(msg);

        // ── Chest detection fallback ─────────────────────────────────────────
        String detected = lootManager.detectChestSource(lower);
        if (detected != null && lastChestSource == null)
        {
            lastChestSource = detected;
            log.info("Chat: chest source set to '{}'", detected);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GAME TICK
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        // ── Expire stale boss ground-item attribution ──────────────────────────
        if (lastKillTime != null
                && ChronoUnit.SECONDS.between(lastKillTime, Instant.now())
                > BOSS_CLEAR_TIMEOUT_SECONDS)
        {
            lastKilledBoss = null;
            lastKillTime   = null;
        }

        // ── Expire the Whisperer ground-item window ───────────────────────────
        if (whispererGroundItemWindow && whispererKillTime != null)
        {
            long elapsed = Instant.now().toEpochMilli() - whispererKillTime.toEpochMilli();
            if (elapsed > WHISPERER_GROUND_ITEM_WINDOW_MS + 5_000)
            {
                log.warn("Whisperer ground-item window force-expired with {} items unclaimed",
                        whispererGroundItems.size());
                whispererGroundItemWindow = false;
                whispererGroundItems.clear();
                whispererKillTime = null;
            }
        }

        // ── Expire pickpocket attribution window ──────────────────────────────
        if (pendingPickpocketNpc != null
                && System.currentTimeMillis() > pickpocketWindowExpiry)
        {
            log.debug("Pickpocket window ticked out for '{}'", pendingPickpocketNpc);
            pendingPickpocketNpc        = null;
            pickpocketInventorySnapshot = null;
        }

        // ── Expire skilling sessions ──────────────────────────────────────────
        long nowMs = System.currentTimeMillis();
        skillingExpiry.entrySet().removeIf(entry -> {
            if (nowMs > entry.getValue())
            {
                skillingSnapshot.remove(entry.getKey());
                log.debug("Skilling session ticked out: {}", entry.getKey());
                return true;
            }
            return false;
        });

        // NOTE: The per-tick XP flush that was previously here has been removed.
        // XP is now batched by XpTrackerManager and sent once every 30 seconds.
        // Sending on every game tick (≈600 ms) was causing ~100 API calls/minute,
        // which silently prevented XP from appearing on the website.
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GAME STATE CHANGE
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState gs = event.getGameState();
        log.info("GameState: {}", gs);

        if (gs == GameState.LOGIN_SCREEN)
        {
            state.setLoggedIn(false);
            SwingUtilities.invokeLater(() -> mainPanel.showLoggedOutState());
            return;
        }

        if (gs == GameState.HOPPING) return;

        if (gs == GameState.LOGGED_IN)
        {
            state.setLoggedIn(true);
            // Refresh verification panel immediately so the button enables
            SwingUtilities.invokeLater(() ->
                    injector.getInstance(RuneAlyticsVerificationPanel.class).refreshLoginState());

            String username = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getName()
                    : null;

            if (username == null || username.isEmpty()) return;

            final String rsn = username.toLowerCase();

            if (!state.isVerified())
            {
                SwingUtilities.invokeLater(() -> mainPanel.showVerificationOnly());
                return;
            }

            executorService.submit(() ->
            {
                Map<String, Boolean> flags = apiClient.fetchFeatureFlags(rsn);
                SwingUtilities.invokeLater(() ->
                        mainPanel.showMainFeatures(
                                flags.getOrDefault(FEATURE_LOOT, false),
                                flags.getOrDefault(FEATURE_MATCHES, false)));
            });
        }
    }

    private void handlePostLogin()
    {
        String username = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName()
                : null;

        if (username == null || username.isEmpty()) return;

        if (!state.isVerified())
        {
            SwingUtilities.invokeLater(() -> mainPanel.showVerificationOnly());
            return;
        }

        executorService.submit(() -> {
            Map<String, Boolean> flags = apiClient.fetchFeatureFlags(username.toLowerCase());
            SwingUtilities.invokeLater(() -> mainPanel.applyFeatureFlags(flags));
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PLAYER SPAWNED
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned event)
    {
        if (event.getPlayer() != client.getLocalPlayer()) return;

        log.info("Local player spawned");

        // Always check per-account token and refresh the verification UI on login
        checkVerificationStatus();

        executorService.schedule(() -> {
            // Load local loot data regardless of verification — tracking always works locally.
            log.info("Post-login: loading local loot data (verified={})", state.isVerified());
            lootManager.loadFromStorage();
        }, 2, TimeUnit.SECONDS);

        executorService.schedule(
                () -> SwingUtilities.invokeLater(mainPanel::restoreLastTab),
                2_500, TimeUnit.MILLISECONDS);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  XP TRACKING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fires whenever the local player's XP changes in any skill.
     *
     * <p>Computes the XP delta since the last event and hands it off to
     * {@link XpTrackerManager#onXpGained}, which opens a 30-second accumulation
     * window on the first gain and sends a single batched POST at T+30s.
     * Nothing is sent per-tick here — all batching/scheduling lives in the manager.</p>
     *
     * <p>Also maintains the skilling-loot snapshot used by
     * {@link #onItemContainerChanged} to diff inventory changes during skilling sessions.</p>
     */
    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (!config.enableXpTracking()) return;

        Skill skill = event.getSkill();
        if (skill == Skill.OVERALL) return;

        int current = event.getXp();
        Integer prev = previousXp.get(skill);

        // First observation for this skill — record a baseline and wait for a real gain
        if (prev == null)
        {
            previousXp.put(skill, current);
            return;
        }

        int gained = current - prev;
        previousXp.put(skill, current);

        if (gained <= 0 || gained < config.minXpGain()) return;

        // ── Delegate XP batching to XpTrackerManager ──────────────────────────
        // The manager opens a 30-second window on the first call and accumulates
        // all subsequent gains within that window.  At T+30s it drains the buffer
        // and fires a single POST to /api/xp/batch.
        xpTrackerManager.onXpGained(skill, gained);

        // ── Skilling loot snapshot (unchanged) ────────────────────────────────
        if (SKILLING_TRACKED.contains(skill))
        {
            String key = skill.getName();
            clientThread.invokeLater(() -> {
                if (!skillingSnapshot.containsKey(key))
                    skillingSnapshot.put(key, getCurrentInventory());
                skillingExpiry.put(key, System.currentTimeMillis() + SKILLING_SESSION_MS);
            });
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCHEDULED SYNC
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
    //  WHISPERER FLUSH
    // ═════════════════════════════════════════════════════════════════════════

    private void flushWhispererLoot()
    {
        clientThread.invokeLater(() ->
        {
            whispererGroundItemWindow = false;

            final int kc = whispererParsedKC;
            whispererParsedKC = -1;

            if (whispererGroundItems.isEmpty())
            {
                log.warn("Whisperer flush: no items");
                return;
            }

            final List<ItemStack> loot = mergeItemStacks(whispererGroundItems);
            whispererGroundItems.clear();

            log.info("The Whisperer: merged into {} item types, KC={}", loot.size(), kc);
            lootManager.processPlayerLootWithGameKC("The Whisperer", loot, kc);
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Merges a list of {@link ItemStack}s with duplicate IDs into a single entry
     * per item ID by summing quantities.
     */
    private List<ItemStack> mergeItemStacks(List<ItemStack> raw)
    {
        Map<Integer, Integer> merged = new LinkedHashMap<>();
        for (ItemStack s : raw)
            merged.merge(s.getId(), s.getQuantity(), Integer::sum);

        List<ItemStack> result = new ArrayList<>(merged.size());
        for (Map.Entry<Integer, Integer> e : merged.entrySet())
            result.add(new ItemStack(e.getKey(), e.getValue()));
        return result;
    }

    private List<ItemStack> getCurrentInventory()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return Collections.emptyList();

        List<ItemStack> items = new ArrayList<>();
        for (Item item : inv.getItems())
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                items.add(new ItemStack(item.getId(), item.getQuantity()));
        return items;
    }

    /**
     * Returns only the items that were GAINED (new or increased in quantity)
     * between {@code before} and {@code after} inventory snapshots.
     *
     * @param before snapshot taken before the action
     * @param after  snapshot taken after the action
     * @return list of gained {@link ItemStack}s (may be empty)
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

    private void checkVerificationStatus()
    {
        if (client.getLocalPlayer() == null) return;
        String rawRsn = client.getLocalPlayer().getName();
        if (rawRsn == null) return;

        // RSN is stored/matched in lowercase to align with server normalisation
        final String rsn = rawRsn.trim().toLowerCase();

        RuneAlyticsVerificationPanel vp = injector.getInstance(RuneAlyticsVerificationPanel.class);
        String token = vp.loadAccountToken(rsn);

        if (token == null)
        {
            // No local token — definitely not verified
            applyVerificationState(false, rsn, null, vp);
            return;
        }

        // Local token exists — confirm it is still valid on the server before trusting it
        executorService.execute(() -> {
            boolean serverConfirmed = false;
            try
            {
                serverConfirmed = apiClient.verifyToken(token, rsn);
            }
            catch (Exception e)
            {
                // Network failure: keep existing state rather than clearing a valid token
                log.warn("Could not reach server to validate stored token for '{}': {}", rsn, e.getMessage());
                serverConfirmed = true; // optimistic — don't log out on connectivity issues
            }

            final boolean verified = serverConfirmed;
            if (!verified)
            {
                log.info("Stored token for '{}' rejected by server — clearing", rsn);
                vp.clearAccountToken(rsn);
            }

            applyVerificationState(verified, rsn, verified ? token : null, vp);
        });
    }

    private void applyVerificationState(boolean verified, String rsn, String token,
                                        RuneAlyticsVerificationPanel vp)
    {
        if (verified)
        {
            state.setVerified(true);
            state.setVerifiedUsername(rsn);
            state.setVerificationCode(token);
            log.info("Account '{}' is linked (server confirmed)", rsn);
        }
        else
        {
            state.setVerified(false);
            state.setVerifiedUsername(null);
            state.setVerificationCode(null);
            log.info("Account '{}' is not linked", rsn);
        }

        SwingUtilities.invokeLater(() -> {
            RuneAlyticsSettingsPanel sp = injector.getInstance(RuneAlyticsSettingsPanel.class);
            sp.updateVerificationStatus(verified, verified ? rsn : null);
            vp.refreshLoginState();
        });
    }

    private void logConfiguration()
    {
        log.info("=== RUNEALYTICS CONFIG ===");
        log.info("Loot tracking     : {}", config.enableLootTracking());
        log.info("Track all NPCs    : {}", config.trackAllNpcs());
        log.info("Track pickpockets : {}", config.enablePickpocketTracking());
        log.info("Min loot value    : {}", config.minimumLootValue());
        log.info("Sync to server    : {}", config.syncLootToServer());
        log.info("Auto-verify       : {}", config.enableAutoVerification());
        log.info("API URL           : {}", config.apiUrl());
        log.info("=========================");
    }

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