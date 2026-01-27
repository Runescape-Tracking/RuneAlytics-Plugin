package com.runealytics;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks statistics for a specific boss/NPC
 */
@Slf4j
@Getter
@Setter
public class BossKillStats
{
    private String npcName;
    private int npcId;
    private int killCount;
    private int prestige;
    private long totalLootValue;
    private long highestDrop;
    private List<NpcKillRecord> killHistory;
    private Map<Integer, Integer> itemDropCounts; // itemId -> count

    public BossKillStats(String npcName, int npcId)
    {
        this.npcName = npcName;
        this.npcId = npcId;
        this.killCount = 0;
        this.prestige = 0;
        this.totalLootValue = 0;
        this.highestDrop = 0;
        this.killHistory = new ArrayList<>();
        this.itemDropCounts = new HashMap<>();
    }

    /**
     * Add a kill to this boss's statistics
     */
    public void addKill(NpcKillRecord kill)
    {
        if (kill == null)
        {
            log.error("Attempted to add null kill to {}", npcName);
            return;
        }

        killCount++;
        kill.setKillNumber(killCount);
        killHistory.add(kill);

        long lootValue = kill.getTotalValue();
        totalLootValue += lootValue;

        if (lootValue > highestDrop)
        {
            highestDrop = lootValue;
        }

        // Track item drop counts with null safety
        List<LootDrop> killDrops = kill.getDrops();
        if (killDrops != null)
        {
            for (LootDrop drop : killDrops)
            {
                if (drop != null) // Extra safety
                {
                    int itemId = drop.getItemId();
                    itemDropCounts.put(itemId, itemDropCounts.getOrDefault(itemId, 0) + drop.getQuantity());
                }
            }
        }
    }

    /**
     * Reset statistics for prestige
     */
    public void prestige()
    {
        prestige++;
        killCount = 0;
        totalLootValue = 0;
        highestDrop = 0;
        killHistory.clear();
        itemDropCounts.clear();
    }

    /**
     * Get average loot value per kill
     */
    public long getAverageLootPerKill()
    {
        if (killCount == 0)
        {
            return 0;
        }
        return totalLootValue / killCount;
    }

    /**
     * Get total number of unique items dropped
     */
    public int getUniqueItemCount()
    {
        return itemDropCounts.size();
    }

    /**
     * Get quantity of a specific item dropped
     */
    public int getItemQuantity(int itemId)
    {
        return itemDropCounts.getOrDefault(itemId, 0);
    }

    /**
     * Get timestamp of the last kill
     */
    public long getLastKillTimestamp()
    {
        if (killHistory.isEmpty())
        {
            return 0;
        }
        return killHistory.get(killHistory.size() - 1).getTimestamp();
    }

    /**
     * Get timestamp of the first kill
     */
    public long getFirstKillTimestamp()
    {
        if (killHistory.isEmpty())
        {
            return 0;
        }
        return killHistory.get(0).getTimestamp();
    }

    /**
     * Get aggregated drop data for display in UI
     */
    public List<AggregatedDrop> getAggregatedDrops()
    {
        Map<Integer, AggregatedDrop> aggregatedMap = new HashMap<>();

        for (NpcKillRecord kill : killHistory)
        {
            for (LootDrop drop : kill.getDrops())
            {
                int itemId = drop.getItemId();

                AggregatedDrop agg = aggregatedMap.get(itemId);
                if (agg == null)
                {
                    agg = new AggregatedDrop();
                    agg.itemId = itemId;
                    agg.itemName = drop.getItemName();
                    agg.totalQuantity = 0;
                    agg.totalValue = 0;
                    agg.dropCount = 0;
                    agg.gePrice = drop.getGePrice();
                    agg.highAlchValue = drop.getHighAlchValue(); // ← ADD THIS
                    aggregatedMap.put(itemId, agg);
                }

                agg.totalQuantity += drop.getQuantity();
                agg.totalValue += drop.getGePrice() * drop.getQuantity();
                agg.dropCount++;
            }
        }

        return new ArrayList<>(aggregatedMap.values());
    }

    /**
     * Get aggregated drops sorted by total value (highest first)
     */
    public List<AggregatedDrop> getAggregatedDropsSorted()
    {
        List<AggregatedDrop> drops = getAggregatedDrops();
        drops.sort((a, b) -> Long.compare(b.getTotalValue(), a.getTotalValue()));
        return drops;
    }

    /**
     * Inner class representing aggregated drop data
     */
    @Getter
    @Setter
    public static class AggregatedDrop
    {
        private int itemId;
        private String itemName;
        private int totalQuantity;
        private long totalValue;
        private int dropCount;
        private long gePrice;
        private long highAlchValue;

        public AggregatedDrop()
        {
        }

        public AggregatedDrop(int itemId, String itemName, int totalQuantity, long totalValue, int dropCount, long gePrice, long highAlchValue)
        {
            this.itemId = itemId;
            this.itemName = itemName;
            this.totalQuantity = totalQuantity;
            this.totalValue = totalValue;
            this.dropCount = dropCount;
            this.gePrice = gePrice;
            this.highAlchValue = highAlchValue;
        }
    }
}