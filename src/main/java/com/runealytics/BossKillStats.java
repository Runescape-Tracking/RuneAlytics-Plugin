package com.runealytics;

import lombok.Data;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregated stats for a specific boss/NPC
 */
@Data
public class BossKillStats
{
    private final String npcName;
    private final int npcId;
    private int killCount;
    private long totalLootValue;
    private final List<NpcKillRecord> killHistory;

    // Aggregated drop counts: itemId -> AggregatedDrop
    private final Map<Integer, AggregatedDrop> aggregatedDrops;

    // Prestige tracking
    private int currentPrestige;

    public BossKillStats(String npcName, int npcId)
    {
        this.npcName = npcName;
        this.npcId = npcId;
        this.killCount = 0;
        this.totalLootValue = 0;
        this.killHistory = new ArrayList<>();
        this.aggregatedDrops = new HashMap<>();
        this.currentPrestige = 0;
    }

    public void addKill(NpcKillRecord kill)
    {
        killCount++;
        totalLootValue += kill.getTotalValue();
        killHistory.add(kill);

        // Aggregate drops
        for (LootDrop drop : kill.getDrops())
        {
            AggregatedDrop agg = aggregatedDrops.computeIfAbsent(
                    drop.getItemId(),
                    id -> new AggregatedDrop(
                            drop.getItemId(),
                            drop.getItemName(),
                            drop.getGePrice(),
                            drop.getHighAlchValue()
                    )
            );

            agg.addDrop(drop.getQuantity(), drop.getTotalValue());
        }
    }

    /**
     * Reset stats for prestige (keeps history on server)
     */
    public void prestige()
    {
        currentPrestige++;
        killCount = 0;
        totalLootValue = 0;
        killHistory.clear();
        aggregatedDrops.clear();
    }

    /**
     * Get average loot per kill
     */
    public long getAverageLootPerKill()
    {
        return killCount > 0 ? totalLootValue / killCount : 0;
    }

    /**
     * Get most recent kill
     */
    public NpcKillRecord getMostRecentKill()
    {
        return killHistory.isEmpty() ? null : killHistory.get(killHistory.size() - 1);
    }

    /**
     * Get aggregated drops sorted by total value
     */
    public List<AggregatedDrop> getAggregatedDropsSorted()
    {
        return aggregatedDrops.values().stream()
                .sorted(Comparator.comparingLong(AggregatedDrop::getTotalValue).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Represents an aggregated drop (same item across multiple kills)
     */
    @Data
    public static class AggregatedDrop
    {
        private final int itemId;
        private final String itemName;
        private final long gePrice;
        private final int highAlchValue;

        private int dropCount;          // Number of times this item dropped
        private long totalQuantity;     // Total quantity received
        private long totalValue;        // Total value of all drops

        public AggregatedDrop(int itemId, String itemName, long gePrice, int highAlchValue)
        {
            this.itemId = itemId;
            this.itemName = itemName;
            this.gePrice = gePrice;
            this.highAlchValue = highAlchValue;
            this.dropCount = 0;
            this.totalQuantity = 0;
            this.totalValue = 0;
        }

        public void addDrop(int quantity, long value)
        {
            this.dropCount++;
            this.totalQuantity += quantity;
            this.totalValue += value;
        }

        /**
         * Get the most common quantity (for display purposes)
         */
        public int getDisplayQuantity()
        {
            return (int) totalQuantity;
        }
    }
}