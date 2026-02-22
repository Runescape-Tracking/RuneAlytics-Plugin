package com.runealytics;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.api.widgets.Widget;
import net.runelite.http.api.loottracker.LootRecord;
import net.runelite.http.api.loottracker.LootRecordType;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.runealytics.RuneAlyticsPlugin.WIDGET_VARLAMORE_LAIR_REWARD;

@Slf4j
@Singleton
public class LootTrackerManager
{
    // ==================== CONSTANTS ====================

    /** Pattern to extract kill count from game chat (e.g. "Your Zulrah kill count is: 42") */
    private static final Pattern KC_PATTERN = Pattern.compile("Your (.+) kill count is: (\\d+)");

    /**
     * Dedup window for CHEST / PlayerLootReceived events only.
     * This prevents double-firing when both the widget method AND the
     * PlayerLootReceived event capture the same loot.
     *
     * <p>NOT applied to NpcLootReceived — RuneLite already guarantees that
     * event fires exactly once per kill, so rapid kills of the same NPC
     * (e.g. Ice Giants) must all be recorded independently.</p>
     */
    private static final long PLAYER_LOOT_DEDUPE_WINDOW_MS = 2_000;

    // ==================== BOSS ID WHITELIST ====================

    /**
     * NPC IDs that are always tracked regardless of the "Track All NPCs" setting.
     * Extend this list when new bosses are added to OSRS.
     */
    private static final Set<Integer> TRACKED_BOSS_IDS = ImmutableSet.of(
            // --- VARLAMORE & RECENT (2024-2026) ---
            13751, 13752, 13753, 13754, 13755, 13756, 13757, 13758, // Royal Titans (Eldric/Branda)
            14000, 14001, 14002, 14003, 14013, 14014,               // The Hueycoatl
            13010, 13011, 13012, 13013, 13014, 13015,               // Moons of Peril
            12821, 12822, 12823, 13668, 13669, 13670,               // Yama (Sol Heredit) & Araxxor
            12922, 12923, 12924, 12925, 13579, 13580,               // Scurrius & Amoxliatl
            13147, 13148, 13149,                                    // Tormented Demons

            // --- RAIDS ---
            11750, 11751, 11752, 11753, 11754, 11770, 11771,        // ToA (Tombs of Amascut)
            10674, 10698, 10702, 10704, 10707, 10847,               // ToB (Theatre of Blood)
            7554, 7555, 7556,                                       // CoX (Great Olm)

            // --- GOD WARS DUNGEON ---
            2215, 2216, 2217, 2218, 2205, 2206, 2207,               // Zilyana, Graardor, Kree, K'ril
            6260, 6261, 6262, 6263, 6203, 6204, 6205, 6206,         // GWD Minions
            11278, 11279, 11280, 11281, 11282,                      // Nex

            // --- WILDERNESS & REVAMPED ---
            11872, 11867, 11868, 11962, 11963, 11946, 11947,        // Artio, Callisto, Calvar'ion, Vet'ion
            11993, 11994, 11973, 11974,                             // Spindel, Venenatis
            2054, 6611, 6612, 6618, 6619, 6615, 319,                // Chaos Fanatic, Scorpia, Crazy Arch, Corp

            // --- SLAYER & WORLD BOSSES ---
            2042, 2043, 2044, 8059, 8060, 50,                       // Zulrah, Vorkath, KBD
            5862, 5886, 1999, 7855, 494, 496, 7605, 8609,           // Sire, Cerb, Kraken, Smoke Devil, Hydra
            7544, 7796, 9415, 9416, 9425, 9426,                     // Grotesque Guardians, Nightmare

            // --- DESERT TREASURE 2 ---
            12166, 12167, 12193, 12214, 12205, 12223, 12225, 12227, // Duke, Leviathan, Vardorvis, Whisperer

            // --- MINIGAME & SKILLING ---
            2025, 2026, 2027, 2028, 2029, 2030,                     // Barrows
            10565, 7559, 9050, 10814                                // Tempoross, Wintertodt, Zalcano, Nightmare
    );

    /**
     * Map of display-friendly boss name → primary NPC ID.
     * Used when we only have a name string (e.g. from PlayerLootReceived sources).
     */
    private static final Map<String, Integer> BOSS_NAME_TO_ID = ImmutableMap.<String, Integer>builder()
            // --- GOD WARS DUNGEON ---
            .put("Commander Zilyana",   2215)
            .put("General Graardor",    2260)
            .put("Kree'arra",           6260)
            .put("K'ril Tsutsaroth",    6203)
            .put("Nex",                 11278)
            .put("Royal Titans", 13751)
            .put("The Royal Titans", 13751)
            .put("Eldric", 13751)
            .put("Branda", 13751)

            // --- VARLAMORE & NEW (2024-2026) ---
            .put("The Hueycoatl",       14000)
            .put("Moons of Peril",      13010) // Blue Moon
            .put("Yama",                12821) // Sol Heredit
            .put("Araxxor",             13668)
            .put("Scurrius",            12922)
            .put("Amoxliatl",           13579)
            .put("Tormented Demon",     13147)

            // --- DESERT TREASURE 2 ---
            .put("Duke Sucellus",       12166)
            .put("The Leviathan",       12193)
            .put("Vardorvis",           12205)
            .put("The Whisperer",       12225)

            // --- WILDERNESS REVAMPED ---
            .put("Artio",               11962)
            .put("Callisto",            11963)
            .put("Calvar'ion",          11946)
            .put("Vet'ion",             11947)
            .put("Spindel",             11993)
            .put("Venenatis",           11973)

            // --- RAIDS ---
            .put("Chambers of Xeric",    7554)
            .put("Theatre of Blood",    10674)
            .put("Tombs of Amascut",    11750)

            // --- CLASSIC BOSSES ---
            .put("Corporeal Beast",     319)
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
            .put("The Nightmare",       9415)
            .put("Phosani's Nightmare", 9416)
            .put("Barrows",             2025)
            .put("Corrupted Gauntlet",  9036)
            .put("The Gauntlet",        9035)
            .put("Zalcano",             9050)
            .put("Wintertodt",          7559)
            .put("Tempoross",           10565)
            .build();

    // ==================== DEPENDENCIES ====================

    private final Client client;
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final RunealyticsConfig config;
    private final RuneAlyticsState state;
    private final LootStorageManager storageManager;
    private final LootTrackerApiClient apiClient;
    private final ScheduledExecutorService executorService;


    // ==================== STATE ====================

    /** Reference to the loot tracker UI panel; may be null before first login */
    private LootTrackerPanel panel;

    /**
     * Whether we have already loaded local loot data this session.
     * Prevents redundant disk reads on world-hops.
     */
    private boolean hasAttemptedSync = false;

    /**
     * Gate flag: server sync is ONLY permitted when a manual sync is in progress.
     * Prevents automatic background syncing on startup/login.
     */
    private boolean allowSync = false;

    /**
     * In-memory boss stats map, keyed by normalised NPC name.
     * This is the primary data structure driving the UI.
     */
    private final Map<String, BossKillStats> bossKillStats = new ConcurrentHashMap<>();

    /** Listeners notified on every kill or data refresh */
    private final List<LootTrackerUpdateListener> listeners = new ArrayList<>();

    /**
     * Per-boss hidden item sets. itemId entries in this map are excluded
     * from the loot display for the associated NPC name.
     */
    private final Map<String, Set<Integer>> hiddenDrops = new HashMap<>();

    /**
     * Dedup map for PLAYER LOOT ONLY (chest / PlayerLootReceived path).
     * Key = normalised NPC/source name. Value = last time we processed loot
     * from this source. Kills within PLAYER_LOOT_DEDUPE_WINDOW_MS are ignored.
     *
     * <p>NOT used for NpcLootReceived — that event is already deduplicated by
     * RuneLite and we must NOT block rapid kills of the same NPC name.</p>
     */
    private final Map<String, Long> lastPlayerLootTimestamp = new ConcurrentHashMap<>();

    // ==================== CONSTRUCTOR ====================

    @Inject
    public LootTrackerManager(
            Client client,
            ClientThread clientThread,
            ItemManager itemManager,
            RunealyticsConfig config,
            RuneAlyticsState state,
            LootStorageManager storageManager,
            LootTrackerApiClient apiClient,
            ScheduledExecutorService executorService)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.itemManager = itemManager;
        this.config = config;
        this.state = state;
        this.storageManager = storageManager;
        this.apiClient = apiClient;
        this.executorService = executorService;
    }

    // ==================== LIFECYCLE ====================

    /**
     * Attaches the UI panel that this manager drives.
     * Must be called before any loot is processed so highlights/refreshes work.
     *
     * @param panel the LootTrackerPanel instance
     */
    public void setPanel(LootTrackerPanel panel)
    {
        this.panel = panel;
        log.info("LootTrackerManager: panel reference set");
    }

    /**
     * Called once during plugin startUp().
     * Resets the sync-attempted flag so data loads fresh on the next login.
     */
    public void initialize()
    {
        log.info("LootTrackerManager: initialising");
        hasAttemptedSync = false;
    }

    /**
     * Called during plugin shutDown().
     * Flushes in-memory loot data to disk so nothing is lost.
     */
    public void shutdown()
    {
        log.info("LootTrackerManager: shutting down — saving for '{}'",
                state.getVerifiedUsername());
        storageManager.saveData();
    }

    // ==================== LOOT PROCESSING ====================

    /**
     * Processes loot from an NPC kill (NpcLootReceived event).
     *
     * <p>This is the correct path for ALL standard ground-drop NPCs: giants,
     * dragons, GWD bosses, Vorkath, Zulrah, Cerberus, Hydra, wilderness bosses, etc.</p>
     *
     * <p>NO deduplication is applied here because RuneLite's NpcLootReceived
     * fires exactly once per kill. Applying a time-window dedup here would
     * incorrectly block rapid kills of the same NPC type (e.g. killing two
     * Ice Giants within 2 seconds would only record the first one).</p>
     *
     * @param npc   the NPC that was killed (provides ID, name, combat level)
     * @param items the items dropped (already in our internal ItemStack format)
     */
    /**
     * Processes loot from an NPC kill (NpcLootReceived event).
     *
     * <p>Logs the NPC name and ID at INFO level unconditionally — this means
     * even if the NPC is filtered out, you can check logs to find its ID
     * and add it to TRACKED_BOSS_IDS.</p>
     *
     * @param npc   the NPC that was killed
     * @param items items dropped (our internal ItemStack format)
     */
    public void processNpcLoot(NPC npc, List<ItemStack> items)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        if (npc == null || npc.getName() == null)
        {
            log.warn("processNpcLoot: null NPC, skipping");
            return;
        }

        // *** Log BEFORE any filtering so we can always find new NPC IDs in logs ***
        log.info("NPC loot event: name='{}' id={} cb={} items={}",
                npc.getName(), npc.getId(), npc.getCombatLevel(), items.size());

        if (!state.isVerified())
        {
            log.debug("processNpcLoot: not verified, skipping");
            return;
        }

        String npcName = normalizeBossName(npc.getName());
        int npcId = npc.getId();
        int combatLevel = npc.getCombatLevel();
        int world = client.getWorld();

        boolean isBoss = isBoss(npcId, npcName);

        if (!isBoss && !config.trackAllNpcs())
        {
            // Log at WARN (not debug) so the user can see filtered NPCs in standard logs
            // and add their IDs to TRACKED_BOSS_IDS
            log.warn("NPC filtered (not a tracked boss, trackAllNpcs=false): '{}' id={}  " +
                            "→ Enable 'Track All NPCs' in config or add id={} to TRACKED_BOSS_IDS",
                    npcName, npcId, npcId);
            return;
        }

        List<LootStorageData.DropRecord> drops = convertItemStacksToDropRecords(items);

        if (drops.isEmpty())
        {
            log.debug("processNpcLoot: all items filtered by min value for '{}'", npcName);
            return;
        }

        recordKill(npcName, npcId, combatLevel, world, drops);
    }

    public void processPlayerLoot(String source, List<ItemStack> items)
    {
        if (items == null || items.isEmpty()) return;

        // The manager receives the 'source' string from the plugin
        String normalizedName = normalizeBossName(source);

        log.info("RuneAlytics: Recording loot for {}", normalizedName);

        LootRecord record = new LootRecord();

        synchronized (this)
        {
            state.addLootRecord(record);
            state.incrementKillCount(normalizedName);
        }

        if (panel != null)
        {
            panel.updatePanel();
        }
    }

    /**
     * Converts a list of raw ItemStacks into DropRecords by looking up
     * GE price and item name via RuneLite's ItemManager.
     *
     * <p>Items below the configured minimumLootValue are silently discarded.</p>
     *
     * @param items raw ItemStacks to convert
     * @return filtered, enriched DropRecord list (may be empty)
     */
    private List<LootStorageData.DropRecord> convertItemStacksToDropRecords(List<ItemStack> items)
    {
        List<LootStorageData.DropRecord> drops = new ArrayList<>();

        for (ItemStack item : items)
        {
            ItemComposition comp = itemManager.getItemComposition(item.getId());
            int gePrice = itemManager.getItemPrice(item.getId());
            int totalValue = gePrice * item.getQuantity();

            // Apply minimum value filter
            if (totalValue < config.minimumLootValue())
            {
                continue;
            }

            LootStorageData.DropRecord drop = new LootStorageData.DropRecord();
            drop.setItemId(item.getId());
            drop.setItemName(comp.getName());
            drop.setQuantity(item.getQuantity());
            drop.setGePrice(gePrice);
            drop.setHighAlch(comp.getHaPrice());
            drop.setTotalValue(totalValue);
            drop.setHidden(false);

            drops.add(drop);
        }

        return drops;
    }
    private String lastChestSource = null;
    /**
     * Core kill-recording method.  Increments in-memory stats, persists to disk,
     * optionally syncs to server, and refreshes the UI panel.
     *
     * @param npcName     normalised NPC/source name
     * @param npcId       NPC ID (0 for chest sources)
     * @param combatLevel NPC combat level (0 for chest sources)
     * @param world       current world number
     * @param drops       processed, filtered DropRecord list
     */
    private void recordKill(String npcName, int npcId, int combatLevel, int world,
                            List<LootStorageData.DropRecord> drops)
    {
        // Get or create in-memory stats for this boss
        BossKillStats stats = bossKillStats.computeIfAbsent(npcName,
                k -> new BossKillStats(npcName, npcId));

        int killNumber = stats.getKillCount() + 1;

        // Build the NpcKillRecord for in-memory history
        NpcKillRecord killRecord = new NpcKillRecord(npcName, npcId, combatLevel, world);
        killRecord.setKillNumber(killNumber);

        for (LootStorageData.DropRecord drop : drops)
        {
            killRecord.addDrop(new LootDrop(
                    drop.getItemId(),
                    drop.getItemName(),
                    drop.getQuantity(),
                    drop.getGePrice(),
                    drop.getHighAlch()
            ));
        }

        // Update in-memory stats (increments killCount)
        stats.addKill(killRecord);

        // Persist to disk immediately
        storageManager.addKill(npcName, npcId, combatLevel, killNumber,
                world, state.getPrestige(), drops);

        // Async server sync (if enabled)
        if (config.syncLootToServer())
        {
            syncKillToServer(npcName, npcId, combatLevel, killNumber, world, drops);
        }

        // Refresh UI on the client thread
        if (panel != null)
        {
            clientThread.invokeLater(() -> {
                panel.highlightBoss(npcName);
                panel.refreshDisplay();
            });
        }

        notifyKillRecorded(stats);

        log.info("Kill recorded: {} #{} — {} drops, {} gp",
                npcName, killNumber, drops.size(),
                drops.stream().mapToLong(LootStorageData.DropRecord::getTotalValue).sum());
    }

    // ==================== SERVER SYNC ====================

    /**
     * Asynchronously syncs a single kill to the RuneAlytics server.
     * Called immediately after recordKill if syncLootToServer is enabled.
     *
     * @param npcName     normalised NPC name
     * @param npcId       NPC ID
     * @param combatLevel NPC combat level
     * @param killNumber  sequential kill number for this boss
     * @param world       world the kill occurred on
     * @param drops       the drop records to send
     */
    private void syncKillToServer(String npcName, int npcId, int combatLevel,
                                  int killNumber, int world,
                                  List<LootStorageData.DropRecord> drops)
    {
        if (!state.canSync())
        {
            log.debug("Sync skipped — sync in progress or not verified");
            return;
        }

        executorService.submit(() -> {
            try
            {
                apiClient.syncSingleKill(
                        state.getVerifiedUsername(),
                        npcName, npcId, combatLevel,
                        killNumber, world,
                        System.currentTimeMillis(),
                        state.getPrestige(),
                        drops
                );
                storageManager.markKillsSynced(npcName,
                        System.currentTimeMillis(), System.currentTimeMillis());
                log.debug("Synced to server: {} #{}", npcName, killNumber);
            }
            catch (Exception e)
            {
                log.error("Failed to sync kill to server: {} #{}", npcName, killNumber, e);
            }
        });
    }

    /**
     * Called on login when player is verified.
     * Loads local data ONLY — does not contact the server.
     */
    public void onPlayerLoggedInAndVerified()
    {
        if (hasAttemptedSync)
        {
            log.info("Local data already loaded this session");
            return;
        }

        log.info("Player verified — loading LOCAL data (no auto-sync)");
        loadFromStorage();
        hasAttemptedSync = true;
    }

    /**
     * Downloads kill history from the server and merges it into local storage.
     * ONLY callable when {@link #allowSync} is true (i.e. during manual sync).
     */
    public void downloadKillHistoryFromServer()
    {
        if (!allowSync)
        {
            log.warn("downloadKillHistoryFromServer: blocked — use manual sync button");
            return;
        }

        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty() || !state.canSync())
        {
            log.warn("downloadKillHistoryFromServer: cannot sync");
            return;
        }

        try
        {
            state.startSync();
            log.info("Downloading kill history for '{}'", username);

            Map<String, LootStorageData.BossKillData> serverData =
                    apiClient.fetchKillHistoryFromServer(username);

            if (serverData == null || serverData.isEmpty())
            {
                log.info("No server kill history for '{}'", username);
                return;
            }

            storageManager.mergeServerData(serverData);
            refreshLootDisplay();

            log.info("Downloaded and merged {} bosses from server", serverData.size());
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
     * Uploads all locally unsynced kills to the server in batches of 50.
     * Marks uploaded kills as synced in local storage on success.
     */
    public void uploadUnsyncedKills()
    {
        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty() || !state.canSync())
        {
            return;
        }

        try
        {
            state.startSync();

            Map<String, List<LootStorageData.KillRecord>> unsyncedKills =
                    storageManager.getAllUnsyncedKills();

            if (unsyncedKills.isEmpty())
            {
                log.debug("No unsynced kills to upload");
                return;
            }

            int total = unsyncedKills.values().stream().mapToInt(List::size).sum();
            log.info("Uploading {} unsynced kills across {} bosses", total, unsyncedKills.size());

            final int BATCH_SIZE = 50;
            List<LootStorageData.KillRecord> allKills = new ArrayList<>();
            Map<String, String> killTimeToBossName = new HashMap<>();

            for (Map.Entry<String, List<LootStorageData.KillRecord>> entry : unsyncedKills.entrySet())
            {
                for (LootStorageData.KillRecord kill : entry.getValue())
                {
                    allKills.add(kill);
                    killTimeToBossName.put(String.valueOf(kill.getTimestamp()), entry.getKey());
                }
            }

            for (int i = 0; i < allKills.size(); i += BATCH_SIZE)
            {
                int end = Math.min(i + BATCH_SIZE, allKills.size());
                List<LootStorageData.KillRecord> batch = allKills.subList(i, end);

                Map<String, List<LootStorageData.KillRecord>> batchByBoss = new HashMap<>();
                for (LootStorageData.KillRecord kill : batch)
                {
                    String boss = killTimeToBossName.get(String.valueOf(kill.getTimestamp()));
                    batchByBoss.computeIfAbsent(boss, k -> new ArrayList<>()).add(kill);
                }

                log.info("Uploading batch {}/{} ({} kills)",
                        (i / BATCH_SIZE) + 1,
                        (allKills.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                        batch.size());

                boolean success = apiClient.bulkSyncKills(username, batchByBoss);

                if (success)
                {
                    for (Map.Entry<String, List<LootStorageData.KillRecord>> entry : batchByBoss.entrySet())
                    {
                        List<LootStorageData.KillRecord> kills = entry.getValue();
                        if (!kills.isEmpty())
                        {
                            long minTs = kills.stream().mapToLong(LootStorageData.KillRecord::getTimestamp).min().orElse(0);
                            long maxTs = kills.stream().mapToLong(LootStorageData.KillRecord::getTimestamp).max().orElse(Long.MAX_VALUE);
                            storageManager.markKillsSynced(entry.getKey(), minTs, maxTs);
                        }
                    }
                }
                else
                {
                    log.error("Batch upload failed — stopping");
                    break;
                }

                if (i + BATCH_SIZE < allKills.size())
                {
                    Thread.sleep(500);
                }
            }

            log.info("Batch upload complete");
        }
        catch (Exception e)
        {
            log.error("Failed to upload unsynced kills", e);
        }
        finally
        {
            state.endSync();
        }
    }

    /**
     * Loads local loot data for a specific username without performing any server sync.
     * Safe to call on login; does not modify server state.
     *
     * @param username the OSRS username to load data for
     */
    public void loadLocalDataForUser(String username)
    {
        log.info("Loading local loot data for '{}'", username);

        LootStorageData storageData = storageManager.loadLootData(username);

        if (storageData == null || storageData.getBossKills().isEmpty())
        {
            log.info("No local data for '{}'", username);
            clientThread.invokeLater(() -> {
                bossKillStats.clear();
                if (panel != null) panel.refreshDisplay();
            });
            return;
        }

        clientThread.invokeLater(this::refreshLootDisplay);
    }

    /**
     * Performs a bidirectional manual sync: download from server, upload local,
     * then refresh the UI.  This is the ONLY way to sync with the server.
     *
     * <p>The {@link #allowSync} gate is set to true only for the duration of
     * this call and reset in the finally block.</p>
     *
     * @param username the verified OSRS username to sync for
     */
    public void performManualSync(String username)
    {
        if (username == null || username.isEmpty())
        {
            log.warn("performManualSync: no username");
            return;
        }

        log.info("=== MANUAL SYNC STARTED for '{}' ===", username);

        executorService.submit(() -> {
            try
            {
                allowSync = true;

                log.info("Step 1: Downloading server kill history");
                downloadKillHistoryFromServer();

                log.info("Step 2: Uploading unsynced local kills");
                uploadUnsyncedKills();

                log.info("Step 3: Refreshing UI");
                clientThread.invokeLater(this::refreshLootDisplay);

                log.info("=== MANUAL SYNC COMPLETE ===");

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

    /**
     * @deprecated Automatic sync on startup is disabled. Use {@link #performManualSync} instead.
     */
    @Deprecated
    public void syncWithServerOnStartup()
    {
        log.warn("syncWithServerOnStartup() called but automatic sync is disabled — use manual sync");
    }

    // ==================== DATA MANAGEMENT ====================

    /**
     * Loads loot data from local disk and rebuilds the in-memory stats map.
     * Called after login when the player is verified.
     */
    public void loadFromStorage()
    {
        LootStorageData data = storageManager.loadData();
        if (data == null || data.getBossKills().isEmpty())
        {
            log.info("No stored loot data found");
            return;
        }

        log.info("Loading stored data: {} bosses", data.getBossKills().size());
        refreshLootDisplay();
    }

    /**
     * Rebuilds {@link #bossKillStats} from the current on-disk LootStorageData
     * and triggers a UI refresh.  Called after any operation that changes
     * the underlying data (load, merge, prestige, clear).
     *
     * <p>Clears the map first to avoid duplicates, then re-adds every boss
     * by replaying kill records through BossKillStats.addKill().</p>
     */
    private void refreshLootDisplay()
    {
        LootStorageData data = storageManager.getCurrentData();

        if (data == null || data.getBossKills().isEmpty())
        {
            log.info("refreshLootDisplay: no data");
            bossKillStats.clear();
            if (panel != null) clientThread.invokeLater(() -> panel.refreshDisplay());
            return;
        }

        log.debug("refreshLootDisplay: {} bosses", data.getBossKills().size());

        // Clear first to prevent stale/duplicate entries
        bossKillStats.clear();

        for (Map.Entry<String, LootStorageData.BossKillData> entry : data.getBossKills().entrySet())
        {
            String bossName = entry.getKey();
            LootStorageData.BossKillData bossData = entry.getValue();

            BossKillStats stats = new BossKillStats(bossData.getNpcName(), bossData.getNpcId());
            stats.setPrestige(bossData.getPrestige());
            stats.setTotalLootValue(bossData.getTotalLootValue());
            stats.setHighestDrop(bossData.getTotalLootValue());

            List<LootStorageData.KillRecord> killRecords = bossData.getKills();

            if (killRecords != null && !killRecords.isEmpty())
            {
                for (LootStorageData.KillRecord killRecord : killRecords)
                {
                    NpcKillRecord npcKill = new NpcKillRecord(
                            bossData.getNpcName(),
                            bossData.getNpcId(),
                            killRecord.getCombatLevel(),
                            killRecord.getWorld()
                    );
                    npcKill.setTimestamp(killRecord.getTimestamp());
                    npcKill.setKillNumber(killRecord.getKillNumber());

                    for (LootStorageData.DropRecord drop : killRecord.getDrops())
                    {
                        npcKill.addDrop(new LootDrop(
                                drop.getItemId(), drop.getItemName(),
                                drop.getQuantity(), drop.getGePrice(), drop.getHighAlch()
                        ));
                    }

                    stats.addKill(npcKill); // increments killCount
                }

                // Sanity-check: if kill count diverges from disk, trust disk
                if (stats.getKillCount() != bossData.getKillCount())
                {
                    log.warn("Kill count mismatch for '{}': memory={} disk={}",
                            bossName, stats.getKillCount(), bossData.getKillCount());
                    stats.setKillCount(bossData.getKillCount());
                }
            }
            else
            {
                // No individual kill records — fall back to aggregated count
                stats.setKillCount(bossData.getKillCount());
            }

            bossKillStats.put(stats.getNpcName(), stats);
        }

        log.debug("refreshLootDisplay: rebuilt {} bosses", bossKillStats.size());

        if (panel != null)
        {
            clientThread.invokeLater(() -> panel.refreshDisplayPreservingLayout());
        }
    }

    /**
     * Called when the player switches accounts (different RSN detected).
     * Clears in-memory state and reloads from local storage for the new account.
     *
     * @param newUsername the new account's OSRS username
     */
    public void onAccountChanged(String newUsername)
    {
        log.info("Account changed to '{}'", newUsername);
        bossKillStats.clear();
        hasAttemptedSync = false;
        loadFromStorage();

        if (panel != null)
        {
            SwingUtilities.invokeLater(() -> panel.refreshDisplay());
        }
    }

    /**
     * Returns an unmodifiable view of all in-memory boss stats.
     *
     * @return map of normalised boss name → BossKillStats
     */
    public Map<String, BossKillStats> getBossKillStats()
    {
        return Collections.unmodifiableMap(bossKillStats);
    }

    /**
     * Returns all boss stats as a list (order is not guaranteed).
     *
     * @return list of all in-memory BossKillStats
     */
    public List<BossKillStats> getAllBossStats()
    {
        return new ArrayList<>(bossKillStats.values());
    }

    /**
     * Clears all loot data from memory, disk, and hidden-drop settings.
     * Also clears the player loot dedup map.
     */
    public void clearAllData()
    {
        bossKillStats.clear();
        hiddenDrops.clear();
        lastPlayerLootTimestamp.clear();
        storageManager.clearData();

        if (panel != null)
        {
            clientThread.invokeLater(() -> panel.refreshDisplay());
        }

        notifyDataRefresh();
    }

    /**
     * Removes all tracked data for a single boss.
     *
     * @param npcName the normalised boss name to clear
     */
    public void clearBossData(String npcName)
    {
        bossKillStats.remove(npcName);
        hiddenDrops.remove(npcName);
        lastPlayerLootTimestamp.remove(npcName);
        storageManager.saveData();

        if (panel != null)
        {
            clientThread.invokeLater(() -> panel.refreshDisplay());
        }
    }

    /**
     * Resets kill stats for a boss and increments its prestige counter.
     * Kill history and drops are cleared but prestige level is preserved.
     *
     * @param npcName the normalised boss name to prestige
     */
    public void prestigeBoss(String npcName)
    {
        BossKillStats stats = bossKillStats.get(npcName);
        if (stats != null)
        {
            stats.prestige();
            storageManager.saveData();

            if (panel != null)
            {
                clientThread.invokeLater(() -> panel.refreshDisplay());
            }
        }
    }

    // ==================== HIDDEN DROPS ====================

    /**
     * Returns true if the given item has been hidden for the given NPC.
     *
     * @param npcName normalised NPC name
     * @param itemId  the item ID to check
     */
    public boolean isDropHidden(String npcName, int itemId)
    {
        Set<Integer> hidden = hiddenDrops.get(npcName);
        return hidden != null && hidden.contains(itemId);
    }

    /**
     * Hides a specific item drop for an NPC so it is excluded from the UI.
     *
     * @param npcName normalised NPC name
     * @param itemId  the item ID to hide
     */
    public void hideDropForNpc(String npcName, int itemId)
    {
        hiddenDrops.computeIfAbsent(npcName, k -> new HashSet<>()).add(itemId);
        storageManager.saveData();
    }

    /**
     * Un-hides a previously hidden item drop for an NPC.
     *
     * @param npcName normalised NPC name
     * @param itemId  the item ID to un-hide
     */
    public void unhideDropForNpc(String npcName, int itemId)
    {
        Set<Integer> hidden = hiddenDrops.get(npcName);
        if (hidden != null)
        {
            hidden.remove(itemId);
            if (hidden.isEmpty()) hiddenDrops.remove(npcName);
            storageManager.saveData();
        }
    }

    // ==================== UTILITY ====================

    /**
     * Returns true if the NPC should be tracked as a boss, either because
     * its ID is in the whitelist or its name matches known boss name patterns.
     *
     * @param npcId   the NPC's ID
     * @param npcName the NPC's name
     */
    public boolean isBoss(int npcId, String npcName)
    {
        return TRACKED_BOSS_IDS.contains(npcId)
                || (npcName != null && isBossName(npcName));
    }

    /**
     * Returns true if the name string matches any known boss name pattern.
     * Used as a fallback when the NPC ID is not in TRACKED_BOSS_IDS.
     *
     * @param name the NPC name to test (should be lowercase or normalised)
     */
    private boolean isBossName(String name)
    {
        if (name == null) return false;
        String lower = name.toLowerCase();

        return lower.contains("duke")        || lower.contains("leviathan")
                || lower.contains("vardorvis")   || lower.contains("whisperer")
                || lower.contains("zulrah")      || lower.contains("vorkath")
                || lower.contains("cerberus")    || lower.contains("nightmare")
                || lower.contains("gauntlet")    || lower.contains("barrows")
                || lower.contains("yama")        || lower.contains("tempoross")
                || lower.contains("wintertodt")  || lower.contains("zalcano")
                // Varlamore / new content — Ice King, Fire Queen, etc.
                || lower.contains("ice king")    || lower.contains("fire queen")
                || lower.contains("eldric")      || lower.contains("branda")
                // Generic patterns OSRS uses for named mini-bosses
                || lower.contains(" king")       || lower.contains(" queen")
                || lower.contains(" lord")       || lower.contains(" guardian")
                || lower.contains("abyssal")     || lower.contains("thermonuclear")
                || lower.contains("grotesque");
    }

    /**
     * Maps variant NPC names to a canonical display name used as the map key.
     * Call this on every name before using it as a key in bossKillStats.
     *
     * @param name raw NPC/source name
     * @return canonical display name, never null
     */
    public String normalizeBossName(String name)
    {
        if (name == null || name.isEmpty()) return "Unknown";
        String lower = name.toLowerCase();

        if (lower.contains("corrupted gauntlet"))                    return "Corrupted Gauntlet";
        if (lower.contains("gauntlet"))                              return "The Gauntlet";
        if (lower.contains("barrows"))                               return "Barrows";
        if (lower.contains("chambers of xeric"))                     return "Chambers of Xeric";
        if (lower.contains("theatre of blood"))                      return "Theatre of Blood";
        if (lower.contains("tombs of amascut"))                      return "Tombs of Amascut";
        if (lower.contains("fortis colosseum") || lower.contains("colosseum")) return "Fortis Colosseum";
        if (lower.contains("duke") || lower.contains("sucellus"))    return "Duke Sucellus";
        if (lower.contains("leviathan"))                             return "The Leviathan";
        if (lower.contains("vardorvis"))                             return "Vardorvis";
        if (lower.contains("whisperer"))                             return "The Whisperer";
        if (lower.contains("zulrah"))                                return "Zulrah";
        if (lower.contains("vorkath"))                               return "Vorkath";
        if (lower.contains("cerberus"))                              return "Cerberus";
        if (lower.contains("hydra"))                                 return "Alchemical Hydra";
        if (lower.contains("graardor"))                              return "General Graardor";
        if (lower.contains("zilyana"))                               return "Commander Zilyana";
        if (lower.contains("kree"))                                  return "Kree'arra";
        if (lower.contains("kril"))                                  return "K'ril Tsutsaroth";
        if (lower.contains("yama"))                                  return "Yama";
        if (lower.contains("tempoross"))                             return "Tempoross";
        if (lower.contains("wintertodt"))                            return "Wintertodt";
        if (lower.contains("royal titans") || lower.contains("branda") || lower.contains("eldric"))                            return "Royal Titans";


        return name.trim();
    }

    /**
     * Parses a kill-count chat message and logs the extracted boss name + KC.
     * Does not modify any stored data — KC is authoritative from the in-game counter.
     *
     * @param message the raw chat message string
     */
    public void parseKillCountMessage(String message)
    {
        Matcher matcher = KC_PATTERN.matcher(message);
        if (matcher.find())
        {
            String bossName = normalizeBossName(matcher.group(1));
            int kc = Integer.parseInt(matcher.group(2));
            log.debug("KC from chat: {} = {}", bossName, kc);
        }
    }

    /**
     * Looks up the primary NPC ID for a given boss name using the BOSS_NAME_TO_ID map.
     *
     * @param bossName display name of the boss
     * @return NPC ID, or null if the name is not in the map
     */
    public Integer getBossIdFromName(String bossName)
    {
        return BOSS_NAME_TO_ID.get(normalizeBossName(bossName));
    }

    // ==================== LISTENERS ====================

    /**
     * Registers a listener that will be notified on kill events and data refreshes.
     *
     * @param listener the listener to add
     */
    public void addListener(LootTrackerUpdateListener listener)
    {
        listeners.add(listener);
    }

    /** Notifies all listeners that a kill was recorded */
    private void notifyKillRecorded(BossKillStats stats)
    {
        for (LootTrackerUpdateListener listener : listeners)
        {
            listener.onDataRefresh();
        }
    }

    /** Notifies all listeners that data was refreshed (e.g. after clear/prestige) */
    private void notifyDataRefresh()
    {
        for (LootTrackerUpdateListener listener : listeners)
        {
            listener.onDataRefresh();
        }
    }
}