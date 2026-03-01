package com.runealytics;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central coordinator for all loot tracking.
 *
 * <h2>Loot paths supported</h2>
 * <ol>
 *   <li><b>NpcLootReceived</b> – ground-drop NPCs (monsters, bosses, wilderness)</li>
 *   <li><b>PlayerLootReceived</b> – chest/raid sources after widget detection</li>
 *   <li><b>WidgetLoaded</b> → container read – CoX, ToB, ToA, Barrows, Gauntlet,
 *       Nightmare, Zalcano, Colosseum, Yama, Royal Titans, Wintertodt, Tempoross,
 *       Clue caskets</li>
 *   <li><b>ItemContainerChanged</b> (inventory diff) – Tempoross / Wintertodt fallback</li>
 *   <li><b>ItemSpawned</b> (ground item window) – supplemental fallback for new content</li>
 *   <li><b>Chat messages</b> – completion / KC detection</li>
 * </ol>
 *
 * <h2>Deduplication</h2>
 * <ul>
 *   <li>NPC loot: no dedup (RuneLite fires once per kill; rapid kills of the same
 *       NPC type must all be counted separately).</li>
 *   <li>Player / chest loot: 2-second window per source name to prevent double-counting
 *       when both the widget-read path <em>and</em> PlayerLootReceived fire.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class LootTrackerManager
{
    // ── KC chat message pattern ───────────────────────────────────────────────
    private static final Pattern KC_PATTERN =
            Pattern.compile("Your (.+?) kill count is: (\\d+)", Pattern.CASE_INSENSITIVE);

    // ── Deduplication window (player / chest loot only) ───────────────────────
    private static final long PLAYER_LOOT_DEDUP_MS = 2_000;

    // ── Ground-item attribution window after a kill ───────────────────────────
    private static final long GROUND_ITEM_WINDOW_MS = 3_000;

    // ═════════════════════════════════════════════════════════════════════════
    //  BOSS NPC-ID WHITELIST
    //  Add new bosses here when they are released.
    // ═════════════════════════════════════════════════════════════════════════
    private static final Set<Integer> TRACKED_BOSS_IDS = ImmutableSet.of(
            // ── Varlamore / 2024-2026 ────────────────────────────────────────
            13751, 13752, 13753, 13754, 13755, 13756, 13757, 13758, // Royal Titans
            14000, 14001, 14002, 14003, 14013, 14014,               // Hueycoatl
            13010, 13011, 13012, 13013, 13014, 13015,               // Moons of Peril
            12821, 12822, 12823, 13668, 13669, 13670,               // Yama / Araxxor
            12922, 12923, 12924, 12925, 13579, 13580,               // Scurrius / Amoxliatl
            13147, 13148, 13149,                                    // Tormented Demons

            // ── Raids ─────────────────────────────────────────────────────────
            7554, 7555, 7556,                                       // CoX (Great Olm)
            10674, 10698, 10702, 10704, 10707, 10847,               // ToB
            11750, 11751, 11752, 11753, 11754, 11770, 11771,        // ToA

            // ── God Wars Dungeon ─────────────────────────────────────────────
            2215, 2216, 2217, 2218, 2205, 2206, 2207,               // Zilyana / Graardor
            6260, 6261, 6262, 6263, 6203, 6204, 6205, 6206,         // Kree / K'ril
            11278, 11279, 11280, 11281, 11282,                      // Nex

            // ── Wilderness ───────────────────────────────────────────────────
            11872, 11867, 11868, 11962, 11963, 11946, 11947,        // Callisto / Artio / Vet'ion
            11993, 11994, 11973, 11974,                             // Spindel / Venenatis
            2054, 6611, 6612, 6618, 6619, 6615, 319,                // Corp / Fanatic / Scorpia

            // ── Slayer & World Bosses ────────────────────────────────────────
            2042, 2043, 2044, 8059, 8060, 50,                       // Zulrah / Vorkath / KBD
            5862, 5886, 1999, 7855, 494, 496, 7605, 8609,           // Cerb / Sire / Hydra
            7544, 7796, 9415, 9416, 9425, 9426,                     // Grotesque / Nightmare
            6230, 6231, 6232, 6233, 6234,                           // Skotizo
            2265, 2266, 2267, 963, 965, 4303,                       // Dagannoth Kings / KQ

            // ── Desert Treasure 2 ────────────────────────────────────────────
            12166, 12167, 12193, 12214, 12205, 12223, 12225, 12227, // Duke/Leviathan/Vardorvis/Whisperer

            // ── Minigames / Skilling ─────────────────────────────────────────
            2025, 2026, 2027, 2028, 2029, 2030,                     // Barrows brothers
            10565, 7559, 9050, 10814                                 // Tempoross / Wintertodt / Zalcano
    );

    // ═════════════════════════════════════════════════════════════════════════
    //  BOSS NAME → PRIMARY NPC-ID MAP
    //  Used when only a display name is available (e.g. PlayerLootReceived).
    // ═════════════════════════════════════════════════════════════════════════
    private static final Map<String, Integer> BOSS_NAME_TO_ID =
            ImmutableMap.<String, Integer>builder()
                    // GWD
                    .put("Commander Zilyana",   2215)
                    .put("General Graardor",    2260)
                    .put("Kree'arra",           6260)
                    .put("K'ril Tsutsaroth",    6203)
                    .put("Nex",                 11278)
                    // Varlamore
                    .put("Royal Titans",        13751)
                    .put("The Hueycoatl",       14000)
                    .put("Moons of Peril",      13010)
                    .put("Yama",                12821)
                    .put("Araxxor",             13668)
                    .put("Scurrius",            12922)
                    .put("Amoxliatl",           13579)
                    .put("Tormented Demon",     13147)
                    // Desert Treasure 2
                    .put("Duke Sucellus",       12166)
                    .put("The Leviathan",       12193)
                    .put("Vardorvis",           12205)
                    .put("The Whisperer",       12225)
                    // Wilderness
                    .put("Artio",               11962)
                    .put("Callisto",            11963)
                    .put("Calvar'ion",          11946)
                    .put("Vet'ion",             11947)
                    .put("Spindel",             11993)
                    .put("Venenatis",           11973)
                    .put("Corporeal Beast",     319)
                    // Raids
                    .put("Chambers of Xeric",   7554)
                    .put("Theatre of Blood",    10674)
                    .put("Tombs of Amascut",    11750)
                    // Classic bosses
                    .put("Kalphite Queen",      963)
                    .put("Dagannoth Prime",     2265)
                    .put("Dagannoth Rex",       2266)
                    .put("Dagannoth Supreme",   2267)
                    .put("Vorkath",             8059)
                    .put("Zulrah",              2042)
                    .put("Alchemical Hydra",    8609)
                    .put("Cerberus",            5862)
                    .put("Abyssal Sire",        5886)
                    .put("Kraken",              494)
                    .put("Skotizo",             6230)
                    .put("The Nightmare",       9415)
                    .put("Phosani's Nightmare", 9416)
                    // Activities
                    .put("Barrows",             2025)
                    .put("Corrupted Gauntlet",  9036)
                    .put("The Gauntlet",        9035)
                    .put("Zalcano",             9050)
                    .put("Wintertodt",          7559)
                    .put("Tempoross",           10565)
                    .put("Fortis Colosseum",    0)
                    // Clue scrolls (no NPC ID needed)
                    .put("Beginner Clue",       0)
                    .put("Easy Clue",           0)
                    .put("Medium Clue",         0)
                    .put("Hard Clue",           0)
                    .put("Elite Clue",          0)
                    .put("Master Clue",         0)
                    .build();

    // ═════════════════════════════════════════════════════════════════════════
    //  WIDGET GROUP-IDs FOR CHEST / REWARD INTERFACES
    //  When onWidgetLoaded fires with one of these IDs we set lastChestSource
    //  so that the subsequent PlayerLootReceived or container-read is attributed.
    // ═════════════════════════════════════════════════════════════════════════

    /** Barrows reward chest                                  */
    static final int WIDGET_BARROWS          = 155;
    /** Chambers of Xeric reward chest                        */
    static final int WIDGET_COX              = 539;
    /** Theatre of Blood reward chest                         */
    static final int WIDGET_TOB              = 23;
    /** Tombs of Amascut reward chest                         */
    static final int WIDGET_TOA              = 773;
    /** The Corrupted Gauntlet reward chest                   */
    static final int WIDGET_CORRUPTED_GAUNTLET = 700;
    /** The Gauntlet (normal) reward chest                    */
    static final int WIDGET_GAUNTLET         = 595;
    /** Nightmare / Phosani reward chest                      */
    static final int WIDGET_NIGHTMARE        = 600;
    /** Zalcano reward chest                                  */
    static final int WIDGET_ZALCANO          = 620;
    /** Tempoross reward pool                                 */
    static final int WIDGET_TEMPOROSS        = 229;
    /** Wintertodt reward crate                               */
    static final int WIDGET_WINTERTODT       = 634;
    /** Clue scroll reward casket                             */
    static final int WIDGET_CLUE             = 73;
    /** Royal Titans (Varlamore lair) reward chest            */
    static final int WIDGET_ROYAL_TITANS     = 174;
    /** Yama reward interface                                 */
    static final int WIDGET_YAMA             = 810;
    /** Fortis Colosseum reward chest                         */
    static final int WIDGET_COLOSSEUM        = 867;
    /** Hespori flower chest (Farming Guild boss)             */
    static final int WIDGET_HESPORI          = 897;
    /** Giant's Foundry / Mahogany Homes minigame rewards     */
    static final int WIDGET_MINIGAME_REWARD  = 300;

    // ── InventoryID constants for container reads ────────────────────────────
    // These are the integer container IDs used in client.getItemContainer(id)
    private static final int CONTAINER_BARROWS   = 141;
    private static final int CONTAINER_COX       = 582;
    private static final int CONTAINER_TOB       = 23;
    private static final int CONTAINER_TOA       = 141; // same slot reused
    private static final int CONTAINER_GAUNTLET  = 179;
    private static final int CONTAINER_NIGHTMARE = 646;
    private static final int CONTAINER_ZALCANO   = 631;

    // ═════════════════════════════════════════════════════════════════════════
    //  INJECTED DEPENDENCIES
    // ═════════════════════════════════════════════════════════════════════════

    private final Client                  client;
    private final ClientThread            clientThread;
    private final ItemManager             itemManager;
    private final RunealyticsConfig       config;
    private final RuneAlyticsState        state;
    private final LootStorageManager      storageManager;
    private final LootTrackerApiClient    apiClient;
    private final ScheduledExecutorService executorService;

    // ═════════════════════════════════════════════════════════════════════════
    //  MUTABLE STATE
    // ═════════════════════════════════════════════════════════════════════════

    /** Reference to the UI panel; may be null before first login. */
    private LootTrackerPanel panel;

    /** True after local data has been loaded this session (prevents re-load on hop). */
    private boolean hasLoadedData = false;

    /**
     * Gate: server sync is only permitted during a manual sync operation.
     * Prevents background auto-sync on login/startup.
     */
    private boolean allowSync = false;

    /** In-memory boss stats, keyed by normalised NPC name. */
    private final Map<String, BossKillStats> bossKillStats = new ConcurrentHashMap<>();

    /** Listeners notified on every kill or data refresh. */
    private final List<LootTrackerUpdateListener> listeners = new ArrayList<>();

    /**
     * Per-boss hidden item sets.
     * Key = normalised NPC name; value = set of hidden itemIds.
     */
    private final Map<String, Set<Integer>> hiddenDrops = new HashMap<>();

    /**
     * Dedup map for player / chest loot sources only.
     * Key = normalised source name; value = last time (ms) we processed loot from it.
     * NOT used for NpcLootReceived – see class javadoc.
     */
    private final Map<String, Long> lastPlayerLootTime = new ConcurrentHashMap<>();

    // ═════════════════════════════════════════════════════════════════════════
    //  CONSTRUCTOR
    // ═════════════════════════════════════════════════════════════════════════

    @Inject
    public LootTrackerManager(
            Client                   client,
            ClientThread             clientThread,
            ItemManager              itemManager,
            RunealyticsConfig        config,
            RuneAlyticsState         state,
            LootStorageManager       storageManager,
            LootTrackerApiClient     apiClient,
            ScheduledExecutorService executorService
    )
    {
        this.client          = client;
        this.clientThread    = clientThread;
        this.itemManager     = itemManager;
        this.config          = config;
        this.state           = state;
        this.storageManager  = storageManager;
        this.apiClient       = apiClient;
        this.executorService = executorService;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called once during plugin startUp().
     * Resets the "data loaded" flag so the next login fetches fresh data.
     */
    public void initialize()
    {
        log.info("LootTrackerManager: initialising");
        hasLoadedData = false;
    }

    /** Called during plugin shutDown(). Persists in-memory data to disk. */
    public void shutdown()
    {
        log.info("LootTrackerManager: saving on shutdown");
        storageManager.saveData();
    }

    /** Attaches the UI panel that this manager drives. */
    public void setPanel(LootTrackerPanel panel)
    {
        this.panel = panel;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 1 – NPC GROUND DROPS  (NpcLootReceived)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Processes loot from a standard NPC ground-drop kill.
     *
     * <p>Called from RuneAlyticsPlugin.onNpcLootReceived(). No deduplication is
     * applied because RuneLite guarantees this event fires exactly once per kill,
     * and rapid kills of the same NPC type (e.g. two Ice Giants) must both be
     * counted independently.</p>
     *
     * @param npc   the NPC that was killed
     * @param items the dropped items in our internal ItemStack format
     */
    public void processNpcLoot(NPC npc, List<ItemStack> items)
    {
        if (!config.enableLootTracking() || npc == null || npc.getName() == null)
            return;

        // Always log at INFO so new NPC IDs can be found in logs
        log.info("NPC loot: '{}' id={} cb={} items={}",
                npc.getName(), npc.getId(), npc.getCombatLevel(), items.size());

        if (!state.isVerified())
        {
            log.debug("processNpcLoot: not verified – skipping");
            return;
        }

        String name = normalizeBossName(npc.getName());
        boolean isBoss = isBoss(npc.getId(), name);

        if (!isBoss && !config.trackAllNpcs())
        {
            log.warn("Filtered NPC (not a tracked boss): '{}' id={} "
                            + "→ enable 'Track All NPCs' or add id to TRACKED_BOSS_IDS",
                    name, npc.getId());
            return;
        }

        List<LootStorageData.DropRecord> drops =
                convertToDropRecords(items);

        if (drops.isEmpty()) return;

        recordKill(name, npc.getId(), npc.getCombatLevel(), client.getWorld(), drops);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 2 – PLAYER / CHEST LOOT  (PlayerLootReceived + Widget reads)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Processes loot from a non-NPC source (chests, raids, activities, clues).
     *
     * <p>A 2-second deduplication window is applied per source name to prevent
     * double-counting when both the widget container-read path
     * <em>and</em> the PlayerLootReceived event fire for the same chest.</p>
     *
     * @param source display name of the loot source (e.g. "Barrows", "Chambers of Xeric")
     * @param items  item list in our internal ItemStack format
     */
    public void processPlayerLoot(String source, List<ItemStack> items)
    {
        if (items == null || items.isEmpty() || !state.isVerified())
        {
            log.debug("processPlayerLoot: empty/unverified – source='{}'", source);
            return;
        }

        String name = normalizeBossName(source);
        long   now  = System.currentTimeMillis();
        Long   last = lastPlayerLootTime.get(name);

        if (last != null && (now - last) < PLAYER_LOOT_DEDUP_MS)
        {
            log.debug("processPlayerLoot: dedup suppressed '{}' ({}ms ago)",
                    name, now - last);
            return;
        }

        lastPlayerLootTime.put(name, now);

        int npcId = BOSS_NAME_TO_ID.getOrDefault(name, 0);
        log.info("Player loot: '{}' (id={}) items={}", name, npcId, items.size());

        List<LootStorageData.DropRecord> drops = convertToDropRecords(items);
        if (drops.isEmpty()) return;

        recordKill(name, npcId, 0, client.getWorld(), drops);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 3 – WIDGET CONTAINER READS
    //  These are called from RuneAlyticsPlugin.onWidgetLoaded().
    //  Each method reads items directly from the game's reward container widget.
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Reads loot from a numeric InventoryID container (Barrows, CoX, ToB, ToA,
     * Gauntlet, Nightmare, Zalcano, Wintertodt).
     *
     * <p>Schedule a short delay before reading so the container has time to
     * populate after the widget loads.</p>
     *
     * @param sourceName  display name for the loot source
     * @param containerId integer container ID (from the InventoryID enum's getId() value)
     */
    public void readRewardContainer(String sourceName, int containerId)
    {
        // Delay 300ms to allow the container to populate
        executorService.schedule(() -> clientThread.invokeLater(() ->
        {
            ItemContainer container = client.getItemContainer(containerId);
            if (container == null)
            {
                log.warn("readRewardContainer: container {} not found for '{}'",
                        containerId, sourceName);
                return;
            }

            List<ItemStack> items = new ArrayList<>();
            for (Item item : container.getItems())
            {
                if (item.getId() > 0 && item.getQuantity() > 0)
                    items.add(new ItemStack(item.getId(), item.getQuantity()));
            }

            if (items.isEmpty())
            {
                log.debug("readRewardContainer: no items in container {} for '{}'",
                        containerId, sourceName);
                return;
            }

            log.info("Container read '{}': {} items from container {}",
                    sourceName, items.size(), containerId);
            processPlayerLoot(sourceName, items);

        }), 300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Reads loot from a widget tree by walking all children of the given
     * widget group ID (used for Royal Titans, Yama, Colosseum, Hespori, and
     * any other reward interface that doesn't map to a standard InventoryID).
     *
     * @param sourceName  display name for the loot source
     * @param groupId     widget group ID (from onWidgetLoaded event)
     * @param maxChildren how many child indices to search (usually 100 is safe)
     */
    public void readWidgetLoot(String sourceName, int groupId, int maxChildren)
    {
        executorService.schedule(() -> clientThread.invokeLater(() ->
        {
            List<ItemStack> items = new ArrayList<>();

            for (int i = 0; i < maxChildren; i++)
            {
                net.runelite.api.widgets.Widget w = client.getWidget(groupId, i);
                if (w == null) continue;

                collectWidgetItems(w, items);

                // Recurse one level into children
                net.runelite.api.widgets.Widget[] children = w.getChildren();
                if (children != null)
                {
                    for (net.runelite.api.widgets.Widget child : children)
                    {
                        if (child != null) collectWidgetItems(child, items);
                    }
                }
            }

            if (items.isEmpty())
            {
                log.debug("readWidgetLoot: no items found for '{}' (group {})",
                        sourceName, groupId);
                return;
            }

            log.info("Widget loot '{}' (group {}): {} items", sourceName, groupId, items.size());
            processPlayerLoot(sourceName, items);

        }), 300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Reads loot from the Clue scroll reward casket widget (group 73, child 10).
     * The source label must be set by the caller (from detectChestSource).
     *
     * @param sourceName clue tier label e.g. "Elite Clue"
     */
    public void readClueReward(String sourceName)
    {
        executorService.schedule(() -> clientThread.invokeLater(() ->
        {
            net.runelite.api.widgets.Widget parent = client.getWidget(WIDGET_CLUE, 10);
            if (parent == null)
            {
                log.warn("Clue reward widget (73,10) not found for '{}'", sourceName);
                return;
            }

            List<ItemStack> items = new ArrayList<>();
            collectWidgetItems(parent, items);

            net.runelite.api.widgets.Widget[] children = parent.getChildren();
            if (children != null)
            {
                for (net.runelite.api.widgets.Widget c : children)
                    if (c != null) collectWidgetItems(c, items);
            }

            if (items.isEmpty())
            {
                log.debug("readClueReward: no items for '{}'", sourceName);
                return;
            }

            log.info("Clue reward '{}': {} items", sourceName, items.size());
            processPlayerLoot(sourceName, items);

        }), 300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Extracts item ID and quantity from a widget if it holds an item.
     *
     * @param w     widget to inspect
     * @param items list to add the item to if valid
     */
    private void collectWidgetItems(net.runelite.api.widgets.Widget w, List<ItemStack> items)
    {
        if (w.getItemId() > 0 && w.getItemQuantity() > 0)
            items.add(new ItemStack(w.getItemId(), w.getItemQuantity()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 4 – INVENTORY DIFF  (Tempoross / Wintertodt fallback)
    //  Called from RuneAlyticsPlugin when an inventory snapshot is available.
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Processes a list of new items derived from an inventory diff.
     * Routes through {@link #processPlayerLoot} with the given source name.
     *
     * @param sourceName  activity name e.g. "Tempoross", "Wintertodt"
     * @param newItems    items that appeared in the inventory since the snapshot
     */
    public void processInventoryDiff(String sourceName, List<ItemStack> newItems)
    {
        if (newItems == null || newItems.isEmpty()) return;
        log.info("Inventory diff '{}': {} new items", sourceName, newItems.size());
        processPlayerLoot(sourceName, newItems);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 5 – GROUND ITEM SPAWN  (ItemSpawned fallback)
    //  Called from RuneAlyticsPlugin when a ground item spawns near a recent kill.
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Processes a batch of ground items attributed to a recently killed NPC.
     * This is only a fallback for new content not yet supported by RuneLite's
     * NpcLootReceived event.
     *
     * @param npc   the NPC whose kill caused the drops
     * @param items ground items within the attribution window
     */
    public void processGroundItemBatch(NPC npc, List<ItemStack> items)
    {
        if (npc == null || items.isEmpty()) return;
        log.info("Ground items from '{}' (id={}): {} items",
                npc.getName(), npc.getId(), items.size());
        processNpcLoot(npc, items);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CORE KILL RECORDING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The single write path for all loot sources.
     * <ol>
     *   <li>Updates in-memory {@link BossKillStats}</li>
     *   <li>Persists to disk via {@link LootStorageManager#addKill}</li>
     *   <li>Optionally queues an async server sync</li>
     *   <li>Refreshes the UI panel</li>
     * </ol>
     *
     * @param npcName     normalised NPC / source name
     * @param npcId       NPC ID (0 for chest sources)
     * @param combatLevel NPC combat level (0 for chest sources)
     * @param world       current world number
     * @param drops       processed, value-filtered DropRecord list
     */
    private void recordKill(
            String npcName, int npcId, int combatLevel, int world,
            List<LootStorageData.DropRecord> drops)
    {
        BossKillStats stats = bossKillStats.computeIfAbsent(
                npcName, k -> new BossKillStats(npcName, npcId));

        int killNumber = stats.getKillCount() + 1;

        // Build in-memory kill record
        NpcKillRecord killRecord = new NpcKillRecord(npcName, npcId, combatLevel, world);
        killRecord.setKillNumber(killNumber);
        for (LootStorageData.DropRecord d : drops)
        {
            killRecord.addDrop(new LootDrop(
                    d.getItemId(), d.getItemName(), d.getQuantity(),
                    d.getGePrice(), d.getHighAlch()));
        }

        stats.addKill(killRecord);

        // Persist to disk
        storageManager.addKill(npcName, npcId, combatLevel, killNumber,
                world, state.getPrestige(), drops);

        // Async server sync (if enabled and not already syncing)
        if (config.syncLootToServer())
        {
            asyncServerSync(npcName, npcId, combatLevel, killNumber, world, drops);
        }

        // Update UI
        if (panel != null)
        {
            clientThread.invokeLater(() -> {
                panel.highlightBoss(npcName);
                panel.refreshDisplay();
            });
        }

        notifyListeners(stats, killRecord);

        log.info("Kill recorded: '{}' #{} – {} drops, {} gp",
                npcName, killNumber, drops.size(),
                drops.stream().mapToLong(LootStorageData.DropRecord::getTotalValue).sum());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SERVER SYNC
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Submits a single kill sync to the server in the background.
     * Silently skips if a sync is already in progress or the state is not ready.
     */
    private void asyncServerSync(
            String npcName, int npcId, int combatLevel,
            int killNumber, int world,
            List<LootStorageData.DropRecord> drops)
    {
        if (!state.canSync()) return;

        executorService.submit(() -> {
            try
            {
                apiClient.syncSingleKill(
                        state.getVerifiedUsername(), npcName, npcId, combatLevel,
                        killNumber, world, System.currentTimeMillis(),
                        state.getPrestige(), drops);
                storageManager.markKillsSynced(
                        npcName, System.currentTimeMillis(), System.currentTimeMillis());
            }
            catch (Exception e)
            {
                log.error("Async sync failed for '{}' #{}", npcName, killNumber, e);
            }
        });
    }

    /**
     * Downloads kill history from the server and merges it into local storage.
     * Only callable when {@link #allowSync} is true (set by {@link #performManualSync}).
     */
    public void downloadKillHistoryFromServer()
    {
        if (!allowSync)
        {
            log.warn("downloadKillHistoryFromServer: blocked – use manual sync");
            return;
        }

        String username = state.getVerifiedUsername();
        if (username == null || !state.canSync()) return;

        try
        {
            state.startSync();
            Map<String, LootStorageData.BossKillData> serverData =
                    apiClient.fetchKillHistoryFromServer(username);

            if (serverData != null && !serverData.isEmpty())
            {
                storageManager.mergeServerData(serverData);
                refreshLootDisplay();
                log.info("Merged {} bosses from server", serverData.size());
            }
        }
        catch (Exception e)
        {
            log.error("Failed to download kill history", e);
        }
        finally
        {
            state.endSync();
        }
    }

    /**
     * Uploads all locally unsynced kills in batches of 50.
     * Marks each batch as synced on success.
     */
    public void uploadUnsyncedKills()
    {
        String username = state.getVerifiedUsername();
        if (username == null || !state.canSync()) return;

        try
        {
            state.startSync();
            Map<String, List<LootStorageData.KillRecord>> unsynced =
                    storageManager.getAllUnsyncedKills();

            if (unsynced.isEmpty()) {  return; }

            int total = unsynced.values().stream().mapToInt(List::size).sum();
            log.info("Uploading {} unsynced kills across {} bosses", total, unsynced.size());

            final int BATCH = 50;
            List<LootStorageData.KillRecord> all = new ArrayList<>();
            Map<String, String> tsToName = new HashMap<>();

            for (Map.Entry<String, List<LootStorageData.KillRecord>> e : unsynced.entrySet())
            {
                for (LootStorageData.KillRecord k : e.getValue())
                {
                    all.add(k);
                    tsToName.put(String.valueOf(k.getTimestamp()), e.getKey());
                }
            }

            for (int i = 0; i < all.size(); i += BATCH)
            {
                List<LootStorageData.KillRecord> batch =
                        all.subList(i, Math.min(i + BATCH, all.size()));

                Map<String, List<LootStorageData.KillRecord>> byBoss = new HashMap<>();
                for (LootStorageData.KillRecord k : batch)
                {
                    String boss = tsToName.get(String.valueOf(k.getTimestamp()));
                    byBoss.computeIfAbsent(boss, x -> new ArrayList<>()).add(k);
                }

                boolean ok = apiClient.bulkSyncKills(username, byBoss);
                if (ok)
                {
                    for (Map.Entry<String, List<LootStorageData.KillRecord>> e : byBoss.entrySet())
                    {
                        long min = e.getValue().stream().mapToLong(LootStorageData.KillRecord::getTimestamp).min().orElse(0);
                        long max = e.getValue().stream().mapToLong(LootStorageData.KillRecord::getTimestamp).max().orElse(Long.MAX_VALUE);
                        storageManager.markKillsSynced(e.getKey(), min, max);
                    }
                }
                else
                {
                    log.error("Batch upload failed – aborting");
                    break;
                }

                if (i + BATCH < all.size()) Thread.sleep(500);
            }
        }
        catch (Exception e)
        {
            log.error("Upload unsynced kills failed", e);
        }
        finally
        {
            state.endSync();
        }
    }

    /**
     * Performs a full bidirectional sync: download from server, upload local,
     * refresh UI. This is the ONLY way server sync is triggered.
     *
     * @param username verified OSRS username
     */
    public void performManualSync(String username)
    {
        if (username == null || username.isEmpty()) return;

        executorService.submit(() -> {
            try
            {
                allowSync = true;
                downloadKillHistoryFromServer();
                uploadUnsyncedKills();
                clientThread.invokeLater(this::refreshLootDisplay);

                clientThread.invokeLater(() -> {
                    if (panel != null) panel.showSyncCompleted();
                });
            }
            catch (Exception e)
            {
                log.error("Manual sync failed", e);
                clientThread.invokeLater(() -> {
                    if (panel != null) panel.showSyncFailed(e.getMessage());
                });
            }
            finally
            {
                allowSync = false;
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Loads loot data from disk and rebuilds in-memory stats.
     * Called after login when the player is verified.
     */
    public void loadFromStorage()
    {
        if (hasLoadedData) { log.debug("Data already loaded this session"); return; }
        log.info("Loading local loot data");
        refreshLootDisplay();
        hasLoadedData = true;
    }

    /**
     * Loads loot data for a specific username (used on account switch).
     *
     * @param username OSRS username to load for
     */
    public void loadLocalDataForUser(String username)
    {
        log.info("Loading local data for '{}'", username);
        storageManager.loadLootData(username);
        clientThread.invokeLater(this::refreshLootDisplay);
    }

    /**
     * Rebuilds {@link #bossKillStats} from the current on-disk data and
     * triggers a UI refresh. Call after any operation that modifies stored data.
     */
    private void refreshLootDisplay()
    {
        LootStorageData data = storageManager.getCurrentData();

        if (data == null || data.getBossKills().isEmpty())
        {
            bossKillStats.clear();
            if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
            return;
        }

        bossKillStats.clear();

        for (Map.Entry<String, LootStorageData.BossKillData> entry : data.getBossKills().entrySet())
        {
            LootStorageData.BossKillData bd = entry.getValue();
            BossKillStats stats = new BossKillStats(bd.getNpcName(), bd.getNpcId());
            stats.setPrestige(bd.getPrestige());

            if (bd.getKills() != null)
            {
                for (LootStorageData.KillRecord kr : bd.getKills())
                {
                    NpcKillRecord nkr = new NpcKillRecord(
                            bd.getNpcName(), bd.getNpcId(),
                            kr.getCombatLevel(), kr.getWorld());
                    nkr.setTimestamp(kr.getTimestamp());
                    nkr.setKillNumber(kr.getKillNumber());

                    for (LootStorageData.DropRecord dr : kr.getDrops())
                    {
                        nkr.addDrop(new LootDrop(
                                dr.getItemId(), dr.getItemName(),
                                dr.getQuantity(), dr.getGePrice(), dr.getHighAlch()));
                    }

                    stats.addKill(nkr);
                }

                // Trust disk KC if in-memory replay diverges
                if (stats.getKillCount() != bd.getKillCount())
                {
                    log.warn("KC mismatch '{}': memory={} disk={}",
                            entry.getKey(), stats.getKillCount(), bd.getKillCount());
                    stats.setKillCount(bd.getKillCount());
                }
            }
            else
            {
                stats.setKillCount(bd.getKillCount());
            }

            bossKillStats.put(stats.getNpcName(), stats);
        }

        log.debug("refreshLootDisplay: {} bosses loaded", bossKillStats.size());

        if (panel != null)
            SwingUtilities.invokeLater(() -> panel.refreshDisplay());
    }

    /** Called when the player switches OSRS accounts. */
    public void onAccountChanged(String newUsername)
    {
        bossKillStats.clear();
        hasLoadedData = false;
        loadFromStorage();
        if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
    }

    /** Called on login once the player is verified. */
    public void onPlayerLoggedInAndVerified()
    {
        loadFromStorage();
    }

    // ── Public read accessors ─────────────────────────────────────────────────

    /** Returns an unmodifiable view of all in-memory boss stats. */
    public Map<String, BossKillStats> getBossKillStats()
    {
        return Collections.unmodifiableMap(bossKillStats);
    }

    /** Returns all boss stats as a flat list (order not guaranteed). */
    public List<BossKillStats> getAllBossStats()
    {
        return new ArrayList<>(bossKillStats.values());
    }

    // ── Data mutation ─────────────────────────────────────────────────────────

    /** Wipes all in-memory and on-disk loot data for the current user. */
    public void clearAllData()
    {
        bossKillStats.clear();
        hiddenDrops.clear();
        lastPlayerLootTime.clear();
        storageManager.clearData();
        if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
        notifyDataRefresh();
    }

    /**
     * Removes all tracked data for a single boss.
     *
     * @param npcName normalised boss name
     */
    public void clearBossData(String npcName)
    {
        bossKillStats.remove(npcName);
        hiddenDrops.remove(npcName);
        lastPlayerLootTime.remove(npcName);
        storageManager.saveData();
        if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
    }

    /**
     * Resets kill stats for a boss and increments its prestige counter.
     *
     * @param npcName normalised boss name
     */
    public void prestigeBoss(String npcName)
    {
        BossKillStats stats = bossKillStats.get(npcName);
        if (stats != null)
        {
            stats.prestige();
            storageManager.saveData();
            if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
        }
    }

    // ── Hidden drops ──────────────────────────────────────────────────────────

    /** Returns true if itemId is hidden for the given NPC. */
    public boolean isDropHidden(String npcName, int itemId)
    {
        Set<Integer> h = hiddenDrops.get(npcName);
        return h != null && h.contains(itemId);
    }

    /** Hides a specific item for an NPC (excludes it from the UI grid). */
    public void hideDropForNpc(String npcName, int itemId)
    {
        hiddenDrops.computeIfAbsent(npcName, k -> new HashSet<>()).add(itemId);
        storageManager.saveData();
    }

    /** Removes a previously hidden item for an NPC. */
    public void unhideDropForNpc(String npcName, int itemId)
    {
        Set<Integer> h = hiddenDrops.get(npcName);
        if (h != null)
        {
            h.remove(itemId);
            if (h.isEmpty()) hiddenDrops.remove(npcName);
            storageManager.saveData();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY – BOSS DETECTION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if this NPC should be treated as a tracked boss.
     * Checks the ID whitelist first, then falls back to name-based matching.
     *
     * @param npcId  NPC ID from the game
     * @param name   normalised NPC name
     * @return true if the NPC should be tracked
     */
    public boolean isBoss(int npcId, String name)
    {
        return TRACKED_BOSS_IDS.contains(npcId) || matchesBossName(name);
    }

    /**
     * Name-based boss matching for NPCs not yet in the ID whitelist.
     * Add new keywords here when new content is released.
     *
     * @param name normalised NPC name (may be lower-case or mixed)
     * @return true if the name suggests a boss
     */
    private boolean matchesBossName(String name)
    {
        if (name == null) return false;
        String l = name.toLowerCase();
        return l.contains("duke")        || l.contains("leviathan")
                || l.contains("vardorvis")   || l.contains("whisperer")
                || l.contains("zulrah")      || l.contains("vorkath")
                || l.contains("cerberus")    || l.contains("nightmare")
                || l.contains("gauntlet")    || l.contains("barrows")
                || l.contains("yama")        || l.contains("tempoross")
                || l.contains("wintertodt")  || l.contains("zalcano")
                || l.contains("eldric")      || l.contains("branda")
                || l.contains("hueycoatl")   || l.contains("araxxor")
                || l.contains("scurrius")    || l.contains("amoxliatl")
                || l.contains("colosseum")   || l.contains("skotizo")
                || l.contains("hespori")     || l.contains("abyssal")
                || l.contains("thermonuclear")
                || l.contains("grotesque")   || l.contains("kalphite")
                || l.contains("dagannoth")   || l.contains("corporeal")
                || l.contains("tormented demon");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY – NAME NORMALISATION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Maps variant NPC / source names to a single canonical display name.
     * Call this on every name before using it as a map key.
     *
     * @param raw raw name from NPC, widget, or chat
     * @return canonical name, never null
     */
    public String normalizeBossName(String raw)
    {
        if (raw == null || raw.isEmpty()) return "Unknown";
        String l = raw.toLowerCase();

        // ── Raids ──────────────────────────────────────────────────────────────
        if (l.contains("corrupted gauntlet"))             return "Corrupted Gauntlet";
        if (l.contains("gauntlet"))                       return "The Gauntlet";
        if (l.contains("chambers") || l.contains("cox"))  return "Chambers of Xeric";
        if (l.contains("theatre") || l.contains("tob"))   return "Theatre of Blood";
        if (l.contains("tombs") || l.contains("toa"))     return "Tombs of Amascut";

        // ── GWD ───────────────────────────────────────────────────────────────
        if (l.contains("zilyana"))                        return "Commander Zilyana";
        if (l.contains("graardor"))                       return "General Graardor";
        if (l.contains("kree"))                           return "Kree'arra";
        if (l.contains("kril"))                           return "K'ril Tsutsaroth";
        if (l.equals("nex"))                              return "Nex";

        // ── Wilderness ────────────────────────────────────────────────────────
        if (l.contains("artio"))                          return "Artio";
        if (l.contains("callisto"))                       return "Callisto";
        if (l.contains("calvar"))                         return "Calvar'ion";
        if (l.contains("vet'ion") || l.contains("vetion"))return "Vet'ion";
        if (l.contains("spindel"))                        return "Spindel";
        if (l.contains("venenatis"))                      return "Venenatis";
        if (l.contains("corporeal"))                      return "Corporeal Beast";
        if (l.contains("chaos fanatic"))                  return "Chaos Fanatic";
        if (l.contains("scorpia"))                        return "Scorpia";
        if (l.contains("crazy archaeologist"))            return "Crazy Archaeologist";

        // ── Desert Treasure 2 ─────────────────────────────────────────────────
        if (l.contains("duke") || l.contains("sucellus")) return "Duke Sucellus";
        if (l.contains("leviathan"))                      return "The Leviathan";
        if (l.contains("vardorvis"))                      return "Vardorvis";
        if (l.contains("whisperer"))                      return "The Whisperer";

        // ── Varlamore / new content ───────────────────────────────────────────
        if (l.contains("royal titans") || l.contains("eldric") || l.contains("branda"))
            return "Royal Titans";
        if (l.contains("hueycoatl"))                      return "The Hueycoatl";
        if (l.contains("moons of peril") || l.contains("blue moon")
                || l.contains("blood moon") || l.contains("eclipse moon"))
            return "Moons of Peril";
        if (l.contains("yama"))                           return "Yama";
        if (l.contains("araxxor"))                        return "Araxxor";
        if (l.contains("scurrius"))                       return "Scurrius";
        if (l.contains("amoxliatl"))                      return "Amoxliatl";
        if (l.contains("tormented demon"))                return "Tormented Demon";
        if (l.contains("colosseum") || l.contains("fortis"))
            return "Fortis Colosseum";

        // ── Classic bosses ────────────────────────────────────────────────────
        if (l.contains("zulrah"))                         return "Zulrah";
        if (l.contains("vorkath"))                        return "Vorkath";
        if (l.contains("hydra"))                          return "Alchemical Hydra";
        if (l.contains("cerberus"))                       return "Cerberus";
        if (l.contains("abyssal sire"))                   return "Abyssal Sire";
        if (l.contains("kraken"))                         return "Kraken";
        if (l.contains("smoke devil") || l.contains("thermonuclear")) return "Smoke Devil";
        if (l.contains("phosani"))                        return "Phosani's Nightmare";
        if (l.contains("nightmare"))                      return "The Nightmare";
        if (l.contains("grotesque"))                      return "Grotesque Guardians";
        if (l.contains("kalphite queen"))                 return "Kalphite Queen";
        if (l.contains("kbd") || l.contains("king black dragon")) return "King Black Dragon";
        if (l.contains("dagannoth prime"))                return "Dagannoth Prime";
        if (l.contains("dagannoth rex"))                  return "Dagannoth Rex";
        if (l.contains("dagannoth supreme"))              return "Dagannoth Supreme";
        if (l.contains("skotizo"))                        return "Skotizo";
        if (l.contains("hespori"))                        return "Hespori";

        // ── Activities ────────────────────────────────────────────────────────
        if (l.contains("barrows"))                        return "Barrows";
        if (l.contains("tempoross"))                      return "Tempoross";
        if (l.contains("wintertodt"))                     return "Wintertodt";
        if (l.contains("zalcano"))                        return "Zalcano";

        // ── Clue tiers ────────────────────────────────────────────────────────
        if (l.contains("beginner clue") || (l.contains("beginner") && l.contains("clue")))
            return "Beginner Clue";
        if (l.contains("easy clue") || (l.contains("easy") && l.contains("clue")))
            return "Easy Clue";
        if (l.contains("medium clue") || (l.contains("medium") && l.contains("clue")))
            return "Medium Clue";
        if (l.contains("hard clue") || (l.contains("hard") && l.contains("clue")))
            return "Hard Clue";
        if (l.contains("elite clue") || (l.contains("elite") && l.contains("clue")))
            return "Elite Clue";
        if (l.contains("master clue") || (l.contains("master") && l.contains("clue")))
            return "Master Clue";

        return raw.trim();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY – CHAT MESSAGE PARSING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Parses a kill-count chat message and logs the result.
     * Does not modify stored data (KC is tracked via in-game kill events).
     *
     * @param message raw chat message string
     */
    public void parseKillCountMessage(String message)
    {
        Matcher m = KC_PATTERN.matcher(message);
        if (m.find())
        {
            String boss = normalizeBossName(m.group(1));
            int    kc   = Integer.parseInt(m.group(2));
            log.debug("KC from chat: '{}' = {}", boss, kc);
        }
    }

    /**
     * Inspects a lower-cased chat message and returns the recognised chest /
     * activity source name, or null if no match is found.
     *
     * <p>Called from RuneAlyticsPlugin.onChatMessage() to prime lastChestSource
     * before PlayerLootReceived fires.</p>
     *
     * @param lower lower-cased message text
     * @return canonical source name, or null if not recognised
     */
    public String detectChestSource(String lower)
    {
        // ── Activities ────────────────────────────────────────────────────────
        if (lower.contains("wintertodt") || lower.contains("cold of the wintertodt"))
            return "Wintertodt";

        if (lower.contains("subdued the spirit") || lower.contains("you have helped to subdue"))
            return "Tempoross";   // caller should snapshot inventory here

        if (lower.contains("zalcano") && (lower.contains("loot") || lower.contains("defeated")))
            return "Zalcano";

        // ── Raids ─────────────────────────────────────────────────────────────
        if (lower.contains("congratulations - your raid is complete")
                || lower.contains("congratulations! your raid is complete"))
            return "Chambers of Xeric";

        if (lower.contains("theatre of blood") && lower.contains("complete"))
            return "Theatre of Blood";

        if (lower.contains("tombs of amascut") && lower.contains("complete"))
            return "Tombs of Amascut";

        if (lower.contains("gauntlet") && lower.contains("complete"))
            return lower.contains("corrupted") ? "Corrupted Gauntlet" : "The Gauntlet";

        // ── Nightmare / Phosani ───────────────────────────────────────────────
        if (lower.contains("phosani") && lower.contains("defeated"))
            return "Phosani's Nightmare";
        if (lower.contains("nightmare") && lower.contains("defeated"))
            return "The Nightmare";

        // ── Varlamore / new bosses ────────────────────────────────────────────
        if (lower.contains("royal titans") || lower.contains("eldric the ice king")
                || lower.contains("branda the fire queen"))
            return "Royal Titans";

        if (lower.contains("colosseum") || lower.contains("fortis"))
            return "Fortis Colosseum";

        // ── Clue scrolls ──────────────────────────────────────────────────────
        if (lower.contains("treasure trail"))
        {
            if (lower.contains("beginner")) return "Beginner Clue";
            if (lower.contains("easy"))     return "Easy Clue";
            if (lower.contains("medium"))   return "Medium Clue";
            if (lower.contains("hard"))     return "Hard Clue";
            if (lower.contains("elite"))    return "Elite Clue";
            if (lower.contains("master"))   return "Master Clue";
            return "Clue Scroll";
        }

        return null;  // not recognised
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY – DROP RECORD CONVERSION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Converts raw ItemStacks to enriched DropRecords by looking up GE price
     * and item name via RuneLite's ItemManager.  Applies the minimum value filter.
     *
     * @param items raw ItemStack list
     * @return filtered, enriched DropRecord list (may be empty)
     */
    private List<LootStorageData.DropRecord> convertToDropRecords(List<ItemStack> items)
    {
        List<LootStorageData.DropRecord> drops = new ArrayList<>();

        for (ItemStack item : items)
        {
            ItemComposition comp = itemManager.getItemComposition(item.getId());
            int gePrice    = itemManager.getItemPrice(item.getId());
            int totalValue = gePrice * item.getQuantity();

            if (totalValue < config.minimumLootValue()) continue;

            LootStorageData.DropRecord drop = new LootStorageData.DropRecord();
            drop.setItemId   (item.getId());
            drop.setItemName (comp.getName());
            drop.setQuantity (item.getQuantity());
            drop.setGePrice  (gePrice);
            drop.setHighAlch (comp.getHaPrice());
            drop.setTotalValue(totalValue);
            drop.setHidden   (false);

            drops.add(drop);
        }

        return drops;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LISTENERS
    // ═════════════════════════════════════════════════════════════════════════

    /** Registers a listener for kill and refresh events. */
    public void addListener(LootTrackerUpdateListener listener)
    {
        listeners.add(listener);
    }

    private void notifyListeners(BossKillStats stats, NpcKillRecord kill)
    {
        for (LootTrackerUpdateListener l : listeners) l.onKillRecorded(kill, stats);
    }

    private void notifyDataRefresh()
    {
        for (LootTrackerUpdateListener l : listeners) l.onDataRefresh();
    }
}