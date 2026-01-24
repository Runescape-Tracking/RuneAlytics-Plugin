package com.runealytics;

import lombok.Data;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a single NPC kill with all drops
 */
@Data
public class NpcKillRecord
{
    private final String npcName;
    private final int npcId;
    private final int combatLevel;
    private final Instant killTime;
    private final List<LootDrop> drops;
    private final int worldNumber;

    public NpcKillRecord(String npcName, int npcId, int combatLevel, int worldNumber)
    {
        this.npcName = npcName;
        this.npcId = npcId;
        this.combatLevel = combatLevel;
        this.killTime = Instant.now();
        this.drops = new ArrayList<>();
        this.worldNumber = worldNumber;
    }

    public void addDrop(LootDrop drop)
    {
        drops.add(drop);
    }

    /**
     * Get total value of all drops (excluding hidden)
     */
    public long getTotalValue()
    {
        return drops.stream()
                .filter(d -> !d.isHidden())
                .mapToLong(LootDrop::getTotalValue)
                .sum();
    }

    /**
     * Get visible drops only
     */
    public List<LootDrop> getVisibleDrops()
    {
        return drops.stream()
                .filter(d -> !d.isHidden())
                .collect(Collectors.toList());
    }

    /**
     * Get number of visible drops
     */
    public int getVisibleDropCount()
    {
        return (int) drops.stream().filter(d -> !d.isHidden()).count();
    }

    public long getTimestamp() {
        return killTime.toEpochMilli();
    }
}