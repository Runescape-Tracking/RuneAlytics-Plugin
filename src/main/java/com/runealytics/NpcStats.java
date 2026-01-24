package com.runealytics;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates statistics for a single NPC/boss across multiple kills
 */
@Data
public class NpcStats
{
    private final String name;
    private int npcId;
    private int combatLevel;
    private final List<NpcKillRecord> kills = new ArrayList<>();

    public NpcStats(String name)
    {
        this.name = name;
    }

    public void addKill(NpcKillRecord kill)
    {
        kills.add(kill);
        this.npcId = kill.getNpcId();
        this.combatLevel = kill.getCombatLevel();
    }

    public int getKillCount()
    {
        return kills.size();
    }

    public List<LootDrop> getAllDrops()
    {
        List<LootDrop> all = new ArrayList<>();
        for (NpcKillRecord k : kills)
        {
            all.addAll(k.getVisibleDrops());
        }
        return all;
    }

    public long getTotalLootValue()
    {
        return kills.stream()
                .mapToLong(NpcKillRecord::getTotalValue)
                .sum();
    }
}
