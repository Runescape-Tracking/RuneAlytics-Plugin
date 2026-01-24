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
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Gson gson;
    private final OkHttpClient okHttpClient;
    private final LootTrackerApiClient apiClient;
    private final LootStorageManager storageManager;
    private final RuneAlyticsState state;

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
            ItemManager itemManager,
            RunealyticsConfig config,
            Gson gson,
            OkHttpClient okHttpClient,
            LootTrackerApiClient apiClient,
            LootStorageManager storageManager,
            RuneAlyticsState state
    )
    {
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        this.gson = gson;
        this.okHttpClient = okHttpClient;
        this.apiClient = apiClient;
        this.storageManager = storageManager;
        this.state = state;
    }

    public void initialize()
    {
        log.info("Initializing LootTrackerManager");
        loadFromStorage();

        // Debug: Print what was loaded
        log.info("Boss stats loaded: {}", bossStats.size());
        for (Map.Entry<String, BossKillStats> entry : bossStats.entrySet())
        {
            BossKillStats stats = entry.getValue();
            log.info("  - {}: {} kills, {} gp",
                    stats.getNpcName(),
                    stats.getKillCount(),
                    stats.getTotalLootValue());
        }
    }

    public void shutdown()
    {
        log.info("Shutting down LootTrackerManager");
        saveToStorage();
    }

    private void loadFromStorage()
    {
        LootStorageManager.LootStorageData data = storageManager.loadLootData();
        if (data != null && data.bossStats != null)
        {
            bossStats.putAll(data.bossStats);
            log.info("Loaded {} boss stats from storage", bossStats.size());
        }
    }

    private void saveToStorage()
    {
        storageManager.saveLootData(bossStats, 0);
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
            log.info(">>> Minimum value threshold: {}", config.minimumLootValue());

            if (totalValue < config.minimumLootValue())
            {
                log.info(">>> Item value {} below threshold {}, skipping", totalValue, config.minimumLootValue());
                continue;
            }

            LootDrop drop = new LootDrop(
                    item.getId(),
                    itemName,
                    item.getQuantity(),
                    gePrice,
                    highAlch
            );

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
        log.info(">>> Kill added to stats");

        pendingSync.offer(kill);
        log.info(">>> Kill queued for sync");

        BossKillStats stats = bossStats.get(normalizedName);
        log.info(">>> Stats: {} kills, {} gp total",
                stats != null ? stats.getKillCount() : 0,
                stats != null ? stats.getTotalLootValue() : 0);

        log.info(">>> Notifying {} listeners", listeners.size());
        notifyKillRecorded(kill, stats);

        log.info(">>> Recorded {} kill: {} items, {} gp", normalizedName, kill.getDrops().size(), kill.getTotalValue());
    }

    // Legacy method for compatibility
    public void processBossLoot(NPC npc, List<ItemStack> items)
    {
        processNpcLoot(npc, items);
    }

    public void processGroundItem(NPC npc, TileItem item)
    {
        log.debug("Processing ground item: {} x{} near {}", item.getId(), item.getQuantity(), npc.getName());
    }

    public void parseKillCountMessage(String message)
    {
        Matcher matcher = KC_PATTERN.matcher(message);
        if (matcher.find())
        {
            String bossName = normalizeBossName(matcher.group(1));
            int kc = Integer.parseInt(matcher.group(2));
            log.debug("Parsed KC: {} = {}", bossName, kc);
        }
    }

    public void syncPendingLoot()
    {
        if (pendingSync.isEmpty() || !state.canSync())
        {
            return;
        }

        state.startSync();

        try
        {
            while (!pendingSync.isEmpty())
            {
                NpcKillRecord kill = pendingSync.poll();
                syncKillToServer(kill);
            }
        }
        finally
        {
            state.endSync();
        }
    }

    private void syncKillToServer(NpcKillRecord kill)
    {
        try
        {
            JsonObject payload = buildKillPayload(kill);
            apiClient.syncKillData(payload);
            log.debug("Synced kill to server: {}", kill.getNpcName());
        }
        catch (IOException e)
        {
            log.error("Failed to sync kill to server", e);
            pendingSync.offer(kill);
        }
    }

    private JsonObject buildKillPayload(NpcKillRecord kill)
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("npc_name", kill.getNpcName());
        payload.addProperty("npc_id", kill.getNpcId());
        payload.addProperty("combat_level", kill.getCombatLevel());
        payload.addProperty("world", kill.getWorldNumber());
        payload.addProperty("timestamp", kill.getTimestamp());

        JsonArray drops = new JsonArray();
        for (LootDrop drop : kill.getDrops())
        {
            JsonObject dropObj = new JsonObject();
            dropObj.addProperty("item_id", drop.getItemId());
            dropObj.addProperty("item_name", drop.getItemName());
            dropObj.addProperty("quantity", drop.getQuantity());
            dropObj.addProperty("ge_price", drop.getGePrice());
            dropObj.addProperty("high_alch", drop.getHighAlchValue());
            drops.add(dropObj);
        }
        payload.add("drops", drops);

        return payload;
    }

    public List<BossKillStats> getAllBossStats()
    {
        return new ArrayList<>(bossStats.values());
    }

    public boolean isDropHidden(String npcName, int itemId)
    {
        Set<Integer> hidden = hiddenDrops.get(npcName);
        return hidden != null && hidden.contains(itemId);
    }

    public void hideDropForNpc(String npcName, int itemId)
    {
        hiddenDrops.computeIfAbsent(npcName, k -> new HashSet<>()).add(itemId);
        saveToStorage();
    }

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

    public void prestigeBoss(String npcName)
    {
        BossKillStats stats = bossStats.get(npcName);
        if (stats != null)
        {
            stats.prestige();
            saveToStorage();
        }
    }

    public void addKill(NpcKillRecord kill)
    {
        BossKillStats stats = bossStats.computeIfAbsent(
                kill.getNpcName(),
                name -> new BossKillStats(name, kill.getNpcId())
        );
        stats.addKill(kill);
        saveToStorage();
    }

    public void clearBossData(String npcName)
    {
        bossStats.remove(npcName);
        hiddenDrops.remove(npcName);
        saveToStorage();
    }

    public void addListener(LootTrackerUpdateListener listener)
    {
        listeners.add(listener);
    }

    private void notifyKillRecorded(NpcKillRecord kill, BossKillStats stats)
    {
        for (LootTrackerUpdateListener listener : listeners)
        {
            listener.onKillRecorded(kill, stats);
        }

        // Also trigger a general data refresh
        for (LootTrackerUpdateListener listener : listeners)
        {
            listener.onDataRefresh();
        }
    }

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

    public Integer getBossIdFromName(String bossName)
    {
        String normalized = normalizeBossName(bossName);
        return BOSS_NAME_TO_ID.get(normalized);
    }
}