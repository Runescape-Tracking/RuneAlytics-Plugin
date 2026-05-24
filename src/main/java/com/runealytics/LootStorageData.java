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

    /**
     * RuneAlytics-specific per-boss "ignored item" list.
     *
     * <p>Independent of RuneLite's own ignore feature so that hiding a drop in
     * RuneAlytics has no effect on any other plugin.  Keyed by normalised boss
     * name; value is the set of item IDs the user has chosen to hide.</p>
     */
    @SerializedName("hidden_drops_by_boss")
    private Map<String, Set<Integer>> hiddenDropsByBoss = new HashMap<>();

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

        /**
         * Game mode at the time this kill was recorded.
         * Values: "regular", "ironman", "leagues", "deadman", "fresh_start", "grid_master".
         * Defaults to "regular" for records loaded from older storage files.
         */
        @SerializedName("game_mode")
        private String gameMode = "regular";

        /**
         * OSRS account subtype at the time this kill was recorded.
         * Values: "normal", "ironman", "hardcore_ironman", "ultimate_ironman",
         * "group_ironman", "hardcore_group_ironman", "unranked_group_ironman".
         */
        @SerializedName("account_type")
        private String accountType = "normal";
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

        @SerializedName("is_pet")
        private boolean pet;
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

        @SerializedName("is_pet")
        private boolean pet;
    }
}