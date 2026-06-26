package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Builds the {@code drop} / {@code kill} JSON objects POSTed to the RuneAlytics
 * server. Central schema shared by all loot upload paths.
 */
public final class LootKillJsonBuilder
{
    private LootKillJsonBuilder() {}

    /**
     * Builds a single {@code DropRecord} JSON object matching the
     * {@code /loot/*} API schema. Items with no resolvable name are included
     * with a synthetic {@code "Item #<id>"} label.
     */
    public static JsonObject buildDrop(LootStorageData.DropRecord drop)
    {
        JsonObject d = new JsonObject();
        d.addProperty("item_id",     drop.getItemId());
        d.addProperty("item_name",   safeName(drop));
        d.addProperty("quantity",    Math.max(1, drop.getQuantity()));
        d.addProperty("ge_price",    drop.getGePrice());
        d.addProperty("high_alch",   drop.getHighAlch());
        d.addProperty("total_value", drop.getTotalValue());
        d.addProperty("hidden",      drop.isHidden());
        d.addProperty("is_pet",      drop.isPet());
        return d;
    }

    private static String safeName(LootStorageData.DropRecord drop)
    {
        String name = drop.getItemName();
        if (name != null && !name.isEmpty()) return name;
        return "Item #" + drop.getItemId();
    }

    /**
     * Builds a complete kill payload (NPC metadata + drops + aggregates).
     *
     * <p>NPC info is passed in because {@link LootStorageData.KillRecord} does
     * not store it — kills are nested under their NPC inside
     * {@link LootStorageData.BossKillData}.</p>
     */
    public static JsonObject buildKill(
            LootStorageData.KillRecord kill,
            String npcName,
            int npcId,
            int prestige)
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("npc_name",     npcName);
        payload.addProperty("npc_id",       npcId);
        payload.addProperty("combat_level", kill.getCombatLevel());
        payload.addProperty("kill_count",   kill.getKillNumber());
        payload.addProperty("world",        kill.getWorld());
        payload.addProperty("timestamp",    kill.getTimestamp());
        payload.addProperty("prestige",     prestige);
        payload.addProperty("game_mode",    kill.getGameMode()    != null ? kill.getGameMode()    : "regular");
        payload.addProperty("account_type", kill.getAccountType() != null ? kill.getAccountType() : "normal");

        JsonArray dropsArr = new JsonArray();
        long totalValue = 0;
        int  dropCount  = 0;

        List<LootStorageData.DropRecord> drops = kill.getDrops();
        if (drops != null)
        {
            for (LootStorageData.DropRecord d : drops)
            {
                dropsArr.add(buildDrop(d));
                totalValue += d.getTotalValue();
                dropCount++;
            }
        }

        payload.add("drops", dropsArr);
        payload.addProperty("total_loot_value", totalValue);
        payload.addProperty("drop_count",       dropCount);

        // Optional per-kill location object. Omitted when the kill has no
        // captured location.
        PlayerLocationSnapshot location = kill.getLocation();
        if (location != null)
        {
            payload.add("location", location.toJson());
        }

        return payload;
    }

    /**
     * Wraps a batch of kill payloads inside the envelope the bulk endpoints expect.
     *
     * <p>Schema: {@code { "username": ..., "game_mode": ..., "account_type": ..., "kills": [...] }}.
     * {@code game_mode} and {@code account_type} at the top level represent the session-level
     * default; individual kill objects may carry their own values if the mode changed
     * mid-session (e.g. world-hop from regular to a Leagues world).</p>
     *
     * @param username       verified RSN
     * @param gameMode       session-level game mode (e.g. "regular", "leagues")
     * @param accountSubtype session-level account subtype (e.g. "normal", "ironman")
     * @param kills          list of per-kill payloads built by {@link #buildKill}
     */
    public static JsonObject buildBulkEnvelope(
            String username,
            String gameMode,
            String accountSubtype,
            List<JsonObject> kills)
    {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("username",     username);
        envelope.addProperty("game_mode",    gameMode      != null ? gameMode      : "regular");
        envelope.addProperty("account_type", accountSubtype != null ? accountSubtype : "normal");

        JsonArray arr = new JsonArray();
        for (JsonObject k : kills) arr.add(k);
        envelope.add("kills", arr);
        return envelope;
    }
}
