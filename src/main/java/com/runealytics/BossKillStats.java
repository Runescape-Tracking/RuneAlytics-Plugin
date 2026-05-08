package com.runealytics;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Getter
@Setter
public class BossKillStats
{
    private String npcName;
    private int    npcId;
    private int    killCount;
    private int    prestige;
    private long   totalLootValue;
    private long   highestDrop;
    private List<LootStorageData.KillRecord> killHistory;

    private List<AggregatedDrop> preloadedDrops = new ArrayList<>();

    public BossKillStats(String npcName, int npcId)
    {
        this.npcName      = npcName;
        this.npcId        = npcId;
        this.killCount    = 0;
        this.prestige     = 0;
        this.totalLootValue = 0;
        this.highestDrop  = 0;
        this.killHistory  = new ArrayList<>();
    }

    public void prestige()
    {
        this.prestige++;
        this.killCount      = 0;
        this.totalLootValue = 0;
        this.highestDrop    = 0;
        this.killHistory.clear();
        this.preloadedDrops.clear();
        log.info("Boss {} prestiged to level {}", npcName, prestige);
    }

    public long getLastKillTimestamp()
    {
        if (killHistory == null || killHistory.isEmpty()) return 0L;
        return killHistory.get(killHistory.size() - 1).getTimestamp();
    }

    public void addKill(LootStorageData.KillRecord kill)
    {
        if (kill == null) return;

        killHistory.add(kill);
        this.killCount++;

        long killValue = 0;
        for (LootStorageData.DropRecord drop : kill.getDrops())
        {
            killValue += drop.getTotalValue();
            if (drop.getTotalValue() > highestDrop)
                highestDrop = drop.getTotalValue();
        }
        this.totalLootValue += killValue;
    }

    public void setPreloadedDrops(List<AggregatedDrop> drops)
    {
        this.preloadedDrops = drops != null ? new ArrayList<>(drops) : new ArrayList<>();
    }

    public List<AggregatedDrop> getAggregatedDrops()
    {
        Map<Integer, AggregatedDrop> aggregatedMap = new HashMap<>();

        for (LootStorageData.KillRecord kill : killHistory)
        {
            for (LootStorageData.DropRecord drop : kill.getDrops())
            {
                AggregatedDrop agg = aggregatedMap.computeIfAbsent(
                        drop.getItemId(),
                        id -> new AggregatedDrop(
                                drop.getItemId(), drop.getItemName(),
                                0, 0, 0,
                                drop.getGePrice(), drop.getHighAlch()));

                agg.totalQuantity += drop.getQuantity();
                agg.totalValue    += drop.getTotalValue();
                agg.dropCount++;
            }
        }

        List<AggregatedDrop> result = new ArrayList<>(aggregatedMap.values());

        if (result.isEmpty() && preloadedDrops != null && !preloadedDrops.isEmpty())
        {
            log.debug("{}: using {} preloaded drops", npcName, preloadedDrops.size());
            return new ArrayList<>(preloadedDrops);
        }

        return result;
    }

    public List<AggregatedDrop> getAggregatedDropsSorted()
    {
        List<AggregatedDrop> drops = getAggregatedDrops();
        drops.sort((a, b) -> Long.compare(b.getTotalValue(), a.getTotalValue()));
        return drops;
    }

    @Getter
    @Setter
    public static class AggregatedDrop
    {
        private int    itemId;
        private String itemName;
        private int    totalQuantity;
        private long   totalValue;
        private int    dropCount;
        private long   gePrice;
        private long   highAlchValue;

        public AggregatedDrop(int itemId, String itemName,
                              int totalQuantity, long totalValue, int dropCount,
                              long gePrice, long highAlchValue)
        {
            this.itemId        = itemId;
            this.itemName      = itemName;
            this.totalQuantity = totalQuantity;
            this.totalValue    = totalValue;
            this.dropCount     = dropCount;
            this.gePrice       = gePrice;
            this.highAlchValue = highAlchValue;
        }
    }
}