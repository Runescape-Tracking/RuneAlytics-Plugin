package com.runealytics;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.runealytics.LootStorageData;
import com.runealytics.LootStorageManager;
import com.runealytics.LootTrackerApiClient;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
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

@Slf4j
@Singleton
public class LootTrackerManager
{
    // ==================== CONSTANTS ====================

    private static final Pattern KC_PATTERN = Pattern.compile("Your (.+) kill count is: (\\d+)");
    private static final long KILL_DEDUPE_WINDOW_MS = 2000; // 2 second window for deduplication

    // Comprehensive list of boss NPC IDs
    private static final Set<Integer> TRACKED_BOSS_IDS = ImmutableSet.of(
            // GWD
            2215, 2216, 2217, 2218, 2205, 2206, 2207, // Zilyana + minions
            2260, 2261, 2262, 2263, // Graardor + minions
            6260, 6261, 6262, 6263, // Kree'arra + minions
            6203, 6204, 6205, 6206, // K'ril + minions
            319,  // Corporeal Beast
            963, 965, // Kalphite Queen
            2265, 2266, 2267, // DKS
            6766, 6609, 6611, 6612, 2054, 6618, 6619, 6615, // Wilderness bosses
            50, 8059, 8060, // KBD, Vorkath
            2042, 2043, 2044, // Zulrah
            5862, 5886, 1999, 7855, // Sire, Cerberus
            7544, 7796, // Grotesque Guardians
            494, 496, 7605, 8609, // Kraken, Thermy, Hydra
            12796, 12821, 12797, 12798, 12799, 12800, 12801, 12802, 12803, 12804, 12805, 12806, 12807, // Colosseum
            9415, 9416, 9425, 9426, // Nightmare
            11278, 11279, // Nex
            10674, 10698, 10702, 10704, 10707, 10847, 10848, 10849, // ToB
            7554, 7555, 7556, // CoX
            11750, 11751, 11752, 11753, 11754, 11770, 11771, 11772, 11773, // ToA
            12166, 12167, 12193, 12214, 12205, 12223, 12225, 12227, // DT2
            9027, 10814, 7858, 8350, 8338, // Other bosses
            10565, 7559, 9050, // Tempoross, Wintertodt, Zalcano
            2025, 2026, 2027, 2028, 2029, 2030, // Barrows
            11872, 11867, 11868 // Wilderness revamped
    );

    // Boss name to ID mapping
    private static final Map<String, Integer> BOSS_NAME_TO_ID = ImmutableMap.<String, Integer>builder()
            .put("Commander Zilyana", 2215)
            .put("General Graardor", 2260)
            .put("Kree'arra", 6260)
            .put("K'ril Tsutsaroth", 6203)
            .put("Corporeal Beast", 319)
            .put("Kalphite Queen", 963)
            .put("Dagannoth Prime", 2265)
            .put("Dagannoth Rex", 2266)
            .put("Dagannoth Supreme", 2267)
            .put("Vorkath", 8059)
            .put("Zulrah", 2042)
            .put("Duke Sucellus", 12166)
            .put("The Leviathan", 12193)
            .put("Vardorvis", 12205)
            .put("The Whisperer", 12225)
            .put("Alchemical Hydra", 8609)
            .put("Cerberus", 5862)
            .put("The Nightmare", 9415)
            .put("Phosani's Nightmare", 9416)
            .put("Nex", 11278)
            .put("Barrows", 2025)
            .put("Corrupted Gauntlet", 9036)
            .put("The Gauntlet", 9035)
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

    private LootTrackerPanel panel;
    private boolean hasAttemptedSync = false;
    private boolean allowSync = false; // ONLY true when manual sync button pressed

    private final Map<String, BossKillStats> bossKillStats = new ConcurrentHashMap<>();
    private final List<LootTrackerUpdateListener> listeners = new ArrayList<>();
    private final Map<String, Set<Integer>> hiddenDrops = new HashMap<>();

    // Deduplication tracking
    private final Map<String, Long> lastKillTimestamp = new ConcurrentHashMap<>();

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

    public void setPanel(LootTrackerPanel panel)
    {
        this.panel = panel;
        log.info("Panel reference set");
    }

    public void initialize()
    {
        log.info("Initializing LootTrackerManager");
        hasAttemptedSync = false;
        log.info("LootTrackerManager initialized - data will load on login");
    }

    public void shutdown()
    {
        log.info("Shutting down LootTrackerManager - saving data for {}", state.getVerifiedUsername());
        storageManager.saveData();
    }

    // ==================== LOOT PROCESSING ====================

    /**
     * Process NPC loot from NpcLootReceived event
     */
    public void processNpcLoot(NPC npc, List<ItemStack> items)
    {
        if (!config.enableLootTracking() || !state.isVerified())
        {
            return;
        }

        String npcName = normalizeBossName(npc.getName());
        int npcId = npc.getId();
        int combatLevel = npc.getCombatLevel();
        int world = client.getWorld();

        boolean isBoss = isBoss(npcId, npcName);
        boolean trackAllNpcs = config.trackAllNpcs();

        if (!isBoss && !trackAllNpcs)
        {
            log.debug("Skipping non-boss NPC: {}", npcName);
            return;
        }

        // DEDUPLICATION: Check if this is a duplicate kill within the time window
        long now = System.currentTimeMillis();
        Long lastKill = lastKillTimestamp.get(npcName);
        if (lastKill != null && (now - lastKill) < KILL_DEDUPE_WINDOW_MS)
        {
            log.debug("Duplicate kill detected for {} within {}ms window - skipping", npcName, KILL_DEDUPE_WINDOW_MS);
            return;
        }
        lastKillTimestamp.put(npcName, now);

        // Convert ItemStack to DropRecord
        List<LootStorageData.DropRecord> drops = convertItemStacksToDropRecords(items);

        if (drops.isEmpty())
        {
            return;
        }

        // Record the kill
        recordKill(npcName, npcId, combatLevel, world, drops);
    }

    /**
     * Process player loot from PlayerLootReceived event (chests, PvP, etc.)
     */
    public void processPlayerLoot(String sourceName, List<ItemStack> items)
    {
        if (!config.enableLootTracking() || !state.isVerified())
        {
            return;
        }

        String normalizedName = normalizeBossName(sourceName);
        int world = client.getWorld();

        // DEDUPLICATION: Check if this is a duplicate kill within the time window
        long now = System.currentTimeMillis();
        Long lastKill = lastKillTimestamp.get(normalizedName);
        if (lastKill != null && (now - lastKill) < KILL_DEDUPE_WINDOW_MS)
        {
            log.debug("Duplicate player loot detected for {} within {}ms window - skipping", normalizedName, KILL_DEDUPE_WINDOW_MS);
            return;
        }
        lastKillTimestamp.put(normalizedName, now);

        // Convert items
        List<LootStorageData.DropRecord> drops = convertItemStacksToDropRecords(items);

        if (drops.isEmpty())
        {
            return;
        }

        // Get NPC ID for the source
        Integer npcId = getBossIdFromName(normalizedName);
        if (npcId == null) npcId = 0;

        // Record the kill
        recordKill(normalizedName, npcId, 0, world, drops);
    }

    /**
     * Convert ItemStacks to DropRecords with value filtering
     */
    private List<LootStorageData.DropRecord> convertItemStacksToDropRecords(List<ItemStack> items)
    {
        List<LootStorageData.DropRecord> drops = new ArrayList<>();

        for (ItemStack item : items)
        {
            ItemComposition itemComp = itemManager.getItemComposition(item.getId());
            String itemName = itemComp.getName();
            int gePrice = itemManager.getItemPrice(item.getId());
            int highAlch = itemComp.getHaPrice();
            int totalValue = gePrice * item.getQuantity();

            // Filter by minimum value
            if (totalValue < config.minimumLootValue())
            {
                continue;
            }

            LootStorageData.DropRecord drop = new LootStorageData.DropRecord();
            drop.setItemId(item.getId());
            drop.setItemName(itemName);
            drop.setQuantity(item.getQuantity());
            drop.setGePrice(gePrice);
            drop.setHighAlch(highAlch);
            drop.setTotalValue(totalValue);
            drop.setHidden(false);

            drops.add(drop);
        }

        return drops;
    }

    /**
     * Record a kill to local storage and sync to server
     */
    private void recordKill(String npcName, int npcId, int combatLevel, int world,
                            List<LootStorageData.DropRecord> drops)
    {
        // Get or create stats
        BossKillStats stats = bossKillStats.computeIfAbsent(npcName, k ->
                new BossKillStats(npcName, npcId)
        );

        // Get current kill count from storage to ensure consistency
        int killNumber = stats.getKillCount() + 1;

        // Create NpcKillRecord for BossKillStats
        NpcKillRecord killRecord = new NpcKillRecord(npcName, npcId, combatLevel, world);
        killRecord.setKillNumber(killNumber);

        // Convert DropRecords to LootDrops
        for (LootStorageData.DropRecord drop : drops)
        {
            LootDrop lootDrop = new LootDrop(
                    drop.getItemId(),
                    drop.getItemName(),
                    drop.getQuantity(),
                    drop.getGePrice(),
                    drop.getHighAlch()
            );
            killRecord.addDrop(lootDrop);
        }

        // Add to stats (this will increment kill count and populate killHistory)
        stats.addKill(killRecord);

        // Save to local storage
        storageManager.addKill(npcName, npcId, combatLevel, killNumber, world, state.getPrestige(), drops);

        // Sync to server asynchronously
        if (config.syncLootToServer())
        {
            syncKillToServer(npcName, npcId, combatLevel, killNumber, world, drops);
        }

        // Update UI and highlight this boss
        if (panel != null)
        {
            clientThread.invokeLater(() -> {
                panel.highlightBoss(npcName);
                panel.refreshDisplay();
            });
        }

        // Notify listeners
        notifyKillRecorded(stats);

        log.info("Recorded kill for {}: #{}, {} drops", npcName, killNumber, drops.size());
    }

    // ==================== SERVER SYNC ====================

    /**
     * Sync single kill to server
     */
    private void syncKillToServer(String npcName, int npcId, int combatLevel,
                                  int killNumber, int world,
                                  List<LootStorageData.DropRecord> drops)
    {
        if (!state.canSync())
        {
            log.debug("Cannot sync - sync in progress");
            return;
        }

        executorService.submit(() -> {
            try
            {
                apiClient.syncSingleKill(
                        state.getVerifiedUsername(),
                        npcName,
                        npcId,
                        combatLevel,
                        killNumber,
                        world,
                        System.currentTimeMillis(),
                        state.getPrestige(),
                        drops
                );

                // Mark as synced
                storageManager.markKillsSynced(npcName, System.currentTimeMillis(), System.currentTimeMillis());

                log.debug("Synced kill to server: {} #{}", npcName, killNumber);
            }
            catch (Exception e)
            {
                log.error("Failed to sync kill to server", e);
            }
        });
    }

    /**
     * Called when player logs in and verification is complete
     * LOADS LOCAL DATA ONLY - no automatic server sync
     */
    public void onPlayerLoggedInAndVerified()
    {
        if (hasAttemptedSync)
        {
            log.info("Already loaded data this session");
            return;
        }

        log.info("Player logged in and verified - loading LOCAL data only (no auto-sync)");

        // Load only from local storage - NO SERVER SYNC
        loadFromStorage();

        hasAttemptedSync = true;
    }

    /**
     * Download kill history from server and merge with local
     * ONLY callable from manual sync button - automatic sync disabled
     */
    public void downloadKillHistoryFromServer()
    {
        // CRITICAL: Block all automatic syncs - only allow manual sync
        if (!allowSync)
        {
            log.warn("Automatic sync blocked - use manual sync button only");
            return;
        }

        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty() || !state.canSync())
        {
            log.warn("Cannot download history");
            return;
        }

        try
        {
            state.startSync();
            log.info("Downloading kill history from server for {}", username);

            Map<String, LootStorageData.BossKillData> serverData = apiClient.fetchKillHistoryFromServer(username);

            if (serverData == null || serverData.isEmpty())
            {
                log.info("No kill history on server for {}", username);
                return;
            }

            storageManager.mergeServerData(serverData);
            refreshLootDisplay();

            log.info("Successfully downloaded and merged {} bosses from server", serverData.size());
        }
        catch (Exception e)
        {
            log.error("Failed to download kill history from server", e);
        }
        finally
        {
            state.endSync();
        }
    }

    /**
     * Upload unsynced kills to server in batches
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

            Map<String, List<LootStorageData.KillRecord>> unsyncedKills = storageManager.getAllUnsyncedKills();

            if (unsyncedKills.isEmpty())
            {
                log.debug("No unsynced kills to upload");
                return;
            }

            int totalKills = unsyncedKills.values().stream().mapToInt(List::size).sum();
            log.info("Uploading {} unsynced kills across {} bosses", totalKills, unsyncedKills.size());

            // BATCH PROCESSING - Upload in chunks of 50 kills max
            final int BATCH_SIZE = 50;
            List<LootStorageData.KillRecord> allKills = new ArrayList<>();
            Map<String, String> killToBossName = new HashMap<>();

            // Flatten all kills into a single list
            for (Map.Entry<String, List<LootStorageData.KillRecord>> entry : unsyncedKills.entrySet())
            {
                for (LootStorageData.KillRecord kill : entry.getValue())
                {
                    allKills.add(kill);
                    killToBossName.put(String.valueOf(kill.getTimestamp()), entry.getKey());
                }
            }

            // Split into batches
            for (int i = 0; i < allKills.size(); i += BATCH_SIZE)
            {
                int end = Math.min(i + BATCH_SIZE, allKills.size());
                List<LootStorageData.KillRecord> batch = allKills.subList(i, end);

                // Group batch by boss name
                Map<String, List<LootStorageData.KillRecord>> batchByBoss = new HashMap<>();
                for (LootStorageData.KillRecord kill : batch)
                {
                    String bossName = killToBossName.get(String.valueOf(kill.getTimestamp()));
                    batchByBoss.computeIfAbsent(bossName, k -> new ArrayList<>()).add(kill);
                }

                log.info("Uploading batch {}/{} ({} kills)",
                        (i / BATCH_SIZE) + 1,
                        (allKills.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                        batch.size());

                boolean success = apiClient.bulkSyncKills(username, batchByBoss);

                if (success)
                {
                    // Mark this batch as synced
                    for (Map.Entry<String, List<LootStorageData.KillRecord>> entry : batchByBoss.entrySet())
                    {
                        String npcName = entry.getKey();
                        List<LootStorageData.KillRecord> kills = entry.getValue();

                        if (!kills.isEmpty())
                        {
                            long minTs = kills.stream().mapToLong(LootStorageData.KillRecord::getTimestamp).min().orElse(0);
                            long maxTs = kills.stream().mapToLong(LootStorageData.KillRecord::getTimestamp).max().orElse(Long.MAX_VALUE);
                            storageManager.markKillsSynced(npcName, minTs, maxTs);
                        }
                    }
                }
                else
                {
                    log.error("Failed to upload batch, stopping");
                    break;
                }

                // Small delay between batches to avoid overwhelming the server
                if (i + BATCH_SIZE < allKills.size())
                {
                    Thread.sleep(500);
                }
            }

            log.info("Successfully uploaded kills in batches");
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
     * Load only local data for a user without syncing
     */
    public void loadLocalDataForUser(String username) {
        log.info("Loading local loot data for: {}", username);

        // Load from storage
        LootStorageData storageData = storageManager.loadLootData(username);

        if (storageData == null || storageData.getBossKills().isEmpty()) {
            log.info("No local data found for {}", username);
            clientThread.invokeLater(() -> {
                bossKillStats.clear();
                if (panel != null) {
                    panel.refreshDisplay();
                }
            });
            return;
        }

        log.info("Loading stored loot data - {} bosses", storageData.getBossKills().size());

        // Convert and display
        clientThread.invokeLater(() -> {
            refreshLootDisplay();
        });
    }

    /**
     * Manual sync button action - bidirectional sync
     * ONLY way to sync with server
     */
    public void performManualSync(String username) {
        if (username == null || username.isEmpty()) {
            log.warn("Cannot sync: no username provided");
            return;
        }

        log.info("=== MANUAL SYNC STARTED ===");
        log.info("Username: {}", username);

        executorService.submit(() -> {
            try {
                // ENABLE sync for this manual operation ONLY
                allowSync = true;

                // Step 1: Download from server
                log.info("Step 1: Downloading kill history from server");
                downloadKillHistoryFromServer();

                // Step 2: Upload unsynced local kills
                log.info("Step 2: Uploading unsynced local kills");
                uploadUnsyncedKills();

                // Step 3: Refresh display with merged data
                log.info("Step 3: Refreshing display");
                clientThread.invokeLater(this::refreshLootDisplay);

                log.info("=== MANUAL SYNC COMPLETED ===");

                // Notify user
                clientThread.invokeLater(() -> {
                    if (panel != null) {
                        panel.showSyncCompleted();
                    }
                });

            } catch (Exception e) {
                log.error("Manual sync failed", e);
                clientThread.invokeLater(() -> {
                    if (panel != null) {
                        panel.showSyncFailed(e.getMessage());
                    }
                });
            } finally {
                // DISABLE sync flag after manual operation completes
                allowSync = false;
            }
        });
    }

    /**
     * DEPRECATED: Auto-sync on startup is disabled
     * Use performManualSync() instead
     */
    public void syncWithServerOnStartup()
    {
        log.warn("syncWithServerOnStartup() called but automatic sync is DISABLED");
        log.warn("Use manual sync button instead");
        // Do nothing - automatic sync disabled
    }

    // ==================== DATA MANAGEMENT ====================

    /**
     * Load data from storage on startup
     */
    public void loadFromStorage()
    {
        LootStorageData data = storageManager.loadData();
        if (data == null || data.getBossKills().isEmpty())
        {
            log.info("No stored loot data found");
            return;
        }

        log.info("Loading stored loot data - {} bosses", data.getBossKills().size());
        refreshLootDisplay();
    }

    /**
     * Refresh display from storage data - converts to BossKillStats with full kill history
     */
    private void refreshLootDisplay()
    {
        LootStorageData data = storageManager.getCurrentData();

        log.info("=== REFRESH DISPLAY DEBUG ===");

        if (data == null || data.getBossKills().isEmpty())
        {
            log.info("No boss kills data to display");
            bossKillStats.clear();
            if (panel != null)
            {
                clientThread.invokeLater(() -> panel.refreshDisplay());
            }
            return;
        }

        log.info("Boss kills count: {}", data.getBossKills().size());

        // Convert storage data to BossKillStats with kill history
        // CLEAR FIRST to prevent duplicates
        bossKillStats.clear();

        log.info("Cleared bossKillStats map - starting fresh");

        for (Map.Entry<String, LootStorageData.BossKillData> entry : data.getBossKills().entrySet())
        {
            String bossName = entry.getKey();
            LootStorageData.BossKillData bossData = entry.getValue();

            // Skip if already exists (safety check)
            if (bossKillStats.containsKey(bossName))
            {
                log.warn("DUPLICATE DETECTED: Boss {} already in map - skipping", bossName);
                continue;
            }

            log.info("Processing boss: {}, KC: {}", bossName, bossData.getKillCount());

            BossKillStats stats = new BossKillStats(bossData.getNpcName(), bossData.getNpcId());
            stats.setKillCount(bossData.getKillCount());
            stats.setPrestige(bossData.getPrestige());
            stats.setTotalLootValue(bossData.getTotalLootValue());

            // Convert kill records to populate killHistory
            List<LootStorageData.KillRecord> killRecords = bossData.getKills();

            if (killRecords != null && !killRecords.isEmpty())
            {
                log.info("Found {} kill records for {}", killRecords.size(), bossName);

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

                    // Convert drops
                    for (LootStorageData.DropRecord drop : killRecord.getDrops())
                    {
                        LootDrop lootDrop = new LootDrop(
                                drop.getItemId(),
                                drop.getItemName(),
                                drop.getQuantity(),
                                drop.getGePrice(),
                                drop.getHighAlch()
                        );
                        npcKill.addDrop(lootDrop);
                    }

                    stats.addKill(npcKill);
                }
            }
            else
            {
                log.warn("No kill records for {}, using aggregated data only", bossName);
            }

            log.info("Added stats for {} with {} aggregated drops",
                    bossName, stats.getAggregatedDrops().size());

            bossKillStats.put(stats.getNpcName(), stats);
        }

        log.info("Total bosses in memory: {}", bossKillStats.size());
        log.info("Boss names: {}", bossKillStats.keySet());
        log.info("=== END REFRESH DISPLAY DEBUG ===");

        // Update panel
        if (panel != null)
        {
            clientThread.invokeLater(() -> {
                log.info("Triggering panel refresh");
                panel.refreshDisplay();
            });
        }
    }

    public void onAccountChanged(String newUsername)
    {
        log.info("Account changed to: {}", newUsername);
        bossKillStats.clear();
        hasAttemptedSync = false;
        loadFromStorage();

        if (panel != null)
        {
            SwingUtilities.invokeLater(() -> panel.refreshDisplay());
        }
    }

    public List<BossKillStats> getAllBossStats()
    {
        return new ArrayList<>(bossKillStats.values());
    }

    public Map<String, BossKillStats> getBossKillStats()
    {
        return Collections.unmodifiableMap(bossKillStats);
    }

    public void clearAllData()
    {
        bossKillStats.clear();
        hiddenDrops.clear();
        lastKillTimestamp.clear();
        storageManager.clearData();

        if (panel != null)
        {
            clientThread.invokeLater(() -> panel.refreshDisplay());
        }

        notifyDataRefresh();
    }

    public void clearBossData(String npcName)
    {
        bossKillStats.remove(npcName);
        hiddenDrops.remove(npcName);
        lastKillTimestamp.remove(npcName);
        storageManager.saveData();

        if (panel != null)
        {
            clientThread.invokeLater(() -> panel.refreshDisplay());
        }
    }

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
     * Check if an item drop is hidden
     */
    public boolean isDropHidden(String npcName, int itemId)
    {
        Set<Integer> hidden = hiddenDrops.get(npcName);
        return hidden != null && hidden.contains(itemId);
    }

    /**
     * Hide a drop for an NPC
     */
    public void hideDropForNpc(String npcName, int itemId)
    {
        hiddenDrops.computeIfAbsent(npcName, k -> new HashSet<>()).add(itemId);
        storageManager.saveData();
    }

    /**
     * Unhide a drop for an NPC
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

    public boolean isBoss(int npcId, String npcName)
    {
        return TRACKED_BOSS_IDS.contains(npcId) ||
                (npcName != null && isBossName(npcName));
    }

    private boolean isBossName(String name)
    {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.contains("duke") || lower.contains("leviathan") ||
                lower.contains("vardorvis") || lower.contains("whisperer") ||
                lower.contains("zulrah") || lower.contains("vorkath") ||
                lower.contains("cerberus") || lower.contains("nightmare") ||
                lower.contains("gauntlet") || lower.contains("barrows");
    }

    public String normalizeBossName(String name)
    {
        if (name == null || name.isEmpty()) return "Unknown";
        String lower = name.toLowerCase();

        if (lower.contains("corrupted gauntlet")) return "Corrupted Gauntlet";
        if (lower.contains("gauntlet")) return "The Gauntlet";
        if (lower.contains("barrows")) return "Barrows";
        if (lower.contains("chambers of xeric")) return "Chambers of Xeric";
        if (lower.contains("theatre of blood")) return "Theatre of Blood";
        if (lower.contains("tombs of amascut")) return "Tombs of Amascut";
        if (lower.contains("duke") || lower.contains("sucellus")) return "Duke Sucellus";
        if (lower.contains("leviathan")) return "The Leviathan";
        if (lower.contains("vardorvis")) return "Vardorvis";
        if (lower.contains("whisperer")) return "The Whisperer";
        if (lower.contains("zulrah")) return "Zulrah";
        if (lower.contains("vorkath")) return "Vorkath";
        if (lower.contains("cerberus")) return "Cerberus";
        if (lower.contains("hydra")) return "Alchemical Hydra";
        if (lower.contains("graardor")) return "General Graardor";
        if (lower.contains("zilyana")) return "Commander Zilyana";
        if (lower.contains("kree")) return "Kree'arra";
        if (lower.contains("kril")) return "K'ril Tsutsaroth";

        return name.trim();
    }

    public void parseKillCountMessage(String message)
    {
        Matcher matcher = KC_PATTERN.matcher(message);
        if (matcher.find())
        {
            String bossName = normalizeBossName(matcher.group(1));
            int kc = Integer.parseInt(matcher.group(2));
            log.debug("Parsed KC from chat: {} = {}", bossName, kc);
        }
    }

    public Integer getBossIdFromName(String bossName)
    {
        return BOSS_NAME_TO_ID.get(normalizeBossName(bossName));
    }

    // ==================== LISTENERS ====================

    public void addListener(LootTrackerUpdateListener listener)
    {
        listeners.add(listener);
    }

    private void notifyKillRecorded(BossKillStats stats)
    {
        for (LootTrackerUpdateListener listener : listeners)
        {
            listener.onDataRefresh();
        }
    }

    private void notifyDataRefresh()
    {
        for (LootTrackerUpdateListener listener : listeners)
        {
            listener.onDataRefresh();
        }
    }
}