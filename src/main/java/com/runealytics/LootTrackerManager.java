package com.runealytics;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.TileItem;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class LootTrackerManager
{
    private static final Pattern KC_PATTERN = Pattern.compile("Your (.+) kill count is: (\\d+)");

    private final Client client;
    private final ItemManager itemManager;
    private final RunealyticsConfig config;
    private final RuneAlyticsState state;
    private final LootStorageManager storageManager;
    private final LootTrackerApiClient apiClient;
    private final ScheduledExecutorService executorService;

    private LootTrackerPanel panel;
    private boolean hasAttemptedSync = false;

    private final Map<String, BossKillStats> bossStats = new ConcurrentHashMap<>();
    private final List<LootTrackerUpdateListener> listeners = new ArrayList<>();
    private final Map<String, Set<Integer>> hiddenDrops = new HashMap<>();
    private final Queue<NpcKillRecord> pendingSync = new LinkedList<>();

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
            .build();

    @Inject
    public LootTrackerManager(
            Client client,
            RunealyticsConfig config,
            RuneAlyticsState state,
            LootStorageManager storageManager,
            LootTrackerApiClient apiClient,
            ItemManager itemManager,
            ScheduledExecutorService executorService
    )
    {
        this.client = client;
        this.config = config;
        this.state = state;
        this.storageManager = storageManager;
        this.apiClient = apiClient;
        this.itemManager = itemManager;
        this.executorService = executorService;
    }

    /**
     * Set the panel reference for UI updates
     */
    public void setPanel(LootTrackerPanel panel)
    {
        this.panel = panel;
        log.info("Panel reference set");
    }

    /**
     * Initialize the loot tracker
     */
    public void initialize()
    {
        log.info("Initializing LootTrackerManager");

        // Load local data first
        loadFromStorage();
        hasAttemptedSync = false;

        log.info("Boss stats loaded: {}", bossStats.size());
        for (Map.Entry<String, BossKillStats> entry : bossStats.entrySet())
        {
            BossKillStats stats = entry.getValue();
            log.info("  - {}: {} kills, {} history records, {} gp",
                    stats.getNpcName(),
                    stats.getKillCount(),
                    stats.getKillHistory().size(),
                    stats.getTotalLootValue());
        }

        log.info("LootTrackerManager initialized - sync will run after login");
    }

    /**
     * Shutdown and save data
     */
    public void shutdown()
    {
        log.info("Shutting down LootTrackerManager - saving data for {}", state.getVerifiedUsername());
        saveToStorage();
    }

    /**
     * Called when player logs in and verification is complete
     */
    public void onPlayerLoggedInAndVerified()
    {
        if (hasAttemptedSync)
        {
            log.info("Sync already attempted this session, skipping");
            return;
        }

        if (!config.syncLootToServer())
        {
            log.info("Server sync disabled in config");
            hasAttemptedSync = true;
            return;
        }

        if (!state.isVerified())
        {
            log.warn("Player not verified, cannot sync");
            hasAttemptedSync = true;
            return;
        }

        log.info("Player logged in and verified - starting loot sync...");
        syncWithServerOnStartup();
        hasAttemptedSync = true;
    }

    /**
     * Called when account changes - reload data for new account
     */
    public void onAccountChanged(String newUsername)
    {
        log.info("Account changed to: {}", newUsername);

        // Clear current data
        bossStats.clear();

        // Reload for new account
        loadFromStorage();

        // Notify panel to refresh
        if (panel != null)
        {
            SwingUtilities.invokeLater(() -> panel.refreshDisplay());
        }
    }

    private void syncWithServerOnStartup()
    {
        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty())
        {
            log.warn("No verified username, cannot sync with server");
            return;
        }

        try
        {
            log.info("Fetching boss stats from server for: {}", username);
            Map<String, LootTrackerApiClient.ServerBossStats> serverStats =
                    apiClient.fetchBossStatsFromServer(username);

            int localTotalKills = bossStats.values().stream()
                    .mapToInt(BossKillStats::getKillCount)
                    .sum();

            log.info("Local data: {} bosses, {} total kills",
                    bossStats.size(), localTotalKills);

            // If local is empty but server has data, download from server
            if (bossStats.isEmpty() && !serverStats.isEmpty())
            {
                log.info("Local storage empty, downloading {} bosses from server", serverStats.size());
                downloadKillHistoryFromServer(username);
                saveToStorage(); // Save downloaded data

                // Notify panel to refresh
                if (panel != null)
                {
                    SwingUtilities.invokeLater(() -> panel.refreshDisplay());
                }
                return;
            }

            if (serverStats.isEmpty())
            {
                log.info("No server data found - uploading all local data");

                if (localTotalKills > 0)
                {
                    uploadAllLocalDataToServer(username);
                }
                return;
            }

            int serverTotalKills = serverStats.values().stream()
                    .mapToInt(s -> s.killCount)
                    .sum();

            log.info("Server data: {} bosses, {} total kills",
                    serverStats.size(), serverTotalKills);

            boolean needsSync = false;

            // Check for missing data on either side
            for (Map.Entry<String, LootTrackerApiClient.ServerBossStats> serverEntry : serverStats.entrySet())
            {
                String bossName = serverEntry.getKey();
                LootTrackerApiClient.ServerBossStats serverStat = serverEntry.getValue();
                BossKillStats localStat = bossStats.get(bossName);

                if (localStat == null)
                {
                    log.warn("Server has {} but local does not ({} kills) - will download",
                            bossName, serverStat.killCount);
                    needsSync = true;
                }
                else if (localStat.getKillCount() < serverStat.killCount)
                {
                    log.warn("Server has more kills for {}: Server {} vs Local {}",
                            bossName, serverStat.killCount, localStat.getKillCount());
                    needsSync = true;
                }
                else if (localStat.getKillCount() > serverStat.killCount)
                {
                    log.warn("Local has more kills for {}: Local {} vs Server {}",
                            bossName, localStat.getKillCount(), serverStat.killCount);
                    needsSync = true;
                }
            }

            if (needsSync)
            {
                // Download missing data from server
                log.info("Syncing data from server...");
                downloadKillHistoryFromServer(username);

                // Then upload any local data that's newer
                uploadAllLocalDataToServer(username);

                saveToStorage();

                // Notify panel to refresh
                if (panel != null)
                {
                    SwingUtilities.invokeLater(() -> panel.refreshDisplay());
                }
            }
            else
            {
                log.info("✓ Local and server data synchronized");
            }

        }
        catch (IOException e)
        {
            log.error("Failed to sync with server on startup", e);
        }
    }

    /**
     * Download kill history from server and merge with local data
     */
    private void downloadKillHistoryFromServer(String username)
    {
        try
        {
            Map<String, List<NpcKillRecord>> serverKillHistory =
                    apiClient.fetchKillHistoryFromServer(username);

            for (Map.Entry<String, List<NpcKillRecord>> entry : serverKillHistory.entrySet())
            {
                String npcName = entry.getKey();
                List<NpcKillRecord> kills = entry.getValue();

                if (kills.isEmpty())
                {
                    continue;
                }

                // Get first kill to determine NPC ID
                NpcKillRecord firstKill = kills.get(0);

                BossKillStats stats = bossStats.computeIfAbsent(
                        npcName,
                        name -> new BossKillStats(name, firstKill.getNpcId())
                );

                // Add each kill from server
                for (NpcKillRecord kill : kills)
                {
                    // Check if we already have this kill (by timestamp)
                    boolean alreadyExists = stats.getKillHistory().stream()
                            .anyMatch(existing -> Math.abs(existing.getTimestamp() - kill.getTimestamp()) < 1000);

                    if (!alreadyExists)
                    {
                        stats.addKill(kill);
                    }
                }

                log.info("Downloaded {} kill records for {}", kills.size(), npcName);
            }
        }
        catch (IOException e)
        {
            log.error("Failed to download kill history from server", e);
        }
    }

    /**
     * Upload all local loot data to server
     */
    private void uploadAllLocalDataToServer(String username)
    {
        try
        {
            int totalKills = bossStats.values().stream()
                    .mapToInt(stats -> stats.getKillHistory().size())
                    .sum();

            log.info("Starting bulk upload of {} kill records from {} bosses...",
                    totalKills, bossStats.size());

            for (Map.Entry<String, BossKillStats> entry : bossStats.entrySet())
            {
                BossKillStats stats = entry.getValue();
                log.info("  - {}: {} kill records (KC: {}, Value: {} gp)",
                        stats.getNpcName(),
                        stats.getKillHistory().size(),
                        stats.getKillCount(),
                        stats.getTotalLootValue());
            }

            apiClient.bulkSyncAllLoot(username, bossStats);
            log.info("✓ Bulk upload completed successfully ({} kills synced)", totalKills);
        }
        catch (IOException e)
        {
            log.error("Failed to bulk upload loot data to server", e);
        }
    }

    /**
     * Process NPC loot
     */
    public void processNpcLoot(NPC npc, List<ItemStack> items)
    {
        log.info(">>> processNpcLoot called for NPC: {} (ID: {})", npc.getName(), npc.getId());
        processLoot(npc.getName(), npc.getId(), npc.getCombatLevel(), client.getWorld(), items, "NPC");
    }

    /**
     * Process player loot (PvP)
     */
    public void processPlayerLoot(String playerName, List<ItemStack> items)
    {
        log.info(">>> processPlayerLoot called for player: {}", playerName);
        processLoot(playerName, 0, 0, client.getWorld(), items, "PLAYER");
    }

    /**
     * Process generic loot (chests, clues, etc.)
     */
    public void processGenericLoot(String source, String type, List<ItemStack> items)
    {
        log.info(">>> processGenericLoot called for source: {} (type: {})", source, type);
        processLoot(source, 0, 0, client.getWorld(), items, type);
    }

    /**
     * Unified loot processing
     */
    private void processLoot(String sourceName, int sourceId, int combatLevel, int world, List<ItemStack> items, String lootType)
    {
        log.info(">>> processLoot: source={}, id={}, combat={}, world={}, items={}, type={}",
                sourceName, sourceId, combatLevel, world, items.size(), lootType);

        if (!config.enableLootTracking())
        {
            log.warn("Loot tracking is disabled, exiting");
            return;
        }

        String normalizedName = normalizeBossName(sourceName);
        log.info(">>> Normalized name: {}", normalizedName);

        NpcKillRecord kill = new NpcKillRecord(normalizedName, sourceId, combatLevel, world);

        int dropsAdded = 0;
        for (ItemStack item : items)
        {
            long gePrice = itemManager.getItemPrice(item.getId());
            int highAlch = itemManager.getItemComposition(item.getId()).getHaPrice();
            String itemName = itemManager.getItemComposition(item.getId()).getName();
            long totalValue = gePrice * item.getQuantity();

            log.info(">>> Item: {} (ID: {}), Qty: {}, GE: {}, Total: {}",
                    itemName, item.getId(), item.getQuantity(), gePrice, totalValue);

            if (totalValue < config.minimumLootValue())
            {
                log.info(">>> Item value {} below threshold {}, skipping", totalValue, config.minimumLootValue());
                continue;
            }

            LootDrop drop = new LootDrop(item.getId(), itemName, item.getQuantity(), gePrice, highAlch);
            kill.addDrop(drop);
            dropsAdded++;
            log.info(">>> Added drop #{}", dropsAdded);
        }

        if (kill.getDrops().isEmpty())
        {
            log.warn(">>> No drops to record for {} (all items below threshold)", normalizedName);
            return;
        }

        log.info(">>> Recording kill with {} drops", kill.getDrops().size());
        addKill(kill);

        BossKillStats stats = bossStats.get(normalizedName);
        log.info(">>> Notifying {} listeners", listeners.size());
        notifyKillRecorded(kill, stats);
        log.info(">>> Recorded {} kill: {} items, {} gp", normalizedName, kill.getDrops().size(), kill.getTotalValue());
    }

    /**
     * Add a kill to statistics
     */
    public void addKill(NpcKillRecord kill)
    {
        BossKillStats stats = bossStats.computeIfAbsent(
                kill.getNpcName(),
                name -> new BossKillStats(name, kill.getNpcId())
        );

        stats.addKill(kill);
        log.info("Added kill for {}: KC now {}", kill.getNpcName(), stats.getKillCount());

        // Save to local storage
        saveToStorage();

        // Notify panel to update UI
        if (panel != null)
        {
            SwingUtilities.invokeLater(() -> panel.onKillAdded(stats));
        }

        // Sync to server if enabled
        if (config.syncLootToServer() && state.isVerified())
        {
            executorService.submit(() -> {
                try
                {
                    syncKillToServer(kill);
                }
                catch (Exception e)
                {
                    log.error("Failed to sync kill to server", e);
                }
            });
        }
    }

    /**
     * Sync a single kill to the server
     */
    private void syncKillToServer(NpcKillRecord kill)
    {
        try
        {
            JsonObject payload = apiClient.buildKillPayload(kill);
            if (payload != null)
            {
                apiClient.syncKillData(payload);
                log.debug("Synced kill to server: {}", kill.getNpcName());
            }
        }
        catch (IOException e)
        {
            log.error("Failed to sync kill to server", e);
            pendingSync.offer(kill);
        }
    }

    /**
     * Load data from storage
     */
    private void loadFromStorage()
    {
        LootStorageManager.LootStorageData data = storageManager.loadLootData();
        if (data != null && data.bossStats != null)
        {
            bossStats.putAll(data.bossStats);
            log.info("Loaded {} boss stats from storage", bossStats.size());
        }
    }

    /**
     * Save data to storage
     */
    private void saveToStorage()
    {
        storageManager.saveLootData(bossStats, 0);
    }

    /**
     * Get all boss statistics
     */
    public List<BossKillStats> getAllBossStats()
    {
        return new ArrayList<>(bossStats.values());
    }

    /**
     * Clear all data
     */
    public void clearAllData()
    {
        bossStats.clear();
        hiddenDrops.clear();
        pendingSync.clear();
        saveToStorage();

        for (LootTrackerUpdateListener listener : listeners)
        {
            listener.onDataRefresh();
        }
    }

    /**
     * Prestige a boss
     */
    public void prestigeBoss(String npcName)
    {
        BossKillStats stats = bossStats.get(npcName);
        if (stats != null)
        {
            stats.prestige();
            saveToStorage();
        }
    }

    /**
     * Clear data for a specific boss
     */
    public void clearBossData(String npcName)
    {
        bossStats.remove(npcName);
        hiddenDrops.remove(npcName);
        saveToStorage();
    }

    /**
     * Add an update listener
     */
    public void addListener(LootTrackerUpdateListener listener)
    {
        listeners.add(listener);
    }

    /**
     * Notify listeners of a kill
     */
    private void notifyKillRecorded(NpcKillRecord kill, BossKillStats stats)
    {
        for (LootTrackerUpdateListener listener : listeners)
        {
            listener.onKillRecorded(kill, stats);
        }

        for (LootTrackerUpdateListener listener : listeners)
        {
            listener.onDataRefresh();
        }
    }

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
        saveToStorage();
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
            saveToStorage();
        }
    }

    /**
     * Check if NPC is a boss
     */
    public boolean isBoss(int npcId, String npcName)
    {
        if (TRACKED_BOSS_IDS.contains(npcId))
        {
            return true;
        }

        if (npcName != null)
        {
            return isBossName(npcName);
        }

        return false;
    }

    /**
     * Check if name is a boss name
     */
    private boolean isBossName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }

        String lowerName = name.toLowerCase();

        return lowerName.contains("duke") || lowerName.contains("sucellus") ||
                lowerName.contains("leviathan") || lowerName.contains("vardorvis") ||
                lowerName.contains("whisperer") || lowerName.contains("zulrah") ||
                lowerName.contains("vorkath") || lowerName.contains("cerberus") ||
                lowerName.contains("nightmare") || lowerName.contains("nex") ||
                lowerName.contains("graardor") || lowerName.contains("zilyana") ||
                lowerName.contains("kree") || lowerName.contains("kril") ||
                lowerName.contains("corporeal") || lowerName.contains("kalphite queen") ||
                lowerName.contains("dagannoth") || lowerName.contains("hydra") ||
                lowerName.contains("kraken") || lowerName.contains("mole");
    }

    /**
     * Normalize boss names for consistency
     */
    public String normalizeBossName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return "Unknown";
        }

        String lowerName = name.toLowerCase();

        // Gauntlet
        if (lowerName.contains("corrupted gauntlet")) return "Corrupted Gauntlet";
        if (lowerName.contains("gauntlet")) return "The Gauntlet";

        // Chests and special loot sources
        if (lowerName.contains("barrows chest") || lowerName.contains("barrows")) return "Barrows";
        if (lowerName.contains("crystal chest")) return "Crystal Chest";
        if (lowerName.contains("casket") || lowerName.contains("clue scroll")) return "Clue Scroll";
        if (lowerName.contains("reward chest")) return "Reward Chest";
        if (lowerName.contains("chambers of xeric")) return "Chambers of Xeric";
        if (lowerName.contains("theatre of blood")) return "Theatre of Blood";
        if (lowerName.contains("tombs of amascut")) return "Tombs of Amascut";

        // DT2 Bosses
        if (lowerName.contains("duke") || lowerName.contains("sucellus")) return "Duke Sucellus";
        if (lowerName.contains("leviathan")) return "The Leviathan";
        if (lowerName.contains("vardorvis")) return "Vardorvis";
        if (lowerName.contains("whisperer")) return "The Whisperer";

        // Other bosses
        if (lowerName.contains("zulrah")) return "Zulrah";
        if (lowerName.contains("vorkath")) return "Vorkath";
        if (lowerName.contains("cerberus")) return "Cerberus";
        if (lowerName.contains("nightmare")) return lowerName.contains("phosani") ? "Phosani's Nightmare" : "The Nightmare";
        if (lowerName.contains("nex")) return "Nex";
        if (lowerName.contains("graardor")) return "General Graardor";
        if (lowerName.contains("zilyana")) return "Commander Zilyana";
        if (lowerName.contains("kree")) return "Kree'arra";
        if (lowerName.contains("kril")) return "K'ril Tsutsaroth";
        if (lowerName.contains("corporeal")) return "Corporeal Beast";
        if (lowerName.contains("kalphite")) return "Kalphite Queen";
        if (lowerName.contains("hydra")) return "Alchemical Hydra";

        return name.trim();
    }

    /**
     * Sync any pending loot that failed to sync previously
     */
    public void syncPendingLoot()
    {
        if (pendingSync.isEmpty() || !state.isVerified())
        {
            return;
        }

        if (!config.syncLootToServer())
        {
            return;
        }

        log.info("Syncing {} pending kills to server", pendingSync.size());

        executorService.submit(() -> {
            int synced = 0;
            int failed = 0;

            while (!pendingSync.isEmpty())
            {
                NpcKillRecord kill = pendingSync.poll();
                try
                {
                    syncKillToServer(kill);
                    synced++;
                }
                catch (Exception e)
                {
                    log.error("Failed to sync pending kill", e);
                    failed++;
                    // Don't re-add to queue to avoid infinite loop
                }
            }

            log.info("Pending sync complete: {} synced, {} failed", synced, failed);
        });
    }

    /**
     * Parse kill count message from chat
     */
    public void parseKillCountMessage(String message)
    {
        Matcher matcher = KC_PATTERN.matcher(message);
        if (matcher.find())
        {
            String bossName = normalizeBossName(matcher.group(1));
            int kc = Integer.parseInt(matcher.group(2));
            log.debug("Parsed KC from chat: {} = {}", bossName, kc);

            // Update local KC if we have stats for this boss
            BossKillStats stats = bossStats.get(bossName);
            if (stats != null && kc > stats.getKillCount())
            {
                log.info("Updating {} KC from {} to {} (from chat message)",
                        bossName, stats.getKillCount(), kc);
                // Note: This just logs it. The actual KC is tracked from kill records.
            }
        }
    }

    /**
     * Get boss ID from name
     */
    public Integer getBossIdFromName(String bossName)
    {
        String normalized = normalizeBossName(bossName);
        return BOSS_NAME_TO_ID.get(normalized);
    }
}