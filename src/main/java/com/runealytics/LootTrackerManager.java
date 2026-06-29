package com.runealytics;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
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
    //  Single source of truth lives in {@link RewardSources}. The
    //  WIDGET_WHISPERER alias is exposed here because RuneAlyticsPlugin
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
    private final ConfigManager            configManager;
    private final ScheduledExecutorService executorService;

    // ═════════════════════════════════════════════════════════════════════════
    //  MUTABLE STATE
    // ═════════════════════════════════════════════════════════════════════════

    /** Reference to the UI panel; may be null before first login. */
    private LootTrackerPanel panel;

    /**
     * Normalized username whose loot is currently loaded into memory + the
     * panel, or {@code null} when nothing is loaded (startup / after logout).
     * Tracked instead of a plain boolean so an account switch forces a reload
     * rather than leaving the previous account's loot on screen.
     */
    private String loadedAccount = null;

    /**
     * Gate: server sync is only permitted during a manual sync operation.
     * Prevents background auto-sync on login/startup.
     */
    private boolean allowSync = false;

    /** In-memory boss stats, keyed by normalised NPC name. */
    private final Map<String, BossKillStats> bossKillStats = new ConcurrentHashMap<>();

    /**
     * Listeners notified on every kill or data refresh. CopyOnWriteArrayList
     * because {@code addListener} runs on the EDT (panel construction) while
     * {@code notifyListeners}/{@code notifyDataRefresh} iterate from the client
     * thread, so registration and iteration can race.
     */
    private final List<LootTrackerUpdateListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Per-boss hidden item sets.
     * Key = normalised NPC name; value = set of hidden itemIds.
     *
     * <p>Concurrent because it is read while building the display (refresh
     * executor thread, via {@link #isDropHidden}) and mutated from the EDT
     * (right-click hide/unhide). The value sets are also concurrent so iteration
     * in {@link #persistHiddenDrops} can't throw {@link java.util.ConcurrentModificationException}.</p>
     */
    private final Map<String, Set<Integer>> hiddenDrops = new ConcurrentHashMap<>();

    /**
     * Boss containers hidden from the panel entirely. Mirrors {@link #hiddenDrops}
     * but at the boss-card level. Display-only — kills/drops for a hidden boss
     * are still recorded and synced, only client rendering is suppressed.
     */
    private final Set<String> hiddenBosses = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Dedup map for player / chest loot sources only.
     * Key = normalised source name; value = last time (ms) we processed loot from it.
     * NOT used for NpcLootReceived or pickpocket paths.
     */
    private final Map<String, Long> lastPlayerLootTime = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    //  LIVE-SYNC DEBOUNCE
    //
    //  After every kill a debounced bulk-sync is started so drops appear on the
    //  website within a few seconds. At most one upload is in flight at a time;
    //  rapid consecutive kills coalesce into one HTTP call.
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
            ConfigManager            configManager,
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
        this.configManager   = configManager;
        this.executorService = executorService;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    /** Called during plugin shutDown(). Persists in-memory data and stops the save executor. */
    public void shutdown()
    {
        log.debug("LootTrackerManager: saving on shutdown");
        storageManager.shutdown();
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

        log.debug("NPC loot: '{}' id={} cb={} items={}",
                npc.getName(), npc.getId(), npc.getCombatLevel(), items.size());

        String name = normalizeBossName(npc.getName());
        boolean isBoss = isBoss(npc.getId(), name);

        if (!isBoss && !config.trackAllNpcs())
        {
            log.debug("Filtered NPC (not a tracked boss): '{}' id={} "
                            + "→ enable 'Track All NPCs' or add id to TRACKED_BOSS_IDS",
                    name, npc.getId());
            return;
        }

        // Record the kill even if every drop was filtered out by
        // minimumLootValue, so the kill counter stays accurate. Zero-drop kills
        // stay local-only; the bulk-sync path skips them from server upload.
        List<LootStorageData.DropRecord> drops = convertToDropRecords(items);
        recordKill(name, npc.getId(), npc.getCombatLevel(), client.getWorld(), drops);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 1b – ZERO-LOOT NPC KILLS
    //
    //  RuneLite's NpcLootReceived event only fires when the kill produced at
    //  least one item. Many NPCs (low-level mobs, some bosses on a dry kill)
    //  die without dropping anything. RuneAlyticsPlugin watches ActorDeath +
    //  HitsplatApplied to detect a loot-independent kill and routes those kills
    //  here so the per-NPC kill count stays accurate.
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

        log.debug("Zero-loot kill: '{}' id={} cb={}", name, npcId, npc.getCombatLevel());
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
        log.debug("Player loot: '{}' (id={}) items={}", name, npcId, items.size());

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

        log.debug("Pickpocket: '{}' → '{}' ({} items)", rawNpcName, storedKey, drops.size());

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
        log.debug("Skilling: '{}' — {} items", skill, drops.size());
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
    //  Catching an imp gives an impling jar (recorded as a Hunter skilling
    //  drop). The loot only materialises when the player loots the jar, which
    //  yields no XP, so the generic skilling diff misses it. A dedicated entry
    //  point handles the inventory diff after a "Loot-jar" / "Loot" click on an
    //  "* impling jar" item. Each impling tier is tracked separately (e.g.
    //  "Impling: Eclectic").
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
        log.debug("Impling jar loot: '{}' tier='{}' ({} items)", jarItemName, tier, drops.size());
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

        log.debug("appendDropsToLastKill: {} drop(s) added to '{}' last kill (+{} gp)",
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

        log.debug("[Pet] Recorded '{}' pet drop for '{}'", drop.getItemName(), npcName);
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

            log.debug("Container read '{}': {} items from container {}",
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

            log.debug("Widget loot '{}' (group {}): {} items", sourceName, groupId, items.size());
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

            log.debug("Clue reward '{}': {} items", sourceName, items.size());
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
        log.debug("Inventory diff '{}': {} new items", sourceName, newItems.size());
        processPlayerLoot(sourceName, newItems);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 5 – GROUND ITEM SPAWN  (ItemSpawned fallback / supplement)
    //
    //  This path serves two purposes:
    //    1. SUPPLEMENT: NpcLootReceived already fired for this NPC with the
    //       same drops. No second kill is recorded; the items are deduped
    //       against the last kill's drops and only the extra items are appended
    //       (e.g. coins added by RoW that bypassed the primary event).
    //    2. FALLBACK: NpcLootReceived did not fire (new content RuneLite hasn't
    //       catalogued yet). The ground items are recorded as a fresh kill.
    // ═════════════════════════════════════════════════════════════════════════

    public void processGroundItemBatch(NPC npc, List<ItemStack> items)
    {
        if (npc == null || items == null || items.isEmpty()) return;

        String name = normalizeBossName(npc.getName());
        BossKillStats stats = bossKillStats.get(name);

        // No prior kill for this NPC → genuine FALLBACK path
        if (stats == null || stats.getKillHistory().isEmpty())
        {
            log.debug("Ground items from '{}' (id={}): {} items — no prior kill, treating as fresh",
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
            log.debug("Ground items from '{}': last kill {}ms ago — treating as fresh",
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

        log.debug("Ground items for '{}': appending {} extra item type(s) to last kill",
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
        log.debug("Player loot (gameKC={}): '{}' (id={}) items={}", gameKC, name, npcId, items.size());

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
     * The single write path for all loot sources. When {@code gameKC} is
     * positive, the local {@link BossKillStats} counter is synced to
     * {@code gameKC - 1} before the kill is added. Does not sync to the server;
     * unsynced kills are uploaded in batches by {@link #uploadUnsyncedKills()}.
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

        // 3. Snapshot the player's location at kill time. Loot events fire on
        //    the client thread, so reading the live client state here is safe.
        //    captureRespectingPrivacy substitutes the Grand Exchange decoy when
        //    visibility is private — a private player's real coordinates must
        //    never be written into a kill record that later gets synced.
        PlayerLocationSnapshot location =
                PlayerLocationSnapshot.captureRespectingPrivacy(client, config.playerVisibility());

        // 4. Create the storage-compatible record
        LootStorageData.KillRecord killRecord = new LootStorageData.KillRecord();
        killRecord.setTimestamp(System.currentTimeMillis());
        killRecord.setKillNumber(killNumber);
        killRecord.setWorld(world);
        killRecord.setCombatLevel(combatLevel);
        killRecord.setDrops(drops);
        killRecord.setSyncedToServer(false); // picked up by the next batch
        killRecord.setGameMode(state.getCurrentGameMode());
        killRecord.setAccountType(state.getCurrentAccountSubtype());
        killRecord.setLocation(location);

        // 5. Update the in-memory UI stats and persistent storage. When a kill
        //    carries an authoritative game kill count (e.g. the Whisperer KC
        //    chat message), seed the in-memory counter to gameKC - 1 so
        //    addKill() lands it exactly on gameKC. Only raise the counter so a
        //    stale/low gameKC can't regress it.
        if (gameKC > 0 && gameKC > stats.getKillCount())
        {
            stats.setKillCount(gameKC - 1);
        }
        stats.addKill(killRecord);
        storageManager.addKill(
                npcName, npcId, combatLevel, killNumber,
                world, state.getPrestige(), drops, location);

        notifyListeners(stats, killRecord);

        // Kick off a debounced live sync so the website updates within a few
        // seconds.  Multiple kills inside the debounce window batch into one
        // HTTP call (see LIVE_SYNC_DEBOUNCE_MS).
        scheduleLiveSync();

        log.debug("Kill recorded: '{}' #{} (gameKC={}) – {} drops, {} gp",
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
        if (username == null) return;
        if (!state.tryStartSync()) return;

        try            { downloadHistoryBlocking(username); }
        finally        { state.endSync(); }
    }

    /**
     * Blocking, slot-free download of server kill history for {@code username}
     * into local storage. The caller is responsible for holding the sync slot
     * (so this never races a concurrent upload) and for scoping
     * {@code username} to the currently logged-in account.
     */
    void downloadHistoryBlocking(String username)
    {
        if (username == null || username.isEmpty()) return;
        try
        {
            Map<String, LootStorageData.BossKillData> serverData =
                    apiClient.fetchKillHistoryFromServer(username);

            if (serverData != null && !serverData.isEmpty())
            {
                storageManager.mergeServerData(serverData);
                refreshLootDisplay();
                log.debug("Merged {} bosses from server for {}", serverData.size(), username);
            }
        }
        catch (Exception e) { log.error("Failed to download kill history", e); }
    }

    public void uploadUnsyncedKills()
    {
        String username = state.getVerifiedUsername();
        if (username == null) return;

        // Atomically claim the sync slot so the scheduled task and a live-sync
        // can't both upload and double-count on the server.
        if (!state.tryStartSync()) return;

        // Run off the client thread on the shared executor so rapid kills can't
        // spawn unbounded threads.
        executorService.execute(() -> {
            try     { uploadUnsyncedKillsBlocking(username); }
            finally { state.endSync(); }
        });
    }

    /**
     * Blocking, slot-free batch upload of all unsynced kills for
     * {@code username}. The caller MUST already hold the sync slot (via
     * {@link RuneAlyticsState#tryStartSync()}) so this never double-uploads
     * alongside a live sync. Runs inline on the calling (background) thread.
     */
    void uploadUnsyncedKillsBlocking(String username)
    {
        if (username == null || username.isEmpty()) return;
        try
        {
            Map<String, List<LootStorageData.KillRecord>> unsynced =
                    storageManager.getAllUnsyncedKills();

            if (unsynced.isEmpty()) return;

            int total = unsynced.values().stream().mapToInt(List::size).sum();
            log.debug("Uploading {} unsynced kills across {} bosses", total, unsynced.size());

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
            // Trigger a UI refresh if the panel is open
            if (panel != null)
            {
                SwingUtilities.invokeLater(() -> panel.refreshDisplay());
            }
        }
    }

    /**
     * Runs the legacy loot sync steps inline (pull history + cleanup + upload
     * kills), scoped to {@code username}. The caller MUST hold the sync slot.
     * When {@code pull} is {@code false} (e.g. a logout flush) the
     * download/cleanup steps are skipped and only the upload runs, keeping the
     * operation fast.
     *
     * <p>RuneLite's own Loot Tracker file is intentionally NOT imported here —
     * it is read directly (and freshly, every sync) by
     * {@link LootSyncMergeService}, never copied into this plugin's local
     * cache or any temp file.</p>
     */
    public void syncLegacyBlocking(String username, boolean pull)
    {
        if (username == null || username.isEmpty()) return;

        if (pull)
        {
            downloadHistoryBlocking(username);
            cleanupZeroValueDrops();
        }
        uploadUnsyncedKillsBlocking(username);
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
                // Pull the website's data down first so it's the baseline
                // the RuneLite-import delta diff compares against — otherwise
                // the diff sees an incomplete local state and either misses
                // the website's contribution or double-counts once the
                // website data is merged in afterwards.
                downloadKillHistoryFromServer();
                importFromRuneLiteLootTrackerSilently(username);
                cleanupZeroValueDrops();
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

    /**
     * Best-effort pull from RuneLite's own Loot Tracker plugin as part of
     * Sync, so the player doesn't have to separately remember to hit the
     * "Import from RuneLite Loot Tracker" button. Silent and non-fatal: if no
     * file is found (or it can't be resolved automatically) this just skips —
     * the user can still use the manual import button, which prompts to pick
     * a file.
     */
    private void importFromRuneLiteLootTrackerSilently(String username)
    {
        try
        {
            java.io.File dataFile = findRuneLiteLootFile(username);
            if (dataFile == null)
            {
                log.debug("Sync: no RuneLite Loot Tracker file found for {} — skipping", username);
                return;
            }

            String result = importFromRuneLiteLootTracker(username, dataFile);
            log.debug("Sync: RuneLite Loot Tracker import result — {}", result);
        }
        catch (Exception e)
        {
            log.warn("Sync: RuneLite Loot Tracker import failed", e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════

    public void loadFromStorage()
    {
        String username = state.getVerifiedUsername();
        String norm = (username == null || username.isEmpty()) ? null : username.toLowerCase();

        // Already showing this exact account's data — nothing to do (guards the
        // repeated local-player spawns within one session).
        if (norm != null && norm.equals(loadedAccount))
        {
            log.debug("Loot data already loaded for '{}'", norm);
            return;
        }

        // First load this session, or a different account just logged in: drop
        // any cached copy belonging to the previous account before reloading so
        // we never display or sync one account's loot under another.
        log.debug("Loading local loot data for '{}' (was '{}')", norm, loadedAccount);
        storageManager.dropCache();
        refreshLootDisplay();
        loadedAccount = norm;
    }

    /**
     * Clears the in-memory + on-screen loot when the player logs out, so the
     * panel resets to empty and the next login reloads that account's own data.
     *
     * <p>The current account's data is flushed to disk first (while the verified
     * username still points at it), then the cache is dropped. Safe to call from
     * the client thread.</p>
     */
    public void resetForLogout()
    {
        storageManager.flushNow();   // persist the account we're leaving
        storageManager.dropCache();  // next login reloads the correct file

        bossKillStats.clear();
        hiddenDrops.clear();
        hiddenBosses.clear();
        loadedAccount = null;

        if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
        log.debug("Loot tracker reset for logout");
    }

    /**
     * Rebuilds the in-memory {@code bossKillStats} display cache from whatever
     * is currently in {@link LootStorageManager}, purging empty placeholder
     * entries in the process.
     *
     * <p>Must be called after anything writes to {@link LootStorageData}
     * outside of this manager's own kill-recording methods — e.g. after
     * {@link LootSyncMergeService} applies a merge directly to storage —
     * otherwise the panel keeps rendering a stale snapshot until the next
     * login.</p>
     */
    public void refreshFromStorage()
    {
        refreshLootDisplay();
    }

    private void refreshLootDisplay()
    {
        LootStorageData data = storageManager.getCurrentData();

        // Always restore the persisted RuneAlytics-specific ignore list.
        rehydrateHiddenDrops();
        rehydrateHiddenBosses();

        if (data == null || data.getBossKills().isEmpty())
        {
            bossKillStats.clear();
            if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
            return;
        }

        // One client-thread hop for the whole dataset; a per-kill hop is too
        // slow against a large history.
        boolean backfilled = backfillAllMissingDropValues(data);

        bossKillStats.clear();

        List<String> emptyPlaceholderKeys = new ArrayList<>();
        boolean purgedPlaceholders = false;

        for (Map.Entry<String, LootStorageData.BossKillData> entry : data.getBossKills().entrySet())
        {
            LootStorageData.BossKillData bd = entry.getValue();

            // Skip placeholder entries: 0 kill count and no recorded drops.
            // These can show up as empty rows on the panel (e.g. a source the
            // merge saw on the website/RuneLite side with no actual loot).
            // Note: a non-empty kills list with no actual kill count/drops in
            // it doesn't count as "real" data, so this checks effective totals
            // rather than just list emptiness.
            boolean hasDrops = bd.getAggregatedDrops() != null
                    && bd.getAggregatedDrops().values().stream()
                            .anyMatch(d -> d.getTotalQuantity() > 0);
            int effectiveKillCount = Math.max(bd.getKillCount(),
                    bd.getKills() != null ? bd.getKills().size() : 0);
            if (effectiveKillCount <= 0 && !hasDrops)
            {
                emptyPlaceholderKeys.add(entry.getKey());
                continue;
            }

            if (effectiveKillCount > 0 && !hasDrops)
            {
                log.warn("[Loot] '{}' has {} kill(s) but no recorded drops — "
                                + "kills.size={}, aggregatedDrops.size={} (underlying data has no items "
                                + "for this source; nothing to display)",
                        entry.getKey(), effectiveKillCount,
                        bd.getKills() != null ? bd.getKills().size() : 0,
                        bd.getAggregatedDrops() != null ? bd.getAggregatedDrops().size() : 0);
            }

            BossKillStats stats = new BossKillStats(bd.getNpcName(), bd.getNpcId());
            stats.setPrestige(bd.getPrestige());

            if (bd.getKills() != null && !bd.getKills().isEmpty())
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

            // Sources synced purely via the website/RuneLite-tracker merge
            // have aggregated drop totals but no per-kill records — without
            // this, BossKillStats.getAggregatedDrops() (which sums killHistory)
            // would come back empty and the panel would show "No drops
            // recorded yet" even though real totals exist.
            boolean usingPreloadedDrops = hasDrops
                    && (bd.getKills() == null || bd.getKills().isEmpty());
            if (hasDrops)
            {
                List<BossKillStats.AggregatedDrop> preloaded = new ArrayList<>();
                long preloadedValue = 0;
                for (LootStorageData.AggregatedDrop agg : bd.getAggregatedDrops().values())
                {
                    if (agg.getTotalQuantity() <= 0) continue;
                    BossKillStats.AggregatedDrop pd = new BossKillStats.AggregatedDrop(
                            agg.getItemId(), agg.getItemName(),
                            agg.getTotalQuantity(), agg.getTotalValue(), agg.getDropCount(),
                            agg.getGePrice(), agg.getHighAlch());
                    pd.setPet(agg.isPet());
                    preloaded.add(pd);
                    preloadedValue += agg.getTotalValue();
                }
                stats.setPreloadedDrops(preloaded);
                if (usingPreloadedDrops) stats.setTotalLootValue(preloadedValue);
            }

            // Drop objects are shared with bd.getKills(), so the backfill pass
            // above already mutated the persisted records in place — just
            // bring the persisted total in line with what was recomputed.
            bd.setTotalLootValue(stats.getTotalLootValue());

            bossKillStats.put(stats.getNpcName(), stats);
        }

        // Purge empty placeholder entries (0 kill count, no drops) directly
        // from persisted storage so they don't keep re-appearing on every
        // refresh or get re-uploaded on the next sync.
        if (!emptyPlaceholderKeys.isEmpty())
        {
            for (String key : emptyPlaceholderKeys) data.getBossKills().remove(key);
            purgedPlaceholders = true;
            log.debug("[Loot] Purged {} empty placeholder boss entry(ies) from storage: {}",
                    emptyPlaceholderKeys.size(), emptyPlaceholderKeys);
        }

        // Persist once, outside the loop, if any drop's value was backfilled
        // or empty placeholder entries were purged.
        if (backfilled || purgedPlaceholders)
        {
            log.debug("[Loot] Backfilled missing GE/alch values and/or purged placeholders — saving");
            storageManager.scheduleSave();
        }

        log.debug("refreshLootDisplay: {} bosses loaded", bossKillStats.size());
        if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
    }

    /**
     * Re-resolves GE price / high alch / total value for every drop stored as 0.
     * Mutates the drop records in place.
     *
     * <p>Runs the whole scan inside a single {@link ClientThread#invoke} call;
     * ItemManager's composition/price lookups must run on the client thread.</p>
     *
     * @return true if any drop's value was recomputed
     */
    private boolean backfillAllMissingDropValues(LootStorageData data)
    {
        boolean[] changed = { false };
        clientThread.invoke(() ->
        {
            for (Map.Entry<String, LootStorageData.BossKillData> entry : data.getBossKills().entrySet())
            {
                LootStorageData.BossKillData bd = entry.getValue();

                for (LootStorageData.KillRecord kr : bd.getKills() != null
                        ? bd.getKills() : Collections.<LootStorageData.KillRecord>emptyList())
                {
                    if (kr.getDrops() == null) continue;

                    // A backfill failure (e.g. an unresolvable legacy item id)
                    // must never block the rest of the scan.
                    try
                    {
                        for (LootStorageData.DropRecord drop : kr.getDrops())
                        {
                            if (drop.getItemId() <= 0 || drop.getGePrice() > 0) continue;

                            int gePrice = ItemValueResolver.perItemGeValue(itemManager, drop.getItemId());
                            if (gePrice <= 0) continue;

                            drop.setGePrice(gePrice);
                            if (drop.getHighAlch() <= 0)
                            {
                                ItemComposition comp = itemManager.getItemComposition(drop.getItemId());
                                if (comp != null) drop.setHighAlch(comp.getHaPrice());
                            }
                            if (drop.getItemName() == null || drop.getItemName().isEmpty())
                            {
                                ItemComposition comp = itemManager.getItemComposition(drop.getItemId());
                                if (comp != null) drop.setItemName(comp.getName());
                            }
                            drop.setTotalValue((long) gePrice * drop.getQuantity());
                            changed[0] = true;

                            // aggregatedDrops is a separate persisted snapshot
                            // (per-item rows the panel reads via
                            // getStorageDropsForBoss), seeded from the first
                            // drop and not updated by later corrections. Patch
                            // the matching aggregate entry so it matches the
                            // corrected drop.
                            LootStorageData.AggregatedDrop agg =
                                    bd.getAggregatedDrops() != null
                                            ? bd.getAggregatedDrops().get(drop.getItemId())
                                            : null;
                            if (agg != null)
                            {
                                if (agg.getGePrice() <= 0)  agg.setGePrice(drop.getGePrice());
                                if (agg.getHighAlch() <= 0) agg.setHighAlch(drop.getHighAlch());
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        log.warn("[Loot] Backfill failed for a drop in '{}': {}", entry.getKey(), ex.getMessage());
                    }
                }

                // Merge-only sources (e.g. RuneLite-tracker / website import)
                // have aggregated totals but no per-kill records, so the loop
                // above never touches them — resolve their price fields here
                // directly so they don't sit at 0gp forever.
                if (bd.getAggregatedDrops() == null) continue;
                for (LootStorageData.AggregatedDrop agg : bd.getAggregatedDrops().values())
                {
                    if (agg.getItemId() <= 0 || agg.getGePrice() > 0) continue;
                    try
                    {
                        int gePrice = ItemValueResolver.perItemGeValue(itemManager, agg.getItemId());
                        if (gePrice <= 0) continue;

                        agg.setGePrice(gePrice);
                        ItemComposition comp = itemManager.getItemComposition(agg.getItemId());
                        if (comp != null)
                        {
                            if (agg.getHighAlch() <= 0) agg.setHighAlch(comp.getHaPrice());
                            if (agg.getItemName() == null || agg.getItemName().isEmpty())
                                agg.setItemName(comp.getName());
                        }
                        agg.setTotalValue((long) gePrice * agg.getTotalQuantity());
                        changed[0] = true;
                    }
                    catch (Exception ex)
                    {
                        log.warn("[Loot] Backfill failed for aggregated drop in '{}': {}",
                                entry.getKey(), ex.getMessage());
                    }
                }
            }
        });
        return changed[0];
    }

    /**
     * Hides aggregated drops that are genuinely worth nothing (zero GE price
     * <em>and</em> zero high-alch, after a final re-resolve attempt) using the
     * existing per-item hide flag — never deletes anything from storage.
     *
     * <p>This intentionally never mutates {@code KillRecord}/{@code DropRecord}
     * history: {@link ClientThread#invoke} only blocks the caller while the
     * client thread is reachable, and on the very first tick after login (or
     * if the item/GE price cache hasn't populated yet) {@code ItemManager} can
     * legitimately return 0 for everything it's asked about. Treating that as
     * "confirmed worthless" and deleting the underlying drop records would
     * destroy real kill history on a cache miss, with no way to recover it
     * (storage is overwritten in place, no backup is kept). Hiding is fully
     * reversible from the panel and never touches {@code totalLootValue} or
     * kill history, so a bad resolve has zero blast radius.</p>
     */
    public void cleanupZeroValueDrops()
    {
        LootStorageData data = storageManager.getCurrentData();
        if (data == null || data.getBossKills().isEmpty()) return;

        for (String npcName : data.getBossKills().keySet())
        {
            for (BossKillStats.AggregatedDrop drop : getStorageDropsForBoss(npcName))
            {
                if (drop.isPet() || drop.getItemId() <= 0) continue;
                if (isDropHidden(npcName, drop.getItemId())) continue;
                if (drop.getGePrice() <= 0 && drop.getHighAlchValue() <= 0)
                {
                    hideDropForNpc(npcName, drop.getItemId());
                }
            }
        }
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
            // "untradeable"; ItemManager.getItemComposition() requires the
            // client thread and throws from the EDT.
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

        // After clearing, the plugin keeps no cache/temp file for this
        // account — the next sync reads only RuneLite's own Loot Tracker file
        // (live, scoped to this username) and pushes those totals to the
        // server to catch it up.
        if (panel != null) SwingUtilities.invokeLater(() -> panel.refreshDisplay());
        notifyDataRefresh();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  RUNELITE LOOT TRACKER IMPORT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Sums recorded quantity per item id across every kill already stored for
     * a boss, regardless of how it got there (live tracking, server merge, or
     * a prior RuneLite import). Used to diff against RuneLite's cumulative
     * loot-tracker totals so re-running the import never re-adds loot we
     * already have.
     */
    private Map<Integer, Integer> sumItemQuantities(LootStorageData data, String bossName)
    {
        Map<Integer, Integer> totals = new HashMap<>();
        LootStorageData.BossKillData bd = data.getBossKills().get(bossName);
        if (bd == null || bd.getKills() == null) return totals;

        for (LootStorageData.KillRecord kr : bd.getKills())
        {
            if (kr.getDrops() == null) continue;
            for (LootStorageData.DropRecord drop : kr.getDrops())
                totals.merge(drop.getItemId(), drop.getQuantity(), Integer::sum);
        }
        return totals;
    }

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

        // Scope to the requested account. RuneLite stores EVERY account's loot
        // in the same files, namespaced by an opaque rsprofile key; without this
        // filter we would import other players' loot that shares this PC.
        final String normalizedTarget =
                CurrentPlayerIdentityService.normalizeUsername(username);
        if (normalizedTarget == null || normalizedTarget.isEmpty())
        {
            log.debug("profiles2: no normalized account to scope import to — skipping");
            return null;
        }

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

                // Build the full rsprofile-key → account map for this file, then
                // pick the key(s) that belong to the target account. Loot under
                // any other key must be ignored.
                java.util.Map<String, String> keyToAccount = profileKeyDisplayNames(props);
                java.util.Set<String> matchingKeys = new java.util.HashSet<>();
                for (java.util.Map.Entry<String, String> e : keyToAccount.entrySet())
                {
                    if (normalizedTarget.equals(e.getValue())) matchingKeys.add(e.getKey());
                }

                if (matchingKeys.isEmpty())
                {
                    log.info("[rl-import] {} → no rsprofile key maps to account '{}' "
                            + "(known accounts: {}); importing nothing from this file",
                            propFile.getName(), normalizedTarget,
                            new java.util.HashSet<>(keyToAccount.values()));
                    continue;
                }

                int before = allRecords.size();
                int ignoredEntries = 0;
                java.util.Set<String> ignoredKeys = new java.util.HashSet<>();

                for (String key : props.stringPropertyNames())
                {
                    if (!key.startsWith("loottracker.rsprofile.")) continue;
                    int dropsIdx = key.indexOf(".drops_", "loottracker.rsprofile.".length());
                    if (dropsIdx < 0) continue;

                    String rsKey = key.substring("loottracker.rsprofile.".length(), dropsIdx);
                    if (!matchingKeys.contains(rsKey))
                    {
                        // Belongs to a different account on this PC — skip it.
                        ignoredEntries++;
                        ignoredKeys.add(rsKey);
                        continue;
                    }

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

                log.info("[rl-import] {} → account '{}' matched key(s) {}; "
                        + "imported {} loot source(s); ignored {} source(s) belonging to "
                        + "{} other account-key(s) {}",
                        propFile.getName(), normalizedTarget, matchingKeys,
                        allRecords.size() - before, ignoredEntries, ignoredKeys.size(), ignoredKeys);
            }
            catch (Exception e)
            {
                log.debug("profiles2: skipping {} – {}", propFile.getName(), e.getMessage());
            }
        }

        if (allRecords.size() == 0)
        {
            log.debug("profiles2: no loot tracker entries found in any .properties file");
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

    /**
     * Builds the {@code rsprofile-key → normalized account name} map from all
     * {@code rsprofile.rsprofile.<KEY>.displayName} properties in {@code props}.
     * Mirrors {@link DefaultRuneLiteLootTrackerReader} so the legacy per-kill
     * import is scoped to the same account as the absolute-merge reader and
     * never leaks other players' loot.
     */
    private java.util.Map<String, String> profileKeyDisplayNames(java.util.Properties props)
    {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (props == null) return map;

        final String prefix = "rsprofile.rsprofile.";
        final String suffix = ".displayName";

        for (String key : props.stringPropertyNames())
        {
            if (!key.startsWith(prefix) || !key.endsWith(suffix)) continue;

            String rsKey = key.substring(prefix.length(), key.length() - suffix.length());
            if (rsKey.isEmpty() || rsKey.contains(".")) continue; // guard nested keys

            String normalized =
                    CurrentPlayerIdentityService.normalizeUsername(props.getProperty(key));
            if (normalized != null) map.put(rsKey, normalized);
        }
        return map;
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

        log.debug("Importing RuneLite loot data from: {}", dataFile.getAbsolutePath());

        try
        {
            String json = new String(java.nio.file.Files.readAllBytes(dataFile.toPath()));
            com.google.gson.JsonArray records = new com.google.gson.JsonParser().parse(json).getAsJsonArray();

            LootStorageData currentLoaded = storageManager.getCurrentData();
            if (currentLoaded == null) currentLoaded = storageManager.loadData();
            final LootStorageData current = currentLoaded;

            Map<String, Set<Integer>> existingKCsByBoss = new HashMap<>();
            for (Map.Entry<String, LootStorageData.BossKillData> entry : current.getBossKills().entrySet())
            {
                Set<Integer> kcs = new HashSet<>();
                if (entry.getValue().getKills() != null)
                    for (LootStorageData.KillRecord kr : entry.getValue().getKills())
                        kcs.add(kr.getKillNumber());
                existingKCsByBoss.put(normalizeBossName(entry.getKey()), kcs);
            }

            // ItemManager composition/price lookups read the client's
            // item-definition cache and must run on the client thread. This
            // method is invoked off the client thread (panel executor), so the
            // whole record-build pass runs in a single client-thread hop,
            // blocking until it finishes.
            final int[] tally = new int[4]; // [0]=imported [1]=dupes [2]=noDrops [3]=bosses
            final String[] importError = { null };
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            clientThread.invoke(() ->
            {
            int importedKills = 0, skippedDupes = 0, skippedNoDrops = 0, importedBosses = 0;
            try
            {
            for (int i = 0; i < records.size(); i++)
            {
                com.google.gson.JsonObject rec = records.get(i).getAsJsonObject();

                String rawName  = rec.has("name") ? rec.get("name").getAsString() : "Unknown";
                String bossName = normalizeBossName(rawName);

                int recKC = 0;
                if (rec.has("killCount")) recKC = rec.get("killCount").getAsInt();
                else if (rec.has("kills")) recKC = rec.get("kills").getAsInt();

                if (!rec.has("drops") || !rec.get("drops").isJsonArray()) { skippedNoDrops++; continue; }

                // RuneLite's loot-tracker file stores *cumulative* totals per
                // item since it started tracking that boss — not a per-kill
                // breakdown. Sum the reported quantities per item first.
                Map<Integer, Integer> reportedQty = new HashMap<>();
                com.google.gson.JsonArray dropsArr = rec.getAsJsonArray("drops");
                for (int d = 0; d < dropsArr.size(); d++)
                {
                    com.google.gson.JsonObject dropObj = dropsArr.get(d).getAsJsonObject();
                    int itemId = dropObj.has("id") ? dropObj.get("id").getAsInt() : 0;
                    int qty    = dropObj.has("qty") ? dropObj.get("qty").getAsInt() : 1;
                    if (itemId <= 0 || qty <= 0) continue;
                    reportedQty.merge(itemId, qty, Integer::sum);
                }

                if (reportedQty.isEmpty()) { skippedNoDrops++; continue; }

                // Comparing against a single killCount/KC number is unreliable
                // (live tracking and prior imports may already hold some of
                // this loot under different kill numbers). Instead diff against
                // what we already have on a per-item basis and only bring in
                // the shortfall — this is what makes repeat imports safe to
                // run (e.g. every Sync) without re-adding loot we already have.
                Map<Integer, Integer> currentQty = sumItemQuantities(current, bossName);

                List<LootStorageData.DropRecord> drops = new ArrayList<>();
                for (Map.Entry<Integer, Integer> e : reportedQty.entrySet())
                {
                    int itemId   = e.getKey();
                    int delta    = e.getValue() - currentQty.getOrDefault(itemId, 0);
                    if (delta <= 0) continue;

                    // Imported records only carry id/qty — resolve name and
                    // value the same way a live drop does.
                    ItemComposition comp = itemManager.getItemComposition(itemId);
                    int  gePrice    = ItemValueResolver.perItemGeValue(itemManager, itemId);
                    long totalValue = (long) gePrice * delta;

                    LootStorageData.DropRecord dr = new LootStorageData.DropRecord();
                    dr.setItemId(itemId);
                    dr.setItemName(comp.getName());
                    dr.setQuantity(delta);
                    dr.setGePrice(gePrice);
                    dr.setHighAlch(comp.getHaPrice());
                    dr.setTotalValue(totalValue);
                    dr.setHidden(false);
                    drops.add(dr);
                }

                if (drops.isEmpty()) { skippedDupes++; continue; }

                int npcId = BOSS_NAME_TO_ID.getOrDefault(bossName, 0);
                boolean isNewBoss = !existingKCsByBoss.containsKey(bossName);

                BossKillStats stats = bossKillStats.computeIfAbsent(
                        bossName, k -> new BossKillStats(bossName, npcId));

                // Bump KC up to RuneLite's reported count when it's ahead (first
                // import of a boss); otherwise just append one synthetic
                // "backfill" kill so the kill number stays unique/monotonic.
                int killNumber = Math.max(stats.getKillCount() + 1, recKC);

                LootStorageData.KillRecord killRecord = new LootStorageData.KillRecord();
                killRecord.setKillNumber(killNumber);
                killRecord.setTimestamp(System.currentTimeMillis());
                killRecord.setWorld(0);
                killRecord.setCombatLevel(0);
                killRecord.setDrops(drops);
                killRecord.setSyncedToServer(false); // picked up by the next batch
                killRecord.setGameMode(state.getCurrentGameMode());
                killRecord.setAccountType(state.getCurrentAccountSubtype());

                stats.addKill(killRecord);

                storageManager.addKill(bossName, npcId, 0, killNumber, 0, 0, drops);
                existingKCsByBoss.computeIfAbsent(bossName, k -> new HashSet<>()).add(killNumber);

                importedKills++;
                if (isNewBoss) importedBosses++;
            }
            }
            catch (Exception ex)
            {
                importError[0] = ex.getMessage();
                log.error("RuneLite import: record-build pass failed", ex);
            }
            finally
            {
                tally[0] = importedKills;
                tally[1] = skippedDupes;
                tally[2] = skippedNoDrops;
                tally[3] = importedBosses;
                latch.countDown();
            }
            });

            if (!latch.await(20, java.util.concurrent.TimeUnit.SECONDS))
            {
                return "Import failed: timed out resolving item data — make sure you are logged in, then try again.";
            }
            if (importError[0] != null)
            {
                return "Import failed: " + importError[0];
            }

            if (tally[0] > 0)
            {
                storageManager.saveData();
                cleanupZeroValueDrops();
                SwingUtilities.invokeLater(() -> { if (panel != null) panel.refreshDisplay(); });
            }

            return String.format(
                    "Import complete!\n\n"
                            + "Kills imported  : %d\n"
                            + "New bosses      : %d\n"
                            + "Skipped (dupes) : %d already tracked\n"
                            + "Skipped (empty) : %d had no drop data\n\n"
                            + "Source: %s",
                    tally[0], tally[3], tally[1], tally[2],
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
        hiddenDrops.computeIfAbsent(npcName, k -> ConcurrentHashMap.newKeySet()).add(itemId);
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
     * it survives a restart. Independent of RuneLite's own ignore feature.
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
        storageManager.scheduleSave();
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
        {
            Set<Integer> concurrentSet = ConcurrentHashMap.newKeySet();
            concurrentSet.addAll(e.getValue());
            hiddenDrops.put(e.getKey(), concurrentSet);
        }
    }

    public boolean isBossHidden(String npcName)
    {
        return hiddenBosses.contains(npcName);
    }

    public void hideBoss(String npcName)
    {
        hiddenBosses.add(npcName);
        persistHiddenBosses();
    }

    public void unhideBoss(String npcName)
    {
        hiddenBosses.remove(npcName);
        persistHiddenBosses();
    }

    /** Pushes the in-memory hidden-bosses set down to {@link LootStorageData} so it survives a restart. */
    private void persistHiddenBosses()
    {
        LootStorageData data = storageManager.getCurrentData();
        if (data == null) data = storageManager.loadData();
        if (data == null) return;
        data.setHiddenBosses(new HashSet<>(hiddenBosses));
        storageManager.scheduleSave();
    }

    /** Restores hidden-bosses from persisted storage. Called by {@link #loadFromStorage}. */
    private void rehydrateHiddenBosses()
    {
        LootStorageData data = storageManager.getCurrentData();
        if (data == null) return;
        Set<String> saved = data.getHiddenBosses();
        if (saved == null) return;
        hiddenBosses.clear();
        hiddenBosses.addAll(saved);
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
            // Plain itemManager.getItemPrice() returns 0 for noted/charged/
            // untradeable variants (e.g. Scythe of Vitur, noted items) — go
            // through ItemValueResolver so those still report a real value by
            // canonicalising or decomposing into their tradeable components.
            int  gePrice    = ItemValueResolver.perItemGeValue(itemManager, item.getId());
            // long math: gePrice * quantity overflows int for large stacks of
            // high-value items (e.g. big coin / rune drops) and would record a
            // negative or garbage value.
            long totalValue = (long) gePrice * item.getQuantity();

            if (totalValue < config.minimumLootValue()) continue;

            ItemComposition canonicalComp = itemManager.getItemComposition(itemManager.canonicalize(item.getId()));

            LootStorageData.DropRecord drop = new LootStorageData.DropRecord();
            drop.setItemId   (item.getId());
            drop.setItemName (comp.getName());
            drop.setQuantity (item.getQuantity());
            drop.setGePrice  (gePrice);
            drop.setHighAlch (Math.max(comp.getHaPrice(), canonicalComp.getHaPrice()));
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