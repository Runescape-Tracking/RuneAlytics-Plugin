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
     * Per-boss set of item IDs the user has hidden in RuneAlytics, keyed by
     * normalised boss name. Separate from RuneLite's own ignore list.
     */
    @SerializedName("hidden_drops_by_boss")
    private Map<String, Set<Integer>> hiddenDropsByBoss = new HashMap<>();

    /**
     * Normalised names of bosses the user has hidden from the panel entirely.
     * Display-only; does not affect what gets synced to the server.
     */
    @SerializedName("hidden_bosses")
    private Set<String> hiddenBosses = new HashSet<>();

    /**
     * Last authoritative in-game kill count observed per boss (from KC chat
     * messages), keyed by normalised boss name. Raise-only. Survives restarts
     * so the real game KC can be audited against the local counter; absent in
     * files written by older plugin versions (Gson leaves the default).
     */
    @SerializedName("last_game_kc_by_boss")
    private Map<String, Integer> lastGameKcByBoss = new HashMap<>();

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

        /**
         * Player location when this kill happened, captured on the client thread
         * and uploaded per-kill in the {@code /loot/bulk-sync} payload.
         * {@code null} when no location was captured, in which case the field is
         * omitted from the request.
         */
        @SerializedName("location")
        private PlayerLocationSnapshot location;
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

        // long: a per-drop value can exceed Integer.MAX_VALUE for large stacks
        // of high-value items.
        @SerializedName("total_value")
        private long totalValue;

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
