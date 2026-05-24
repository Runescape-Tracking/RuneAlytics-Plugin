package com.runealytics;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.io.IOException;
import java.util.*;
import java.util.Map;
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
 *       Clue caskets, <b>The Whisperer (834)</b></li>
 *   <li><b>ItemContainerChanged</b> (inventory diff) – Tempoross / Wintertodt fallback</li>
 *   <li><b>ItemSpawned</b> (ground item window) – supplemental fallback for new content</li>
 *   <li><b>Chat messages</b> – completion / KC detection</li>
 *   <li><b>MenuOptionClicked → ItemContainerChanged diff</b> – Pickpocket / Thieving</li>
 * </ol>
 *
 * <h2>Deduplication</h2>
 * <ul>
 *   <li>NPC loot: no dedup (RuneLite fires once per kill; rapid kills of the same
 *       NPC type must all be counted separately).</li>
 *   <li>Player / chest loot: 2-second window per source name to prevent double-counting
 *       when both the widget-read path <em>and</em> PlayerLootReceived fire.</li>
 *   <li>Pickpocket loot: inventory-diff driven; each diff represents one successful
 *       pickpocket action.</li>
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

    /**
     * How deep {@link #collectWidgetItemsDeep} will recurse into a widget tree.
     * The Whisperer (and some other newer bosses) nest their reward items 3–4
     * layers below the top-level child, so 4 is a safe ceiling.
     */
    private static final int WIDGET_ITEM_SEARCH_DEPTH = 4;

    // ─────────────────────────────────────────────────────────────────────────
    //  PICKPOCKET / THIEVING  SUPPORT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prefix prepended to every pickpocket / thieving source name before it is
     * stored in {@link #bossKillStats} and on disk.
     *
     * <p>This lets {@link #isPickpocketSource(String)} detect the source type
     * after a restart without requiring additional storage fields.</p>
     *
     * <p>Example stored key: {@code "Pickpocket: Master Farmer"}</p>
     */
    public static final String PICKPOCKET_PREFIX = "Pickpocket: ";

    /**
     * Canonical display names for common thieving targets.
     *
     * <p>Raw NPC names arriving from {@code MenuOptionClicked} are passed through
     * {@link #normalizePickpocketNpc(String)} which maps them to these names so
     * that e.g. "H.A.M. Member" and "H.A.M. member" resolve to the same entry.</p>
     */
    private static final Map<String, String> PICKPOCKET_NAME_MAP =
            ImmutableMap.<String, String>builder()
                    // ── Low-level humans ─────────────────────────────────────
                    .put("man",                       "Man / Woman")
                    .put("woman",                     "Man / Woman")
                    .put("farmer",                    "Farmer")
                    .put("al-kharid warrior",         "Al-Kharid Warrior")
                    .put("rogue",                     "Rogue")
                    // ── Mid-tier ─────────────────────────────────────────────
                    .put("master farmer",             "Master Farmer")
                    .put("guard",                     "Guard")
                    .put("bearded pollnivnian bandit","Bandit (Pollnivneach)")
                    .put("pollnivnian bandit",        "Bandit (Pollnivneach)")
                    .put("desert bandit",             "Desert Bandit")
                    .put("fremennik citizen",         "Fremennik Citizen")
                    .put("cave goblin",               "Cave Goblin")
                    .put("warrior woman",             "Warrior Woman")
                    .put("gnome",                     "Gnome")
                    // ── High-tier ────────────────────────────────────────────
                    .put("paladin",                   "Paladin")
                    .put("hero",                      "Hero")
                    .put("knight of ardougne",        "Knight of Ardougne")
                    .put("elf",                       "Elf")
                    .put("menaphite thug",            "Menaphite Thug")
                    .put("wealthy citizen",           "Wealthy Citizen")
                    .put("pirate",                    "Pirate")
                    // ── HAM members ──────────────────────────────────────────
                    .put("h.a.m. member",             "H.A.M. Member")
                    .put("ham member",                "H.A.M. Member")
                    // ── Vyrewatch ────────────────────────────────────────────
                    .put("vyrewatch",                 "Vyrewatch")
                    .put("vyrewatch sentinel",        "Vyrewatch Sentinel")
                    // ── Blackjacking ─────────────────────────────────────────
                    .put("blackjack vendor",          "Blackjack Vendor")
                    // ── Stalls ───────────────────────────────────────────────
                    .put("tea stall",                 "Tea Stall")
                    .put("baker's stall",             "Baker's Stall")
                    .put("silk stall",                "Silk Stall")
                    .put("fur stall",                 "Fur Stall")
                    .put("fish stall",                "Fish Stall")
                    .put("crossbow stall",            "Crossbow Stall")
                    .put("spice stall",               "Spice Stall")
                    .put("gem stall",                 "Gem Stall")
                    .put("magic stall",               "Magic Stall")
                    .put("scimitar stall",            "Scimitar Stall")
                    .put("wine stall",                "Wine Stall")
                    // ── Prifddinas ───────────────────────────────────────────
                    .put("elf (worker)",              "Elf")
                    .put("elven clan worker",         "Elven Clan Worker")
                    // ── Sophanem / Kharidian ─────────────────────────────────
                    .put("hoardstalker",              "Hoardstalker")
                    // ── Monkey Madness ───────────────────────────────────────
                    .put("monkey",                    "Monkey")
                    .build();

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
            12166, 12167, 12193, 12214,                             // Duke / Leviathan / Vardorvis
            12205, 12223, 12224, 12225, 12226, 12227,               // The Whisperer (all phases)

            // ── Minigames / Skilling ─────────────────────────────────────────
            2025, 2026, 2027, 2028, 2029, 2030,                     // Barrows brothers
            10565, 7559, 9050, 10814                                 // Tempoross / Wintertodt / Zalcano
    );

    // ═════════════════════════════════════════════════════════════════════════
    //  BOSS NAME → PRIMARY NPC-ID MAP
    // ═════════════════════════════════════════════════════════════════════════
    private static final Map<String, Integer> BOSS_NAME_TO_ID =
            ImmutableMap.<String, Integer>builder()
                    .put("Commander Zilyana",   2215)
                    .put("General Graardor",    2260)
                    .put("Kree'arra",           6260)
                    .put("K'ril Tsutsaroth",    6203)
                    .put("Nex",                 11278)
                    .put("Royal Titans",        13751)
                    .put("The Hueycoatl",       14000)
                    .put("Moons of Peril",      13010)
                    .put("Yama",                12821)
                    .put("Araxxor",             13668)
                    .put("Scurrius",            12922)
                    .put("Amoxliatl",           13579)
                    .put("Tormented Demon",     13147)
                    .put("Duke Sucellus",       12166)
                    .put("The Leviathan",       12193)
                    .put("Vardorvis",           12205)
                    .put("The Whisperer",       12225)
                    .put("Artio",               11962)
                    .put("Callisto",            11963)
                    .put("Calvar'ion",          11946)
                    .put("Vet'ion",             11947)
                    .put("Spindel",             11993)
                    .put("Venenatis",           11973)
                    .put("Corporeal Beast",     319)
                    .put("Chambers of Xeric",   7554)
                    .put("Theatre of Blood",    10674)
                    .put("Tombs of Amascut",    11750)
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
                    .put("Barrows",             2025)
                    .put("Corrupted Gauntlet",  9036)
                    .put("The Gauntlet",        9035)
                    .put("Zalcano",             9050)
                    .put("Wintertodt",          7559)
                    .put("Tempoross",           10565)
                    .put("Fortis Colosseum",    0)
                    .put("Beginner Clue",       0)
                    .put("Easy Clue",           0)
                    .put("Medium Clue",         0)
                    .put("Hard Clue",           0)
                    .put("Elite Clue",          0)
                    .put("Master Clue",         0)
                    .build();

    // ═════════════════════════════════════════════════════════════════════════
    //  WIDGET / CONTAINER IDs
    //  Single source of truth lives in {@link RewardSources}. The legacy
    //  WIDGET_WHISPERER alias is kept here because RuneAlyticsPlugin still
    //  references it via {@code LootTrackerManager.WIDGET_WHISPERER}.
    // ═════════════════════════════════════════════════════════════════════════
    static final int WIDGET_WHISPERER = RewardSources.WIDGET_WHISPERER;

    // ═════════════════════════════════════════════════════════════════════════
    //  INJECTED DEPENDENCIES
    // ═════════════════════════════════════════════════════════════════════════

    private final Client                   client;
    private final ClientThread             clientThread;
    private final ItemManager              itemManager;
    private final RunealyticsConfig        config;
    private final RuneAlyticsState         state;
    private final LootStorageManager       storageManager;
    private final LootTrackerApiClient     apiClient;
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
     * NOT used for NpcLootReceived or pickpocket paths.
     */
    private final Map<String, Long> lastPlayerLootTime = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    //  LIVE-SYNC DEBOUNCE
    //
    //  After every kill we kick off a debounced bulk-sync so drops show up on
    //  the website within a few seconds (issue #2 — the 60-second scheduled
    //  task was too slow to feel "live").  At most one upload is in flight at
    //  a time; rapid consecutive kills coalesce into one HTTP call.
    // ─────────────────────────────────────────────────────────────────────────

    private static final long LIVE_SYNC_DEBOUNCE_MS = 2_500;
    private final java.util.concurrent.atomic.AtomicBoolean liveSyncPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
     * applied because RuneLite guarantees this event fires exactly once per kill.</p>
     *
     * @param npc   the NPC that was killed
     * @param items the dropped items in our internal {@link ItemStack} format
     */
    public void processNpcLoot(NPC npc, List<ItemStack> items)
    {
        if (!config.enableLootTracking() || npc == null || npc.getName() == null)
            return;

        log.info("NPC loot: '{}' id={} cb={} items={}",
                npc.getName(), npc.getId(), npc.getCombatLevel(), items.size());

        String name = normalizeBossName(npc.getName());
        boolean isBoss = isBoss(npc.getId(), name);

        if (!isBoss && !config.trackAllNpcs())
        {
            log.warn("Filtered NPC (not a tracked boss): '{}' id={} "
                            + "→ enable 'Track All NPCs' or add id to TRACKED_BOSS_IDS",
                    name, npc.getId());
            return;
        }

        List<LootStorageData.DropRecord> drops = convertToDropRecords(items);
        if (drops.isEmpty()) return;

        recordKill(name, npc.getId(), npc.getCombatLevel(), client.getWorld(), drops);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 1b – ZERO-LOOT NPC KILLS
    //
    //  RuneLite's NpcLootReceived event only fires when the kill produced at
    //  least one item.  Many NPCs (low-level mobs, some bosses on a dry kill)
    //  die without dropping anything, which silently dropped the kill from
    //  every counter that hangs off NpcLootReceived.
    //
    //  RuneAlyticsPlugin now also watches ActorDeath + HitsplatApplied to
    //  detect a "I killed it" event that is independent of loot, and routes
    //  those kills here so the per-NPC kill count stays accurate.
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * NPCs whose drops arrive via a reward chest / widget read, not via
     * {@link net.runelite.client.events.NpcLootReceived}.
     *
     * <p>These bosses must <b>NOT</b> be tracked by the zero-loot path because
     * the chest-read path ({@code processPlayerLoot}) is the canonical kill
     * recorder for them.  Allowing the ActorDeath path to also count would
     * double-count every raid clear.</p>
     */
    private static final Set<Integer> CHEST_LOOT_NPC_IDS = ImmutableSet.<Integer>builder()
            // CoX (Great Olm head / hands)
            .add(7554).add(7555).add(7556)
            // ToB
            .add(10674).add(10698).add(10702).add(10704).add(10707).add(10847)
            // ToA
            .add(11750).add(11751).add(11752).add(11753).add(11754).add(11770).add(11771)
            // Barrows brothers (chest at end)
            .add(2025).add(2026).add(2027).add(2028).add(2029).add(2030)
            // Gauntlet / Corrupted Gauntlet (Hunllef)
            .add(9035).add(9036)
            // Zalcano (chest)
            .add(9050)
            // The Nightmare / Phosani's Nightmare (chest)
            .add(9415).add(9416).add(9425).add(9426)
            // Moons of Peril (lunar chest)
            .add(13010).add(13011).add(13012).add(13013).add(13014).add(13015)
            // Yama (chest)
            .add(12821).add(12822).add(12823)
            // Royal Titans (chest)
            .add(13751).add(13752).add(13753).add(13754).add(13755).add(13756).add(13757).add(13758)
            // The Hueycoatl (chest)
            .add(14000).add(14001).add(14002).add(14003).add(14013).add(14014)
            // The Whisperer (special ground-item flow, already counted there)
            .add(12205).add(12223).add(12224).add(12225).add(12226).add(12227)
            // Fortis Colosseum (widget read)
            .add(12816).add(12817).add(12818)
            .build();

    /**
     * Records an NPC kill that produced no loot.
     *
     * <p>Respects the same filters as {@link #processNpcLoot}:</p>
     * <ul>
     *   <li>Loot-tracking config must be enabled</li>
     *   <li>NPC must be a tracked boss <em>or</em> {@code trackAllNpcs} must be on</li>
     *   <li>NPC must not be in {@link #CHEST_LOOT_NPC_IDS}</li>
     * </ul>
     *
     * <p>Called from {@code RuneAlyticsPlugin} after {@code ActorDeath} on an
     * NPC the local player damaged, when no {@code NpcLootReceived} arrived
     * within the grace window.</p>
     *
     * @param npc the dying NPC
     */
    public void processZeroLootKill(NPC npc)
    {
        if (!config.enableLootTracking()) return;
        if (npc == null || npc.getName() == null) return;

        int npcId = npc.getId();
        if (CHEST_LOOT_NPC_IDS.contains(npcId))
        {
            log.debug("Zero-loot kill suppressed for chest-based NPC '{}' (id={})",
                    npc.getName(), npcId);
            return;
        }

        String name = normalizeBossName(npc.getName());
        boolean isBoss = isBoss(npcId, name);

        if (!isBoss && !config.trackAllNpcs())
        {
            log.debug("Zero-loot kill filtered (not a tracked boss): '{}' id={}", name, npcId);
            return;
        }

        log.info("Zero-loot kill: '{}' id={} cb={}", name, npcId, npc.getCombatLevel());
        recordKill(name, npcId, npc.getCombatLevel(),
                client.getWorld(), Collections.emptyList());
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
     * @param source display name of the loot source (e.g. "Barrows", "The Whisperer")
     * @param items  item list in our internal {@link ItemStack} format
     */
    public void processPlayerLoot(String source, List<ItemStack> items)
    {
        if (items == null || items.isEmpty())
        {
            log.debug("processPlayerLoot: empty items – source='{}'", source);
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
    //  LOOT PATH 6 – PICKPOCKET / THIEVING  (MenuOptionClicked → inventory diff)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Records a single successful pickpocket action from a thieving NPC or stall.
     *
     * <p>Called from {@code RuneAlyticsPlugin.onItemContainerChanged()} after an
     * inventory diff reveals new items following a "Pickpocket" menu click.
     * Each call to this method counts as <b>one</b> pickpocket attempt.</p>
     *
     * <p>The NPC name is normalised via {@link #normalizePickpocketNpc(String)}
     * and then prefixed with {@link #PICKPOCKET_PREFIX} before storage, which
     * lets {@link #isPickpocketSource(String)} identify these entries after a
     * plugin restart.</p>
     *
     * @param rawNpcName raw NPC / stall name from the menu target (HTML tags stripped)
     * @param items      items gained from this single pickpocket action
     */
    public void processPickpocketLoot(String rawNpcName, List<ItemStack> items)
    {
        if (!config.enableLootTracking() || !config.enablePickpocketTracking())
            return;

        if (items == null || items.isEmpty()) return;

        // Normalise to canonical thieving-NPC display name, then add prefix
        String canonical = normalizePickpocketNpc(rawNpcName);
        String storedKey = PICKPOCKET_PREFIX + canonical;

        List<LootStorageData.DropRecord> drops = convertToDropRecords(items);
        if (drops.isEmpty()) return;

        log.info("Pickpocket: '{}' → '{}' ({} items)", rawNpcName, storedKey, drops.size());

        // npcId = 0 (stalls/pickpocket targets don't have a meaningful combat NPC ID)
        recordKill(storedKey, 0, 0, client.getWorld(), drops);
    }

    /**
     * Returns {@code true} if {@code npcName} was recorded via the pickpocket path.
     *
     * <p>The pickpocket prefix ({@value #PICKPOCKET_PREFIX}) is prepended when the
     * entry is first created, so this check works correctly after a plugin restart.</p>
     *
     * @param npcName stored boss / source name (from {@link BossKillStats#getNpcName()})
     * @return true if this entry represents a thieving / pickpocket source
     */
    public boolean isPickpocketSource(String npcName)
    {
        return npcName != null && npcName.startsWith(PICKPOCKET_PREFIX);
    }

    /**
     * Strips the {@link #PICKPOCKET_PREFIX} from a stored source name and returns
     * just the canonical NPC / stall display name.
     *
     * <p>If the name does not start with the prefix it is returned unchanged.</p>
     *
     * @param storedName stored source name (may or may not have the prefix)
     * @return display-friendly name without the prefix
     */
    public String stripPickpocketPrefix(String storedName)
    {
        if (storedName == null) return "Unknown";
        if (storedName.startsWith(PICKPOCKET_PREFIX))
            return storedName.substring(PICKPOCKET_PREFIX.length());
        return storedName;
    }

    public static final String SKILLING_PREFIX = "Skilling: ";

    public void processSkillLoot(String skill, List<ItemStack> items)
    {
        if (!config.enableLootTracking()) return;
        if (items == null || items.isEmpty()) return;

        List<LootStorageData.DropRecord> drops = convertToDropRecords(items);
        if (drops.isEmpty()) return;

        String storedKey = SKILLING_PREFIX + skill;
        log.info("Skilling: '{}' — {} items", skill, drops.size());
        recordKill(storedKey, 0, 0, client.getWorld(), drops);
    }

    public boolean isSkillingSource(String npcName)
    {
        return npcName != null && npcName.startsWith(SKILLING_PREFIX);
    }

    public String stripSkillingPrefix(String npcName)
    {
        return (npcName != null && npcName.startsWith(SKILLING_PREFIX))
                ? npcName.substring(SKILLING_PREFIX.length())
                : npcName;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 7 – IMPLING / IMP JAR LOOT
    //
    //  Catching an imp gives the player an impling jar (which is recorded as a
    //  Hunter skilling drop via the existing path). The actual loot only
    //  materialises when the player "Loot-jar"s the impling jar, which yields
    //  no XP — so the generic skilling diff misses it.
    //
    //  We expose a dedicated entry point that the plugin calls after detecting
    //  a "Loot-jar" or "Loot" click on an "* impling jar" item, then diffing
    //  the inventory. Each impling tier is tracked separately so the user
    //  sees "Impling: Eclectic" instead of everything bucketing into "Hunter".
    // ═════════════════════════════════════════════════════════════════════════

    public static final String IMPLING_PREFIX = "Impling: ";

    /**
     * Maps a raw {@code "* impling jar"} item name to the user-facing tier label.
     * Falls back to title-casing the jar prefix when the tier isn't in the map
     * so new content is still tracked under a sensible name.
     */
    private static final Map<String, String> IMPLING_JAR_NAMES =
            ImmutableMap.<String, String>builder()
                    .put("baby impling jar",     "Baby")
                    .put("young impling jar",    "Young")
                    .put("gourmet impling jar",  "Gourmet")
                    .put("earth impling jar",    "Earth")
                    .put("essence impling jar",  "Essence")
                    .put("eclectic impling jar", "Eclectic")
                    .put("nature impling jar",   "Nature")
                    .put("magpie impling jar",   "Magpie")
                    .put("ninja impling jar",    "Ninja")
                    .put("crystal impling jar",  "Crystal")
                    .put("dragon impling jar",   "Dragon")
                    .put("lucky impling jar",    "Lucky")
                    .build();

    /**
     * Records loot extracted from a single impling jar.
     *
     * @param jarItemName the raw item name of the jar that was looted
     * @param items       items gained after the loot-jar action
     */
    public void processImplingLoot(String jarItemName, List<ItemStack> items)
    {
        if (!config.enableLootTracking()) return;
        if (items == null || items.isEmpty()) return;

        List<LootStorageData.DropRecord> drops = convertToDropRecords(items);
        if (drops.isEmpty()) return;

        String tier = canonicaliseImplingJar(jarItemName);
        String storedKey = IMPLING_PREFIX + tier;
        log.info("Impling jar loot: '{}' tier='{}' ({} items)", jarItemName, tier, drops.size());
        recordKill(storedKey, 0, 0, client.getWorld(), drops);
    }

    private static String canonicaliseImplingJar(String raw)
    {
        if (raw == null || raw.isEmpty()) return "Unknown";
        String lower = raw.trim().toLowerCase();
        String mapped = IMPLING_JAR_NAMES.get(lower);
        if (mapped != null) return mapped;
        // Fallback: "Foo impling jar" -> "Foo"
        int idx = lower.indexOf(" impling jar");
        if (idx > 0) return Character.toUpperCase(lower.charAt(0)) + lower.substring(1, idx);
        return raw.trim();
    }

    public boolean isImplingSource(String npcName)
    {
        return npcName != null && npcName.startsWith(IMPLING_PREFIX);
    }

    public String stripImplingPrefix(String storedName)
    {
        return (storedName != null && storedName.startsWith(IMPLING_PREFIX))
                ? storedName.substring(IMPLING_PREFIX.length())
                : storedName;
    }

    /**
     * Appends additional drops to the most recent kill record for {@code npcName}.
     *
     * <p>Intended for Ring of Wealth coins that bypass {@code ItemSpawned} and
     * would otherwise be lost.  Both the in-memory {@link BossKillStats} and the
     * persistent {@link LootStorageManager} are updated atomically.</p>
     *
     * @param npcName normalised boss name (already through {@link #normalizeBossName})
     * @param items   items to append — typically just coins from the RoW pickup
     */
    public void appendDropsToLastKill(String npcName, List<ItemStack> items)
    {
        if (items == null || items.isEmpty()) return;

        BossKillStats stats = bossKillStats.get(npcName);
        if (stats == null || stats.getKillHistory().isEmpty())
        {
            log.debug("appendDropsToLastKill: no existing kill for '{}' – skipping", npcName);
            return;
        }

        List<LootStorageData.DropRecord> newDrops = convertToDropRecords(items);
        if (newDrops.isEmpty()) return;

        // Update in-memory kill record
        LootStorageData.KillRecord lastKill =
                stats.getKillHistory().get(stats.getKillHistory().size() - 1);
        lastKill.getDrops().addAll(newDrops);
        lastKill.setSyncedToServer(false);

        // Update in-memory aggregated stats
        long addedValue = 0;
        for (LootStorageData.DropRecord dr : newDrops)
        {
            addedValue += dr.getTotalValue();
            if (dr.getTotalValue() > stats.getHighestDrop())
                stats.setHighestDrop(dr.getTotalValue());
        }
        stats.setTotalLootValue(stats.getTotalLootValue() + addedValue);

        // Persist and re-sync
        storageManager.appendDropsToLastKill(npcName, newDrops);

        notifyListeners(stats, lastKill);

        log.info("appendDropsToLastKill: {} drop(s) added to '{}' last kill (+{} gp)",
                newDrops.size(), npcName, addedValue);
    }

    /**
     * Records a pet drop against the most recent kill for the given NPC.
     *
     * <p>If a specific item was identified in the inventory it is used; otherwise a
     * generic "Pet" entry (itemId {@value #PET_ITEM_ID_UNKNOWN}) is appended so the
     * drop is never silently lost.</p>
     *
     * @param npcName  normalised NPC name (storage key)
     * @param petItem  the pet item found via inventory diff, or {@code null} if unknown
     */
    public void appendPetDrop(String npcName, ItemStack petItem)
    {
        BossKillStats stats = bossKillStats.get(npcName);
        if (stats == null || stats.getKillHistory().isEmpty())
        {
            log.warn("[Pet] No existing kill record for '{}' – pet drop not recorded", npcName);
            return;
        }

        LootStorageData.DropRecord drop = new LootStorageData.DropRecord();
        if (petItem != null)
        {
            ItemComposition comp = itemManager.getItemComposition(petItem.getId());
            drop.setItemId   (petItem.getId());
            drop.setItemName (comp.getName());
            drop.setQuantity (1);
            drop.setGePrice  (itemManager.getItemPrice(petItem.getId()));
            drop.setHighAlch (comp.getHaPrice());
            drop.setTotalValue(drop.getGePrice());
        }
        else
        {
            drop.setItemId   (PET_ITEM_ID_UNKNOWN);
            drop.setItemName ("Pet");
            drop.setQuantity (1);
        }
        drop.setPet(true);
        drop.setHidden(false);

        LootStorageData.KillRecord lastKill =
                stats.getKillHistory().get(stats.getKillHistory().size() - 1);
        lastKill.getDrops().add(0, drop); // index 0 so storage order also has pet first
        lastKill.setSyncedToServer(false);

        stats.setTotalLootValue(stats.getTotalLootValue() + drop.getTotalValue());

        storageManager.appendDropsToLastKill(npcName, Collections.singletonList(drop));
        notifyListeners(stats, lastKill);

        log.info("[Pet] Recorded '{}' pet drop for '{}'", drop.getItemName(), npcName);
    }

    /** Sentinel item-ID used when the pet item cannot be determined from inventory diff. */
    public static final int PET_ITEM_ID_UNKNOWN = -1;

    /**
     * Maps a raw NPC / stall name (HTML-stripped, as received from the menu target)
     * to a canonical display name used as the storage key.
     *
     * <p>Falls back to title-casing the raw name when no explicit mapping is found,
     * so completely new thieving targets are still recorded under a sensible name.</p>
     *
     * @param raw raw NPC name string (HTML tags already stripped by the plugin)
     * @return canonical display name for this thieving target
     */
    public String normalizePickpocketNpc(String raw)
    {
        if (raw == null || raw.isEmpty()) return "Unknown";

        String lower = raw.trim().toLowerCase();
        String mapped = PICKPOCKET_NAME_MAP.get(lower);
        if (mapped != null) return mapped;

        // Partial-match fallbacks for names with extra suffixes (e.g. "Guard (level-19)")
        for (Map.Entry<String, String> entry : PICKPOCKET_NAME_MAP.entrySet())
        {
            if (lower.startsWith(entry.getKey())) return entry.getValue();
        }

        // Unknown thieving target: title-case and return as-is so it still gets recorded
        String[] words = raw.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words)
        {
            if (!w.isEmpty())
            {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) sb.append(w.substring(1).toLowerCase());
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 3 – WIDGET CONTAINER READS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Reads loot from a numeric InventoryID container (Barrows, CoX, ToB, ToA,
     * Gauntlet, Nightmare, Zalcano, Wintertodt).
     *
     * @param sourceName  display name for the loot source
     * @param containerId integer container ID
     */
    public void readRewardContainer(String sourceName, int containerId)
    {
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
     * Reads loot from a widget tree by walking all children of the given widget group ID.
     *
     * @param sourceName  display name for the loot source
     * @param groupId     widget group ID (from onWidgetLoaded event)
     * @param maxChildren how many top-level child indices to search
     */
    public void readWidgetLoot(String sourceName, int groupId, int maxChildren)
    {
        long delayMs = (groupId == WIDGET_WHISPERER) ? 600L : 300L;

        executorService.schedule(() -> clientThread.invokeLater(() ->
        {
            List<ItemStack> items = new ArrayList<>();

            for (int i = 0; i < maxChildren; i++)
            {
                Widget w = client.getWidget(groupId, i);
                if (w == null) continue;
                collectWidgetItemsDeep(w, items, WIDGET_ITEM_SEARCH_DEPTH);
            }

            if (items.isEmpty())
            {
                log.debug("readWidgetLoot: no items found for '{}' (group {})",
                        sourceName, groupId);
                return;
            }

            log.info("Widget loot '{}' (group {}): {} items", sourceName, groupId, items.size());
            processPlayerLoot(sourceName, items);

        }), delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Reads loot from the Clue scroll reward casket widget (group 73, child 10).
     *
     * @param sourceName clue tier label e.g. "Elite Clue"
     */
    public void readClueReward(String sourceName)
    {
        executorService.schedule(() -> clientThread.invokeLater(() ->
        {
            Widget parent = client.getWidget(RewardSources.WIDGET_CLUE, 10);
            if (parent == null)
            {
                log.warn("Clue reward widget (73,10) not found for '{}'", sourceName);
                return;
            }

            List<ItemStack> items = new ArrayList<>();
            collectWidgetItemsDeep(parent, items, WIDGET_ITEM_SEARCH_DEPTH);

            if (items.isEmpty())
            {
                log.debug("readClueReward: no items for '{}'", sourceName);
                return;
            }

            log.info("Clue reward '{}': {} items", sourceName, items.size());
            processPlayerLoot(sourceName, items);

        }), 300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ── Widget item collection helpers ────────────────────────────────────────

    private void collectWidgetItemsDeep(Widget w, List<ItemStack> items, int depth)
    {
        if (w == null || depth < 0) return;

        if (w.getItemId() > 0 && w.getItemQuantity() > 0)
            items.add(new ItemStack(w.getItemId(), w.getItemQuantity()));

        if (depth == 0) return;

        Widget[] children = w.getChildren();
        if (children != null)
            for (Widget child : children)
                collectWidgetItemsDeep(child, items, depth - 1);

        Widget[] dynamic = w.getDynamicChildren();
        if (dynamic != null)
            for (Widget child : dynamic)
                collectWidgetItemsDeep(child, items, depth - 1);
    }

    /**
     * Reads loot from a configured {@link RewardSources.Source} entry.
     * Uses the container path when the source has a {@code containerId},
     * otherwise walks the widget tree.
     */
    public void readReward(RewardSources.Source src, int widgetGroupId)
    {
        if (src == null) return;
        if (src.containerId != null)
        {
            readRewardContainer(src.displayName, src.containerId);
        }
        else
        {
            readWidgetLoot(src.displayName, widgetGroupId, src.widgetMaxChildren);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 4 – INVENTORY DIFF  (Tempoross / Wintertodt fallback)
    // ═════════════════════════════════════════════════════════════════════════

    public void processInventoryDiff(String sourceName, List<ItemStack> newItems)
    {
        if (newItems == null || newItems.isEmpty()) return;
        log.info("Inventory diff '{}': {} new items", sourceName, newItems.size());
        processPlayerLoot(sourceName, newItems);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 5 – GROUND ITEM SPAWN  (ItemSpawned fallback / supplement)
    //
    //  This path serves two distinct purposes:
    //    1. SUPPLEMENT: NpcLootReceived already fired for this NPC and the
    //       ground items are the same drops. We must not record a second kill
    //       — instead we dedupe against the last kill's drops and append only
    //       the truly-extra items (e.g. coins added by RoW that bypassed the
    //       primary event).
    //    2. FALLBACK: NpcLootReceived did NOT fire (new content RuneLite
    //       hasn't catalogued yet). The ground items are the only signal we
    //       have — record them as a fresh kill.
    //
    //  Calling {@code processNpcLoot} unconditionally here is what caused
    //  every Callisto / Vorkath / etc. kill to be counted twice (issue #12).
    // ═════════════════════════════════════════════════════════════════════════

    public void processGroundItemBatch(NPC npc, List<ItemStack> items)
    {
        if (npc == null || items == null || items.isEmpty()) return;

        String name = normalizeBossName(npc.getName());
        BossKillStats stats = bossKillStats.get(name);

        // No prior kill for this NPC → genuine FALLBACK path
        if (stats == null || stats.getKillHistory().isEmpty())
        {
            log.info("Ground items from '{}' (id={}): {} items — no prior kill, treating as fresh",
                    npc.getName(), npc.getId(), items.size());
            processNpcLoot(npc, items);
            return;
        }

        // Prior kill exists → SUPPLEMENT path.  Subtract items already on the
        // last kill record so we don't double-count anything reported by
        // NpcLootReceived.
        LootStorageData.KillRecord lastKill =
                stats.getKillHistory().get(stats.getKillHistory().size() - 1);

        // Only dedupe against kills that happened in the last few seconds —
        // anything older is a separate kill and the ground items belong to
        // the new one as a fallback.
        long ageMs = System.currentTimeMillis() - lastKill.getTimestamp();
        if (ageMs > 10_000L)
        {
            log.info("Ground items from '{}': last kill {}ms ago — treating as fresh",
                    npc.getName(), ageMs);
            processNpcLoot(npc, items);
            return;
        }

        Map<Integer, Integer> recorded = new HashMap<>();
        for (LootStorageData.DropRecord dr : lastKill.getDrops())
            recorded.merge(dr.getItemId(), dr.getQuantity(), Integer::sum);

        List<ItemStack> extras = new ArrayList<>();
        for (ItemStack item : items)
        {
            int alreadyRecorded = recorded.getOrDefault(item.getId(), 0);
            int delta = item.getQuantity() - alreadyRecorded;
            if (delta > 0) extras.add(new ItemStack(item.getId(), delta));
        }

        if (extras.isEmpty())
        {
            log.debug("Ground items for '{}': all already recorded by NpcLootReceived — skipping",
                    npc.getName());
            return;
        }

        log.info("Ground items for '{}': appending {} extra item type(s) to last kill",
                npc.getName(), extras.size());
        appendDropsToLastKill(name, extras);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CORE KILL RECORDING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Variant of {@link #processPlayerLoot} that also supplies the authoritative
     * in-game kill count. Used for bosses (e.g. The Whisperer) where the game KC
     * is parsed from a chat message and should override the plugin's local counter.
     */
    public void processPlayerLootWithGameKC(String source, List<ItemStack> items, int gameKC)
    {
        if (items == null || items.isEmpty())
        {
            log.debug("processPlayerLootWithGameKC: empty items – source='{}'", source);
            return;
        }

        String name = normalizeBossName(source);
        long now = System.currentTimeMillis();
        Long last = lastPlayerLootTime.get(name);

        if (last != null && (now - last) < PLAYER_LOOT_DEDUP_MS)
        {
            log.debug("processPlayerLootWithGameKC: dedup suppressed '{}' ({}ms ago)", name, now - last);
            return;
        }

        lastPlayerLootTime.put(name, now);

        int npcId = BOSS_NAME_TO_ID.getOrDefault(name, 0);
        log.info("Player loot (gameKC={}): '{}' (id={}) items={}", gameKC, name, npcId, items.size());

        List<LootStorageData.DropRecord> drops = convertToDropRecords(items);
        recordKill(name, npcId, 0, client.getWorld(), drops, gameKC);
    }

    // ── recordKill overloads ──────────────────────────────────────────────────

    private void recordKill(
            String npcName, int npcId, int combatLevel, int world,
            List<LootStorageData.DropRecord> drops)
    {
        recordKill(npcName, npcId, combatLevel, world, drops, -1);
    }


    /**
     * The single write path for all loot sources.
     *
     * <p>If {@code gameKC} is positive, the local {@link BossKillStats} counter
     * is synced to {@code gameKC - 1} before the kill is added.</p>
     *
     * <p>This intentionally does <b>NOT</b> trigger a server sync.  All server
     * traffic happens via the 60-second scheduled task
     * ({@link RuneAlyticsPlugin#syncDataScheduled}) which batches unsynced
     * kills via {@link #uploadUnsyncedKills()}.  Per-kill syncing was causing
     * one HTTP call per drop, which would melt the server and rate-limit the
     * client during long boss sessions.</p>
     */
    private void recordKill(
            String npcName, int npcId, int combatLevel, int world,
            List<LootStorageData.DropRecord> drops, int gameKC)
    {
        // 1. Get or create the UI statistics container
        BossKillStats stats = bossKillStats.computeIfAbsent(
                npcName, k -> new BossKillStats(npcName, npcId));

        // 2. Determine the kill number
        int killNumber = (gameKC > 0)
                ? gameKC
                : stats.getKillCount() + 1;

        // 3. Create the storage-compatible record
        LootStorageData.KillRecord killRecord = new LootStorageData.KillRecord();
        killRecord.setTimestamp(System.currentTimeMillis());
        killRecord.setKillNumber(killNumber);
        killRecord.setWorld(world);
        killRecord.setCombatLevel(combatLevel);
        killRecord.setDrops(drops);
        killRecord.setSyncedToServer(false); // picked up by the next batch
        killRecord.setGameMode(state.getCurrentGameMode());
        killRecord.setAccountType(state.getCurrentAccountSubtype());

        // 4. Update the in-memory UI stats and persistent storage
        stats.addKill(killRecord);
        storageManager.addKill(
                npcName, npcId, combatLevel, killNumber,
                world, state.getPrestige(), drops);

        notifyListeners(stats, killRecord);

        // Kick off a debounced live sync so the website updates within a few
        // seconds.  Multiple kills inside the debounce window batch into one
        // HTTP call (see LIVE_SYNC_DEBOUNCE_MS).
        scheduleLiveSync();

        log.info("Kill recorded: '{}' #{} (gameKC={}) – {} drops, {} gp",
                npcName, killNumber, gameKC > 0 ? gameKC : "n/a",
                drops.size(),
                drops.stream().mapToLong(LootStorageData.DropRecord::getTotalValue).sum());
    }

    /**
     * Schedules a bulk-sync upload {@value #LIVE_SYNC_DEBOUNCE_MS} ms in the
     * future. If a sync is already scheduled, this is a no-op — the in-flight
     * sync will pick up any kills recorded in the meantime.
     */
    private void scheduleLiveSync()
    {
        if (!config.syncLootToServer()) return;
        if (state.getVerifiedUsername() == null) return;

        if (liveSyncPending.compareAndSet(false, true))
        {
            executorService.schedule(() ->
            {
                try
                {
                    if (state.canSync()) uploadUnsyncedKills();
                }
                catch (Exception e)
                {
                    log.warn("Live sync failed: {}", e.getMessage());
                }
                finally
                {
                    liveSyncPending.set(false);
                }
            }, LIVE_SYNC_DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    public void downloadKillHistoryFromServer()
    {
        if (!allowSync) { log.warn("downloadKillHistoryFromServer: blocked – use manual sync"); return; }

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
        catch (Exception e) { log.error("Failed to download kill history", e); }
        finally             { state.endSync(); }
    }

    public void uploadUnsyncedKills()
    {
        String username = state.getVerifiedUsername();
        if (username == null || !state.canSync()) return;

        // Start sync state immediately to prevent double-triggering
        state.startSync();

        // Move the entire process to a background thread to eliminate game lag
        new Thread(() -> {
            try
            {
                Map<String, List<LootStorageData.KillRecord>> unsynced =
                        storageManager.getAllUnsyncedKills();

                if (unsynced.isEmpty()) return;

                int total = unsynced.values().stream().mapToInt(List::size).sum();
                log.info("Uploading {} unsynced kills across {} bosses", total, unsynced.size());

                final int BATCH_SIZE = 50;
                List<LootStorageData.KillRecord> batchBuffer = new ArrayList<>();
                Map<String, List<LootStorageData.KillRecord>> currentBatchMap = new HashMap<>();

                // Iterate by boss directly to avoid building large temporary maps
                for (Map.Entry<String, List<LootStorageData.KillRecord>> entry : unsynced.entrySet())
                {
                    String bossName = entry.getKey();
                    List<LootStorageData.KillRecord> kills = new ArrayList<>(entry.getValue());

                    for (LootStorageData.KillRecord kill : kills)
                    {
                        currentBatchMap.computeIfAbsent(bossName, k -> new ArrayList<>()).add(kill);
                        batchBuffer.add(kill);

                        // When batch reaches limit, sync it
                        if (batchBuffer.size() >= BATCH_SIZE)
                        {
                            processBatch(username, currentBatchMap);
                            batchBuffer.clear();
                            currentBatchMap.clear();
                            Thread.sleep(500); // Safe to sleep on background thread
                        }
                    }
                }

                // Sync any remaining kills in the final partial batch
                if (!batchBuffer.isEmpty())
                {
                    processBatch(username, currentBatchMap);
                }
            }
            catch (Exception e)
            {
                log.error("Upload unsynced kills failed", e);
            }
            finally
            {
                // Ensure the sync state is released so the user can sync again
                state.endSync();

                // Trigger a UI refresh if the panel is open
                if (panel != null)
                {
                    SwingUtilities.invokeLater(() -> panel.refreshDisplay());
                }
            }
        }, "RuneAlytics-Sync").start();
    }

    /**
     * Helper to handle the actual API call and storage update for one batch.
     *
     * <p>The per-boss {@link LootStorageData.BossKillData} map is needed so the
     * client can populate {@code npc_id} / {@code prestige} on every kill —
     * those fields live on the boss record, not on individual kills.</p>
     */
    private void processBatch(String username, Map<String, List<LootStorageData.KillRecord>> byBoss) throws IOException
    {
        LootStorageData data = storageManager.getCurrentData();
        Map<String, LootStorageData.BossKillData> bossLookup =
                data != null ? data.getBossKills() : Collections.emptyMap();

        boolean ok = apiClient.bulkSyncKills(username, byBoss, bossLookup);
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
            log.error("Batch upload failed for {} bosses", byBoss.size());
        }
    }

    public void performManualSync(String username)
    {
        if (username == null || username.isEmpty()) return;

        executorService.submit(() -> {
            try
            {
                allowSync = true;
                downloadKillHistoryFromServer();
                uploadUnsyncedKills();
                SwingUtilities.invokeLater(() -> {
                    if (panel != null) panel.showSyncCompleted();
                    else log.warn("performManualSync: panel is null, cannot show sync completed");
                });
            }
            catch (Exception e)
            {
                log.error("Manual sync failed", e);
                SwingUtilities.invokeLater(() -> { if (panel != null) panel.showSyncFailed(e.getMessage()); });
            }
            finally { allowSync = false; }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════

    public void loadFromStorage()
    {
        if (hasLoadedData) { log.debug("Data already loaded this session"); return; }
        log.info("Loading local loot data");
        refreshLootDisplay();
        hasLoadedData = true;
    }

    public void loadLocalDataForUser(String username)
    {
        log.info("Loading local data for '{}'", username);
        storageManager.loadLootData(username);
        clientThread.invokeLater(this::refreshLootDisplay);
    }

    private void refreshLootDisplay()
    {
        LootStorageData data = storageManager.getCurrentData();

        // Always restore the persisted RuneAlytics-specific ignore list
        // (issue #6 — must survive a restart).
        rehydrateHiddenDrops();

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
                    stats.addKill(kr);
                }

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
        if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
    }

    public void onAccountChanged(String newUsername)
    {
        bossKillStats.clear();
        hasLoadedData = false;
        loadFromStorage();
        if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
    }

    public void onPlayerLoggedInAndVerified()
    {
        loadFromStorage();
    }

    public Map<String, BossKillStats> getBossKillStats()
    {
        return Collections.unmodifiableMap(bossKillStats);
    }

    public List<BossKillStats.AggregatedDrop> getStorageDropsForBoss(String npcName)
    {
        LootStorageData data = storageManager.getCurrentData();
        if (data == null) return Collections.emptyList();

        LootStorageData.BossKillData bd = data.getBossKills().get(npcName);
        if (bd == null || bd.getAggregatedDrops() == null || bd.getAggregatedDrops().isEmpty())
            return Collections.emptyList();

        List<BossKillStats.AggregatedDrop> result = new ArrayList<>();
        for (LootStorageData.AggregatedDrop agg : bd.getAggregatedDrops().values())
        {
            BossKillStats.AggregatedDrop drop = new BossKillStats.AggregatedDrop(
                    agg.getItemId(),
                    agg.getItemName(),
                    agg.getTotalQuantity(),
                    agg.getTotalValue(),
                    agg.getDropCount(),
                    agg.getGePrice(),
                    agg.getHighAlch());
            drop.setPet(agg.isPet());

            // Use zero GE price + zero high-alch as an EDT-safe proxy for
            // "untradeable" — avoids calling ItemManager.getItemComposition()
            // which requires the client thread and throws AssertionError from
            // the EDT (issue with the previous implementation).
            boolean likelyUntradeable = !agg.isPet()
                    && agg.getGePrice() == 0
                    && agg.getHighAlch() == 0;
            drop.setUntradeable(likelyUntradeable);

            result.add(drop);
        }

        // Pets first, then likely-untradeables (zero GE + alch value), then
        // by total value descending.
        result.sort((a, b) ->
        {
            if (a.isPet()         != b.isPet())         return a.isPet()         ? -1 : 1;
            if (a.isUntradeable() != b.isUntradeable()) return a.isUntradeable() ? -1 : 1;
            return Long.compare(b.getTotalValue(), a.getTotalValue());
        });
        return result;
    }

    public long getStorageTotalValueForBoss(String npcName)
    {
        LootStorageData data = storageManager.getCurrentData();
        if (data == null) return 0L;
        LootStorageData.BossKillData bd = data.getBossKills().get(npcName);
        return bd != null ? bd.getTotalLootValue() : 0L;
    }

    public List<BossKillStats> getAllBossStats()
    {
        return new ArrayList<>(bossKillStats.values());
    }

    public void clearAllData()
    {
        bossKillStats.clear();
        hiddenDrops.clear();
        lastPlayerLootTime.clear();
        storageManager.clearData();
        if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
        notifyDataRefresh();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  RUNELITE LOOT TRACKER IMPORT
    // ═════════════════════════════════════════════════════════════════════════

    public java.io.File findRuneLiteLootFile(String username)
    {
        java.io.File base   = net.runelite.client.RuneLite.RUNELITE_DIR;
        String       uLower = username.toLowerCase().replace(" ", "_");

        java.io.File profiles2 = new java.io.File(base, "profiles2");
        if (profiles2.isDirectory())
        {
            java.io.File extracted = extractLootFromProfiles2(profiles2, username);
            if (extracted != null) return extracted;
        }

        java.io.File ltDir = new java.io.File(base, "loottracker");
        if (ltDir.isDirectory())
        {
            for (String c : new String[]{
                    username + ".json", uLower + ".json",
                    username.replace(" ", "_") + ".json"})
            {
                java.io.File f = new java.io.File(ltDir, c);
                if (f.isFile()) return f;
            }
            java.io.File[] sub = ltDir.listFiles(f -> f.getName().endsWith(".json"));
            if (sub != null)
                for (java.io.File f : sub)
                    if (f.getName().toLowerCase().replace(" ", "_").contains(uLower))
                        return f;
        }

        java.io.File rootJson = new java.io.File(base, "loottracker.json");
        if (rootJson.isFile()) return rootJson;

        java.io.File[] rootFiles = base.listFiles(f ->
                f.isFile() && f.getName().endsWith(".json")
                        && f.getName().toLowerCase().contains("loot")
                        && !f.getName().toLowerCase().contains("runealytics"));
        if (rootFiles != null && rootFiles.length > 0) return rootFiles[0];

        return null;
    }

    private java.io.File extractLootFromProfiles2(java.io.File profiles2, String username)
    {
        java.io.File[] propFiles = profiles2.listFiles(f ->
                f.isFile() && f.getName().endsWith(".properties"));

        if (propFiles == null || propFiles.length == 0) return null;

        java.util.Arrays.sort(propFiles,
                (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        com.google.gson.JsonArray allRecords = new com.google.gson.JsonArray();

        for (java.io.File propFile : propFiles)
        {
            try
            {
                java.util.Properties props = new java.util.Properties();
                try (java.io.InputStreamReader isr = new java.io.InputStreamReader(
                        new java.io.FileInputStream(propFile),
                        java.nio.charset.StandardCharsets.UTF_8))
                {
                    props.load(isr);
                }

                int before = allRecords.size();

                for (String key : props.stringPropertyNames())
                {
                    if (!key.startsWith("loottracker.rsprofile.")) continue;
                    if (!key.contains(".drops_"))               continue;

                    String val = props.getProperty(key);
                    if (val == null || val.isEmpty()) continue;

                    try
                    {
                        com.google.gson.JsonObject lootData =
                                new com.google.gson.JsonParser().parse(val).getAsJsonObject();

                        String name  = lootData.has("name")  ? lootData.get("name").getAsString()  : "Unknown";
                        String type  = lootData.has("type")  ? lootData.get("type").getAsString()  : "NPC";
                        int    kills = lootData.has("kills") ? lootData.get("kills").getAsInt()    : 0;

                        if (!lootData.has("drops") || !lootData.get("drops").isJsonArray()) continue;

                        com.google.gson.JsonArray flatDrops = lootData.getAsJsonArray("drops");
                        com.google.gson.JsonArray dropObjs  = new com.google.gson.JsonArray();
                        for (int i = 0; i + 1 < flatDrops.size(); i += 2)
                        {
                            int itemId = flatDrops.get(i).getAsInt();
                            int qty    = flatDrops.get(i + 1).getAsInt();
                            if (itemId <= 0 || qty <= 0) continue;

                            com.google.gson.JsonObject dropObj = new com.google.gson.JsonObject();
                            dropObj.addProperty("id",  itemId);
                            dropObj.addProperty("qty", qty);
                            dropObjs.add(dropObj);
                        }

                        if (dropObjs.size() == 0) continue;

                        com.google.gson.JsonObject record = new com.google.gson.JsonObject();
                        record.addProperty("name",      name);
                        record.addProperty("killCount", kills);
                        record.addProperty("type",      type);
                        record.add("drops", dropObjs);
                        allRecords.add(record);
                    }
                    catch (Exception e)
                    {
                        log.debug("profiles2: failed to parse entry {} – {}", key, e.getMessage());
                    }
                }

                log.info("profiles2: {} → {} loot entries", propFile.getName(),
                        allRecords.size() - before);
            }
            catch (Exception e)
            {
                log.debug("profiles2: skipping {} – {}", propFile.getName(), e.getMessage());
            }
        }

        if (allRecords.size() == 0)
        {
            log.info("profiles2: no loot tracker entries found in any .properties file");
            return null;
        }

        try
        {
            java.io.File tmp = java.io.File.createTempFile("runealytics-rl-import-", ".json");
            tmp.deleteOnExit();
            try (java.io.FileWriter fw = new java.io.FileWriter(tmp,
                    java.nio.charset.StandardCharsets.UTF_8))
            {
                new com.google.gson.GsonBuilder().setPrettyPrinting()
                        .create().toJson(allRecords, fw);
            }
            return tmp;
        }
        catch (Exception e)
        {
            log.error("Failed to write profiles2 loot temp file", e);
            return null;
        }
    }

    public String importFromRuneLiteLootTracker(String username)
    {
        return importFromRuneLiteLootTracker(username, null);
    }

    public String importFromRuneLiteLootTracker(String username, java.io.File manualFile)
    {
        java.io.File dataFile = (manualFile != null) ? manualFile : findRuneLiteLootFile(username);

        if (dataFile == null)
            return "__CHOOSE_FILE__:" + net.runelite.client.RuneLite.RUNELITE_DIR.getAbsolutePath();

        log.info("Importing RuneLite loot data from: {}", dataFile.getAbsolutePath());

        try
        {
            String json = new String(java.nio.file.Files.readAllBytes(dataFile.toPath()));
            com.google.gson.JsonArray records = new com.google.gson.JsonParser().parse(json).getAsJsonArray();

            LootStorageData current = storageManager.getCurrentData();
            if (current == null) current = storageManager.loadData();

            Map<String, Set<Integer>> existingKCsByBoss = new HashMap<>();
            for (Map.Entry<String, LootStorageData.BossKillData> entry : current.getBossKills().entrySet())
            {
                Set<Integer> kcs = new HashSet<>();
                if (entry.getValue().getKills() != null)
                    for (LootStorageData.KillRecord kr : entry.getValue().getKills())
                        kcs.add(kr.getKillNumber());
                existingKCsByBoss.put(normalizeBossName(entry.getKey()), kcs);
            }

            int importedKills = 0, skippedDupes = 0, skippedNoDrops = 0, importedBosses = 0;

            for (int i = 0; i < records.size(); i++)
            {
                com.google.gson.JsonObject rec = records.get(i).getAsJsonObject();

                String rawName  = rec.has("name") ? rec.get("name").getAsString() : "Unknown";
                String bossName = normalizeBossName(rawName);

                int recKC = 0;
                if (rec.has("killCount")) recKC = rec.get("killCount").getAsInt();
                else if (rec.has("kills")) recKC = rec.get("kills").getAsInt();

                Set<Integer> existingKCs = existingKCsByBoss.getOrDefault(bossName, Collections.emptySet());
                if (recKC > 0 && existingKCs.contains(recKC)) { skippedDupes++; continue; }

                if (!rec.has("drops") || !rec.get("drops").isJsonArray()) { skippedNoDrops++; continue; }

                com.google.gson.JsonArray dropsArr = rec.getAsJsonArray("drops");
                List<LootStorageData.DropRecord> drops = new ArrayList<>();

                for (int d = 0; d < dropsArr.size(); d++)
                {
                    com.google.gson.JsonObject dropObj = dropsArr.get(d).getAsJsonObject();
                    int itemId = dropObj.has("id") ? dropObj.get("id").getAsInt() : 0;
                    int qty    = dropObj.has("qty") ? dropObj.get("qty").getAsInt() : 1;
                    if (itemId <= 0) continue;

                    // Create the new DropRecord format
                    LootStorageData.DropRecord dr = new LootStorageData.DropRecord();
                    dr.setItemId(itemId);
                    dr.setItemName(""); // Names are filled during aggregation/display usually
                    dr.setQuantity(qty);
                    dr.setGePrice(0);
                    dr.setHighAlch(0);
                    dr.setTotalValue(0);
                    dr.setHidden(false);
                    drops.add(dr);
                }

                if (drops.isEmpty()) { skippedNoDrops++; continue; }

                int npcId = BOSS_NAME_TO_ID.getOrDefault(bossName, 0);
                boolean isNewBoss = !existingKCsByBoss.containsKey(bossName);

                BossKillStats stats = bossKillStats.computeIfAbsent(
                        bossName, k -> new BossKillStats(bossName, npcId));

                if (recKC > 0 && recKC > stats.getKillCount())
                    stats.setKillCount(recKC - 1);

                int killNumber = stats.getKillCount() + 1;

                LootStorageData.KillRecord killRecord = new LootStorageData.KillRecord();
                killRecord.setKillNumber(killNumber);
                killRecord.setTimestamp(System.currentTimeMillis());
                killRecord.setWorld(0);
                killRecord.setCombatLevel(0);
                killRecord.setDrops(drops); // We can just pass the list directly
                killRecord.setSyncedToServer(false); // Mark for bulk sync later
                killRecord.setGameMode(state.getCurrentGameMode());
                killRecord.setAccountType(state.getCurrentAccountSubtype());

                // This now matches the BossKillStats.addKill(KillRecord) signature
                stats.addKill(killRecord);

                storageManager.addKill(bossName, npcId, 0, killNumber, 0, 0, drops);
                existingKCsByBoss.computeIfAbsent(bossName, k -> new HashSet<>()).add(killNumber);

                importedKills++;
                if (isNewBoss) importedBosses++;
            }

            if (importedKills > 0)
            {
                storageManager.saveData();
                SwingUtilities.invokeLater(() -> { if (panel != null) panel.refreshDisplay(); });
            }

            return String.format(
                    "Import complete!\n\n"
                            + "Kills imported  : %d\n"
                            + "New bosses      : %d\n"
                            + "Skipped (dupes) : %d already tracked\n"
                            + "Skipped (empty) : %d had no drop data\n\n"
                            + "Source: %s",
                    importedKills, importedBosses, skippedDupes, skippedNoDrops,
                    dataFile.getName());
        }
        catch (Exception e)
        {
            log.error("Failed to import RuneLite loot data", e);
            return "Import failed: " + e.getMessage();
        }
    }

    public void clearBossData(String npcName)
    {
        bossKillStats.remove(npcName);
        hiddenDrops.remove(npcName);
        lastPlayerLootTime.remove(npcName);
        storageManager.saveData();
        if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
    }

    public void prestigeBoss(String npcName)
    {
        BossKillStats stats = bossKillStats.get(npcName);
        if (stats != null)
        {
            // 1. Update the local UI object
            stats.prestige();

            // 2. Update the persistent storage data
            LootStorageData data = storageManager.getCurrentData();
            if (data != null && data.getBossKills().containsKey(npcName))
            {
                LootStorageData.BossKillData bossData = data.getBossKills().get(npcName);
                bossData.setPrestige(stats.getPrestige());
                bossData.getKills().clear(); // Clear the history in storage as well
                storageManager.saveData();
            }

            if (panel != null)
            {
                SwingUtilities.invokeLater(() -> panel.refreshDisplay());
            }
        }
    }

    public boolean isDropHidden(String npcName, int itemId)
    {
        Set<Integer> h = hiddenDrops.get(npcName);
        return h != null && h.contains(itemId);
    }

    public void hideDropForNpc(String npcName, int itemId)
    {
        hiddenDrops.computeIfAbsent(npcName, k -> new HashSet<>()).add(itemId);
        persistHiddenDrops();
    }

    public void unhideDropForNpc(String npcName, int itemId)
    {
        Set<Integer> h = hiddenDrops.get(npcName);
        if (h != null)
        {
            h.remove(itemId);
            if (h.isEmpty()) hiddenDrops.remove(npcName);
            persistHiddenDrops();
        }
    }

    /**
     * Pushes the in-memory hidden-drops map down to {@link LootStorageData} so
     * it survives a restart.  Independent of RuneLite's own ignore feature
     * (issue #6 — users wanted a RuneAlytics-specific list, right-click driven).
     */
    private void persistHiddenDrops()
    {
        LootStorageData data = storageManager.getCurrentData();
        if (data == null) data = storageManager.loadData();
        if (data == null) return;
        // Defensive copy so future mutations don't accidentally surface to disk.
        Map<String, Set<Integer>> snapshot = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> e : hiddenDrops.entrySet())
            snapshot.put(e.getKey(), new HashSet<>(e.getValue()));
        data.setHiddenDropsByBoss(snapshot);
        storageManager.saveData();
    }

    /**
     * Restores hidden-drops from persisted storage. Called by
     * {@link #loadFromStorage}.
     */
    private void rehydrateHiddenDrops()
    {
        LootStorageData data = storageManager.getCurrentData();
        if (data == null) return;
        Map<String, Set<Integer>> saved = data.getHiddenDropsByBoss();
        if (saved == null) return;
        hiddenDrops.clear();
        for (Map.Entry<String, Set<Integer>> e : saved.entrySet())
            hiddenDrops.put(e.getKey(), new HashSet<>(e.getValue()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY – BOSS DETECTION
    // ═════════════════════════════════════════════════════════════════════════

    public boolean isBoss(int npcId, String name)
    {
        return TRACKED_BOSS_IDS.contains(npcId) || matchesBossName(name);
    }

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
     *
     * <p>Pickpocket-prefixed names pass through unchanged so that stored
     * thieving entries survive across normalisation calls.</p>
     *
     * @param raw raw name from NPC, widget, or chat
     * @return canonical name, never null
     */
    public String normalizeBossName(String raw)
    {
        if (raw == null || raw.isEmpty()) return "Unknown";

        // Pass through already-prefixed pickpocket entries unchanged
        if (raw.startsWith(PICKPOCKET_PREFIX)) return raw;

        String l = raw.toLowerCase();

        if (l.contains("corrupted gauntlet"))             return "Corrupted Gauntlet";
        if (l.contains("gauntlet"))                       return "The Gauntlet";
        if (l.contains("chambers") || l.contains("cox"))  return "Chambers of Xeric";
        if (l.contains("theatre") || l.contains("tob"))   return "Theatre of Blood";
        if (l.contains("tombs") || l.contains("toa"))     return "Tombs of Amascut";

        if (l.contains("zilyana"))                        return "Commander Zilyana";
        if (l.contains("graardor"))                       return "General Graardor";
        if (l.contains("kree"))                           return "Kree'arra";
        if (l.contains("kril"))                           return "K'ril Tsutsaroth";
        if (l.equals("nex"))                              return "Nex";

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

        if (l.contains("duke") || l.contains("sucellus")) return "Duke Sucellus";
        if (l.contains("leviathan"))                      return "The Leviathan";
        if (l.contains("vardorvis"))                      return "Vardorvis";
        if (l.contains("whisperer"))                      return "The Whisperer";

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

        if (l.contains("barrows"))                        return "Barrows";
        if (l.contains("tempoross"))                      return "Tempoross";
        if (l.contains("wintertodt"))                     return "Wintertodt";
        if (l.contains("zalcano"))                        return "Zalcano";

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

    public String detectChestSource(String lower)
    {
        if (lower.contains("wintertodt") || lower.contains("cold of the wintertodt"))
            return "Wintertodt";

        if (lower.contains("subdued the spirit") || lower.contains("you have helped to subdue"))
            return "Tempoross";

        if (lower.contains("zalcano") && (lower.contains("loot") || lower.contains("defeated")))
            return "Zalcano";

        if (lower.contains("congratulations - your raid is complete")
                || lower.contains("congratulations! your raid is complete"))
            return "Chambers of Xeric";

        if (lower.contains("theatre of blood") && lower.contains("complete"))
            return "Theatre of Blood";

        if (lower.contains("tombs of amascut") && lower.contains("complete"))
            return "Tombs of Amascut";

        if (lower.contains("gauntlet") && lower.contains("complete"))
            return lower.contains("corrupted") ? "Corrupted Gauntlet" : "The Gauntlet";

        if (lower.contains("phosani") && lower.contains("defeated"))
            return "Phosani's Nightmare";
        if (lower.contains("nightmare") && lower.contains("defeated"))
            return "The Nightmare";

        if (lower.contains("royal titans") || lower.contains("eldric the ice king")
                || lower.contains("branda the fire queen"))
            return "Royal Titans";

        if (lower.contains("colosseum") || lower.contains("fortis"))
            return "Fortis Colosseum";

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

        return null;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY – DROP RECORD CONVERSION
    // ═════════════════════════════════════════════════════════════════════════

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

    public void addListener(LootTrackerUpdateListener listener)
    {
        listeners.add(listener);
    }

    private void notifyListeners(BossKillStats stats, LootStorageData.KillRecord kill)
    {
        for (LootTrackerUpdateListener l : listeners)
        {
            l.onLootUpdated(stats, kill);
        }
    }

    private void notifyDataRefresh()
    {
        for (LootTrackerUpdateListener l : listeners) l.onDataRefresh();
    }
}