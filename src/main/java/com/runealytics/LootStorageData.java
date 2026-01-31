package com.runealytics;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.*;

@Data
public class LootStorageData
{
    @SerializedName("username")
    private String username;

    @SerializedName("last_sync")
    private long lastSyncTimestamp;

    @SerializedName("boss_kills")
    private Map<String, BossKillData> bossKills = new HashMap<>();

    @Data
    public static class BossKillData
    {
        @SerializedName("npc_name")
        private String npcName;

        @SerializedName("npc_id")
        private int npcId;

        @SerializedName("kill_count")
        private int killCount;

        @SerializedName("prestige")
        private int prestige;

        @SerializedName("total_loot_value")
        private long totalLootValue;

        @SerializedName("kills")
        private List<KillRecord> kills = new ArrayList<>();

        @SerializedName("aggregated_drops")
        private Map<Integer, AggregatedDrop> aggregatedDrops = new HashMap<>();
    }

    @Data
    public static class KillRecord
    {
        @SerializedName("timestamp")
        private long timestamp;

        @SerializedName("kill_number")
        private int killNumber;

        @SerializedName("world")
        private int world;

        @SerializedName("combat_level")
        private int combatLevel;

        @SerializedName("drops")
        private List<DropRecord> drops = new ArrayList<>();

        @SerializedName("synced_to_server")
        private boolean syncedToServer;
    }

    @Data
    public static class DropRecord
    {
        @SerializedName("item_id")
        private int itemId;

        @SerializedName("item_name")
        private String itemName;

        @SerializedName("quantity")
        private int quantity;

        @SerializedName("ge_price")
        private int gePrice;

        @SerializedName("high_alch")
        private int highAlch;

        @SerializedName("total_value")
        private int totalValue;

        @SerializedName("hidden")
        private boolean hidden;
    }

    @Data
    public static class AggregatedDrop
    {
        @SerializedName("item_id")
        private int itemId;

        @SerializedName("item_name")
        private String itemName;

        @SerializedName("total_quantity")
        private int totalQuantity;

        @SerializedName("drop_count")
        private int dropCount;

        @SerializedName("total_value")
        private long totalValue;

        @SerializedName("ge_price")
        private int gePrice;

        @SerializedName("high_alch")
        private int highAlch;
    }
}