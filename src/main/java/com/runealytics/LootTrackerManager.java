package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.client.game.ItemManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * Manages loot tracking, storage, and syncing
 */
@Singleton
public class LootTrackerManager
{
    private static final Logger log = LoggerFactory.getLogger(LootTrackerManager.class);

    private final Client client;
    private final LootTrackerApiClient apiClient;
    private final LootStorageManager storageManager;
    private final ScheduledExecutorService executorService;
    private final ItemManager itemManager;
    private final Map<String, BossKillStats> bossStats = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> hiddenDrops = new ConcurrentHashMap<>();

    private int currentPrestige = 0;

    // Listeners for UI updates
    private final List<LootTrackerUpdateListener> listeners = new ArrayList<>();

    @Inject
    public LootTrackerManager(
            Client client,
            LootTrackerApiClient apiClient,
            LootStorageManager storageManager,
            ScheduledExecutorService executorService,
            ItemManager itemManager
    )
    {
        this.client = client;
        this.apiClient = apiClient;
        this.storageManager = storageManager;
        this.executorService = executorService;
        this.itemManager = itemManager;
    }

    /**
     * Initialize manager - load from local storage and sync with server
     */
    public void initialize(String username)
    {
        // Load from local storage first
        loadFromStorage();

        // Then sync with server if needed
        syncFromServer(username);
    }

    /**
     * Load loot data from local storage
     */
    public void loadFromStorage()
    {
        LootStorageManager.LootStorageData data = storageManager.loadLootData();

        if (data != null)
        {
            bossStats.clear();
            bossStats.putAll(data.bossStats);
            currentPrestige = data.currentPrestige;

            log.info("Loaded {} boss records from local storage (Prestige: {})",
                    bossStats.size(), currentPrestige);

            notifyListenersRefresh();
        }
    }

    /**
     * Save loot data to local storage
     */
    public void saveToStorage()
    {
        storageManager.saveLootData(bossStats, currentPrestige);
    }

    /**
     * Sync from server (pull existing data if local is empty)
     */
    private void syncFromServer(String username)
    {
        if (username == null || username.isEmpty())
        {
            return;
        }

        // Only sync if we have no local data
        if (bossStats.isEmpty())
        {
            executorService.submit(() -> {
                try
                {
                    apiClient.fetchBossStats(username, stats -> {
                        // Update local stats from server
                        for (BossKillStats serverStat : stats)
                        {
                            bossStats.put(serverStat.getNpcName(), serverStat);
                        }

                        log.info("Synced {} boss records from server", stats.size());
                        notifyListenersRefresh();
                        saveToStorage();
                    });
                }
                catch (Exception e)
                {
                    log.error("Failed to sync from server", e);
                }
            });
        }
    }

    /**
     * Register a listener for loot updates
     */
    public void addListener(LootTrackerUpdateListener listener)
    {
        listeners.add(listener);
    }

    /**
     * Remove a listener
     */
    public void removeListener(LootTrackerUpdateListener listener)
    {
        listeners.remove(listener);
    }

    /**
     * Record an NPC kill with drops
     */
    public void recordKill(NPC npc, Collection<ItemStack> items)
    {
        if (npc == null || npc.getName() == null)
        {
            return;
        }

        String npcName = npc.getName();
        int npcId = npc.getId();
        int combatLevel = npc.getCombatLevel();
        int world = client.getWorld();

        NpcKillRecord killRecord = new NpcKillRecord(npcName, npcId, combatLevel, world);

        // Process each dropped item
        for (ItemStack item : items)
        {
            ItemComposition itemComp = client.getItemDefinition(item.getId());

            if (itemComp == null)
            {
                continue;
            }

            String itemName = itemComp.getName();
            int gePrice = itemManager.getItemPrice(item.getId());
            int highAlch = itemComp.getHaPrice();

            LootDrop drop = new LootDrop(
                    item.getId(),
                    itemName,
                    item.getQuantity(),
                    gePrice,
                    highAlch
            );

            killRecord.addDrop(drop);
        }

        // Update boss stats
        BossKillStats stats = bossStats.computeIfAbsent(
                npcName,
                name -> {
                    BossKillStats newStats = new BossKillStats(name, npcId);
                    newStats.setCurrentPrestige(currentPrestige);
                    return newStats;
                }
        );
        stats.addKill(killRecord);

        log.info("Recorded kill: {} (KC: {}, Prestige: {}), Loot value: {}gp",
                npcName, stats.getKillCount(), currentPrestige, killRecord.getTotalValue());

        // Save to local storage
        saveToStorage();

        // Notify listeners
        notifyListeners(killRecord, stats);

        // Sync to server asynchronously
        syncKillToServerAsync(killRecord, stats);
    }

    /**
     * Prestige a specific boss - reset stats but keep server history
     */
    public void prestigeBoss(String npcName)
    {
        BossKillStats stats = bossStats.get(npcName);
        if (stats == null)
        {
            return;
        }

        int oldKills = stats.getKillCount();
        long oldValue = stats.getTotalLootValue();

        // Submit prestige to server first
        syncPrestigeToServer(npcName, stats);

        // Reset local stats
        stats.prestige();

        log.info("Prestiged {}: {} kills, {} gp → Prestige {}",
                npcName, oldKills, oldValue, stats.getCurrentPrestige());

        // Save to storage
        saveToStorage();

        // Notify listeners
        notifyListenersRefresh();
    }

    /**
     * Get current prestige level
     */
    public int getCurrentPrestige()
    {
        return currentPrestige;
    }

    /**
     * Hide a specific drop for a specific NPC
     */
    public void hideDropForNpc(String npcName, int itemId)
    {
        Set<Integer> hidden = hiddenDrops.computeIfAbsent(npcName, k -> new HashSet<>());
        hidden.add(itemId);
        saveToStorage();
        log.debug("Hidden item {} for NPC {}", itemId, npcName);
    }

    /**
     * Unhide a drop
     */
    public void unhideDropForNpc(String npcName, int itemId)
    {
        Set<Integer> hidden = hiddenDrops.get(npcName);
        if (hidden != null)
        {
            hidden.remove(itemId);
            saveToStorage();
        }
    }

    /**
     * Check if a drop is hidden for an NPC
     */
    public boolean isDropHidden(String npcName, int itemId)
    {
        Set<Integer> hidden = hiddenDrops.get(npcName);
        return hidden != null && hidden.contains(itemId);
    }

    /**
     * Get stats for a specific boss
     */
    public BossKillStats getBossStats(String npcName)
    {
        return bossStats.get(npcName);
    }

    /**
     * Get all tracked bosses sorted by total value
     */
    public List<BossKillStats> getAllBossStats()
    {
        return bossStats.values().stream()
                .sorted(Comparator.comparingLong(BossKillStats::getTotalLootValue).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Clear all tracked data
     */
    public void clearAllData()
    {
        bossStats.clear();
        hiddenDrops.clear();
        storageManager.clearStorage();
        log.info("Cleared all loot tracking data");
    }

    /**
     * Clear data for specific boss
     */
    public void clearBossData(String npcName)
    {
        bossStats.remove(npcName);
        hiddenDrops.remove(npcName);
        saveToStorage();
        log.info("Cleared data for {}", npcName);
    }

    /**
     * Notify all listeners of new kill
     */
    private void notifyListeners(NpcKillRecord kill, BossKillStats stats)
    {
        for (LootTrackerUpdateListener listener : listeners)
        {
            try
            {
                listener.onKillRecorded(kill, stats);
            }
            catch (Exception e)
            {
                log.error("Error notifying listener", e);
            }
        }
    }

    /**
     * Notify listeners to refresh display
     */
    private void notifyListenersRefresh()
    {
        for (LootTrackerUpdateListener listener : listeners)
        {
            try
            {
                listener.onDataRefresh();
            }
            catch (Exception e)
            {
                log.error("Error notifying listener", e);
            }
        }
    }

    /**
     * Sync kill data to RuneAlytics server asynchronously
     */
    private void syncKillToServerAsync(NpcKillRecord kill, BossKillStats stats)
    {
        String username = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName()
                : null;

        if (username == null || username.isEmpty())
        {
            log.warn("Cannot sync - no username available");
            return;
        }

        // Build payload on client thread (fast operation)
        JsonObject payload = buildKillPayload(username, kill, stats);

        // Submit network call to executor service (async)
        executorService.submit(() -> {
            try
            {
                apiClient.syncKillData(payload);
                log.debug("Loot data synced to server for {}", kill.getNpcName());
            }
            catch (Exception e)
            {
                log.error("Failed to sync loot data to server", e);
            }
        });
    }

    /**
     * Sync prestige to server
     */
    private void syncPrestigeToServer(String npcName, BossKillStats stats)
    {
        String username = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName()
                : null;

        if (username == null || username.isEmpty())
        {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("npc_name", npcName);
        payload.addProperty("prestige", stats.getCurrentPrestige() + 1);
        payload.addProperty("final_kill_count", stats.getKillCount());
        payload.addProperty("final_loot_value", stats.getTotalLootValue());

        executorService.submit(() -> {
            try
            {
                apiClient.syncPrestige(payload);
                log.info("Synced prestige for {} to server", npcName);
            }
            catch (Exception e)
            {
                log.error("Failed to sync prestige", e);
            }
        });
    }

    /**
     * Build JSON payload for kill sync
     */
    private JsonObject buildKillPayload(String username, NpcKillRecord kill, BossKillStats stats)
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("npc_name", kill.getNpcName());
        payload.addProperty("npc_id", kill.getNpcId());
        payload.addProperty("combat_level", kill.getCombatLevel());
        payload.addProperty("kill_count", stats.getKillCount());
        payload.addProperty("world", kill.getWorldNumber());
        payload.addProperty("timestamp", kill.getKillTime().getEpochSecond());
        payload.addProperty("prestige", stats.getCurrentPrestige());

        // Add drops
        JsonArray dropsArray = new JsonArray();
        for (LootDrop drop : kill.getDrops())
        {
            JsonObject dropObj = new JsonObject();
            dropObj.addProperty("item_id", drop.getItemId());
            dropObj.addProperty("item_name", drop.getItemName());
            dropObj.addProperty("quantity", drop.getQuantity());
            dropObj.addProperty("ge_price", drop.getGePrice());
            dropObj.addProperty("high_alch", drop.getHighAlchValue());
            dropObj.addProperty("total_value", drop.getTotalValue());
            dropObj.addProperty("hidden", drop.isHidden());
            dropsArray.add(dropObj);
        }
        payload.add("drops", dropsArray);

        // Summary
        payload.addProperty("total_loot_value", kill.getTotalValue());
        payload.addProperty("drop_count", kill.getVisibleDropCount());

        return payload;
    }
}