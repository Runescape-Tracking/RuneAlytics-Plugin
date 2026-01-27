package com.runealytics;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single NPC kill with all associated loot drops
 */
@Getter
@Setter
public class NpcKillRecord
{
    private String npcName;
    private int npcId;
    private int combatLevel;
    private int worldNumber;
    private long timestamp;
    private int killNumber;
    private List<LootDrop> drops;

    public NpcKillRecord(String npcName, int npcId, int combatLevel, int worldNumber)
    {
        this.npcName = npcName;
        this.npcId = npcId;
        this.combatLevel = combatLevel;
        this.worldNumber = worldNumber;
        this.timestamp = System.currentTimeMillis();
        this.drops = new ArrayList<>();
        this.killNumber = 0;
    }

    /**
     * Override setDrops to ensure it's never null
     */
    public void setDrops(List<LootDrop> drops)
    {
        this.drops = drops != null ? drops : new ArrayList<>();
    }

    /**
     * Get drops - guaranteed to never return null
     */
    public List<LootDrop> getDrops()
    {
        if (drops == null)
        {
            drops = new ArrayList<>();
        }
        return drops;
    }

    /**
     * Add a loot drop to this kill
     */
    public void addDrop(LootDrop drop)
    {
        if (drops == null)
        {
            drops = new ArrayList<>();
        }
        drops.add(drop);
    }

    /**
     * Calculate total value of all drops in this kill
     */
    public long getTotalValue()
    {
        if (drops == null)
        {
            return 0;
        }
        return drops.stream()
                .mapToLong(drop -> drop.getGePrice() * drop.getQuantity())
                .sum();
    }

    /**
     * Get the number of drops in this kill
     */
    public int getDropCount()
    {
        return drops != null ? drops.size() : 0;
    }

    /**
     * Get only visible (non-hidden) drops
     */
    public List<LootDrop> getVisibleDrops()
    {
        if (drops == null)
        {
            return new ArrayList<>();
        }
        return new ArrayList<>(drops);
    }
}