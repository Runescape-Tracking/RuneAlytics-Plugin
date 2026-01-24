package com.runealytics;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;

@Data
public class LootData
{
    @SerializedName("username")
    private final String username;

    @SerializedName("npc_name")
    private final String npcName;

    @SerializedName("npc_id")
    private final int npcId;

    @SerializedName("combat_level")
    private final int combatLevel;

    @SerializedName("kill_count")
    private final int killCount;

    @SerializedName("world")
    private final int world;

    @SerializedName("timestamp")
    private final long timestamp;

    @SerializedName("prestige")
    private final int prestige;

    @SerializedName("drops")
    private final List<LootItem> drops;

    @SerializedName("total_loot_value")
    private final int totalLootValue;

    @SerializedName("drop_count")
    private final int dropCount;

    public String getBossName()
    {
        return npcName;
    }
}