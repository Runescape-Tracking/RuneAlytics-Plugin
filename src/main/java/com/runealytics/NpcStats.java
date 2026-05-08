package com.runealytics;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class NpcStats
{
    private final String name;
    private int npcId;
    private int combatLevel;
    private final List<LootStorageData.KillRecord> kills = new ArrayList<>();

    public NpcStats(String name)
    {
        this.name = name;
    }

    public void addKill(LootStorageData.KillRecord kill)
    {
        kills.add(kill);
        // npcId is usually passed from the BossKillData parent in the manager
        this.combatLevel = kill.getCombatLevel();
    }

    public int getKillCount()
    {
        return kills.size();
    }

    public List<LootStorageData.DropRecord> getAllDrops()
    {
        List<LootStorageData.DropRecord> all = new ArrayList<>();
        for (LootStorageData.KillRecord k : kills)
        {
            all.addAll(k.getDrops());
        }
        return all;
    }

    public long getTotalLootValue()
    {
        return kills.stream()
                .flatMap(k -> k.getDrops().stream())
                .mapToLong(LootStorageData.DropRecord::getTotalValue)
                .sum();
    }
}