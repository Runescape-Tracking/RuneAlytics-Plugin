package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.runealytics.RuneAlyticsHttp.JSON;

/**
 * HTTP client for the loot-related RuneAlytics endpoints: batch upload
 * ({@link #bulkSyncKills}) and history download
 * ({@link #fetchKillHistoryFromServer}). Payload construction is delegated to
 * {@link LootKillJsonBuilder}.
 */
@Slf4j
@Singleton
public class LootTrackerApiClient
{
    private static final String LOOT_BULK_SYNC_PATH    = "/loot/bulk-sync";
    private static final String LOOT_HISTORY_PATH      = "/loot/history/";
    private static final String LOOT_SNAPSHOT_PATH     = "/runelite/loot/snapshot";
    private static final String LOOT_SYNC_ABSOLUTE_PATH = "/runelite/loot/sync-absolute";
    private static final String PLAYER_DEATH_EVENT_PATH = "/runelite/player/death-event";

    private final OkHttpClient       httpClient;
    private final RunealyticsConfig  config;
    private final RuneAlyticsState   state;
    private final Gson               gson;
    private final ItemManager        itemManager;
    private final ClientThread       clientThread;

    @Inject
    public LootTrackerApiClient(
            OkHttpClient httpClient,
            RunealyticsConfig config,
            RuneAlyticsState state,
            Gson gson,
            ItemManager itemManager,
            ClientThread clientThread)
    {
        this.itemManager  = itemManager;
        this.clientThread = clientThread;
        // Apply the configured timeout so loot uploads/downloads don't hang on
        // RuneLite's shared-client defaults.
        this.httpClient = httpClient.newBuilder()
                .connectTimeout(config.syncTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.syncTimeout(),    TimeUnit.SECONDS)
                .writeTimeout(config.syncTimeout(),   TimeUnit.SECONDS)
                .build();
        this.config     = config;
        this.state      = state;
        this.gson       = gson;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UPLOAD – BULK
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Uploads a batch of unsynced kills to {@code /loot/bulk-sync}.
     *
     * <p>NPC id and prestige come from the per-boss {@link LootStorageData.BossKillData},
     * since kills don't store these themselves.</p>
     *
     * @param username    verified RSN
     * @param killsByBoss map of {npcName → kills to sync}
     * @param bossLookup  full per-boss data, used to resolve npcId / prestige
     * @return true on HTTP 2xx
     */
    public boolean bulkSyncKills(
            String username,
            Map<String, List<LootStorageData.KillRecord>> killsByBoss,
            Map<String, LootStorageData.BossKillData> bossLookup) throws IOException
    {
        if (killsByBoss == null || killsByBoss.isEmpty()) return true;

        List<JsonObject> killPayloads = new ArrayList<>();
        int skippedZeroLoot = 0;

        for (Map.Entry<String, List<LootStorageData.KillRecord>> e : killsByBoss.entrySet())
        {
            String npcName = e.getKey();
            LootStorageData.BossKillData boss = bossLookup != null ? bossLookup.get(npcName) : null;

            int npcId    = boss != null ? boss.getNpcId()    : 0;
            int prestige = boss != null ? boss.getPrestige() : 0;

            for (LootStorageData.KillRecord kill : e.getValue())
            {
                // The server requires a non-empty drops array (HTTP 422 otherwise).
                // Zero-loot kills are tracked locally for kill-count accuracy but
                // are not sent to the server — they carry no drop data to store.
                List<LootStorageData.DropRecord> drops = kill.getDrops();
                if (drops == null || drops.isEmpty())
                {
                    skippedZeroLoot++;
                    // Mark synced so they are not retried on the next batch pass.
                    kill.setSyncedToServer(true);
                    continue;
                }

                JsonObject killPayload = LootKillJsonBuilder.buildKill(kill, npcName, npcId, prestige);

                if (log.isDebugEnabled())
                {
                    log.debug("[bulk-sync] kill '{}' #{} location payload: {}",
                            npcName, kill.getKillNumber(),
                            killPayload.has("location") ? killPayload.get("location") : "<none>");
                }

                killPayloads.add(killPayload);
            }
        }

        if (skippedZeroLoot > 0)
            log.debug("[bulk-sync] skipped {} zero-loot kill(s) — counted locally only", skippedZeroLoot);

        if (killPayloads.isEmpty()) return true;

        JsonObject envelope = LootKillJsonBuilder.buildBulkEnvelope(
                username,
                state.getCurrentGameMode(),
                state.getCurrentAccountSubtype(),
                killPayloads);
        return postJson(LOOT_BULK_SYNC_PATH, envelope, "bulk-sync " + killPayloads.size() + " kills");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SNAPSHOT  –  GET /runelite/loot/snapshot
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fetches the website's saved absolute loot totals for {@code username}.
     *
     * <p>Used by {@link LootSyncMergeService} to obtain the "website" leg of
     * the three-source merge before computing the max-absolute totals.</p>
     *
     * @return a parsed snapshot, or {@code null} on any error/auth failure
     */
    public LootSnapshot fetchLootSnapshot(String username) throws IOException
    {
        String url = config.apiUrl() + LOOT_SNAPSHOT_PATH
                + "?username=" + encodePathSegment(username)
                + "&game=osrs";

        Request.Builder rb = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "application/json");

        String token = state.getVerificationCode();
        if (token != null && !token.isEmpty())
        {
            rb.addHeader("Authorization", "Bearer " + token);
        }

        try (Response response = httpClient.newCall(rb.build()).execute())
        {
            if (response.code() == 401 || response.code() == 403)
            {
                log.warn("[snapshot] auth error: HTTP {}", response.code());
                return null;
            }
            if (!response.isSuccessful() || response.body() == null)
            {
                log.warn("[snapshot] failed: HTTP {}", response.code());
                return null;
            }

            String json = response.body().string();
            log.debug("[snapshot] response: {}", json);
            return parseSnapshot(gson.fromJson(json, JsonObject.class));
        }
        catch (IOException e)
        {
            log.error("[snapshot] network failure: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Parses a {@code /runelite/loot/snapshot} response into a {@link LootSnapshot}.
     */
    private LootSnapshot parseSnapshot(JsonObject obj)
    {
        if (obj == null || !obj.has("success") || !obj.get("success").getAsBoolean())
        {
            return null;
        }

        LootSnapshot snapshot = new LootSnapshot();
        snapshot.username = getString(obj, "username", "");

        if (!obj.has("sources") || !obj.get("sources").isJsonArray())
        {
            return snapshot;
        }

        for (JsonElement sourceEl : obj.getAsJsonArray("sources"))
        {
            if (!sourceEl.isJsonObject()) continue;
            JsonObject src = sourceEl.getAsJsonObject();

            String sourceKey  = getString(src, "source_key",  "");
            String sourceName = getString(src, "source_name", sourceKey);

            if (!src.has("items") || !src.get("items").isJsonArray()) continue;

            Map<String, Long> itemTotals = new HashMap<>();

            for (JsonElement itemEl : src.getAsJsonArray("items"))
            {
                if (!itemEl.isJsonObject()) continue;
                JsonObject item = itemEl.getAsJsonObject();

                int    itemId  = getInt(item, "item_id", 0);
                String name    = getString(item, "item_name", "");
                long   qty     = getLong(item, "quantity", 0L);

                // Use "id_{itemId}" as key when the ID is present, otherwise
                // "name_{normalized}" — mirrors the server's item_key logic.
                String key = itemId > 0
                        ? "id_" + itemId
                        : "name_" + name.toLowerCase().trim();

                itemTotals.merge(key, qty, Math::max);
                if (itemId > 0)
                {
                    snapshot.itemIdsByKey.put(key, itemId);
                    snapshot.itemNamesByKey.put(key, name);
                }
            }

            snapshot.sources.put(sourceKey, new LootSnapshot.SourceData(sourceKey, sourceName, itemTotals));
        }

        return snapshot;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SYNC-ABSOLUTE  –  POST /runelite/loot/sync-absolute
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Submits merged absolute loot totals to the website.
     *
     * <p>This endpoint is idempotent: the server only updates stored quantities
     * when the submitted value is ≥ the stored value (max-wins merge).  Repeated
     * calls with the same data produce no changes.</p>
     *
     * @param  username  normalized RuneScape account name
     * @param  sources   merged source/item totals from {@link LootSyncMergeService}
     * @return true on HTTP 2xx
     */
    public boolean syncAbsolute(String username,
            List<LootSyncMergeService.MergedSource> sources) throws IOException
    {
        if (sources == null || sources.isEmpty()) return true;

        JsonObject envelope = new JsonObject();
        envelope.addProperty("username",  username);
        envelope.addProperty("game",      "osrs");
        envelope.addProperty("sync_mode", "absolute_merge");
        envelope.addProperty("force_resync", false);
        envelope.addProperty("client_timestamp",
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        JsonArray sourcesArr = new JsonArray();
        for (LootSyncMergeService.MergedSource src : sources)
        {
            JsonObject srcObj = new JsonObject();
            srcObj.addProperty("source_key",  src.sourceKey);
            srcObj.addProperty("source_name", src.sourceName);
            if (src.sourceType != null) srcObj.addProperty("source_type", src.sourceType);

            JsonArray itemsArr = new JsonArray();
            for (LootSyncMergeService.MergedItem item : src.items)
            {
                JsonObject itemObj = new JsonObject();
                if (item.itemId > 0) itemObj.addProperty("item_id", item.itemId);
                itemObj.addProperty("item_name",    item.itemName);
                itemObj.addProperty("quantity",     item.quantity);
                itemObj.addProperty("merge_reason", item.mergeReason);
                itemsArr.add(itemObj);
            }
            srcObj.add("items", itemsArr);
            sourcesArr.add(srcObj);
        }
        envelope.add("sources", sourcesArr);

        return postJson(LOOT_SYNC_ABSOLUTE_PATH, envelope,
                "sync-absolute " + sources.size() + " sources");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DEATH EVENT  –  POST /runelite/player/death-event
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Notifies the website of a player death or item-recovery event.
     *
     * <p>Recovered items sent in this call are stored as metadata only and
     * are never counted toward loot totals.</p>
     *
     * @return true on HTTP 2xx
     */
    public boolean sendDeathEvent(String username,
            String eventType,
            int world,
            int regionId,
            PlayerLocationSnapshot location,
            List<LootStorageData.DropRecord> recoveredItems) throws IOException
    {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("username",          username);
        envelope.addProperty("game",              "osrs");
        envelope.addProperty("event_type",        eventType);
        envelope.addProperty("client_timestamp",
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        envelope.addProperty("world",             world);
        envelope.addProperty("region_id",         regionId);

        if (location != null)
        {
            JsonObject loc = new JsonObject();
            loc.addProperty("x",     location.getWorldX());
            loc.addProperty("y",     location.getWorldY());
            loc.addProperty("plane", location.getPlane());
            envelope.add("location", loc);
        }

        if (recoveredItems != null && !recoveredItems.isEmpty())
        {
            JsonArray reclaimed = new JsonArray();
            for (LootStorageData.DropRecord dr : recoveredItems)
            {
                JsonObject r = new JsonObject();
                r.addProperty("item_id",   dr.getItemId());
                r.addProperty("item_name", dr.getItemName());
                r.addProperty("quantity",  dr.getQuantity());
                reclaimed.add(r);
            }
            JsonObject ctx = new JsonObject();
            ctx.add("items_recovered", reclaimed);
            envelope.add("death_context", ctx);
        }

        return postJson(PLAYER_DEATH_EVENT_PATH, envelope, "death-event " + eventType);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SNAPSHOT DATA CLASSES
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Parsed representation of a {@code /runelite/loot/snapshot} response.
     */
    public static class LootSnapshot
    {
        public String username = "";
        /** Map from normalized sourceKey to {@link SourceData}. */
        public final Map<String, SourceData>  sources        = new HashMap<>();
        public final Map<String, Integer>     itemIdsByKey   = new HashMap<>();
        public final Map<String, String>      itemNamesByKey = new HashMap<>();

        public static class SourceData
        {
            public final String              sourceKey;
            public final String              sourceName;
            /** Map from item_key ("id_536" / "name_dragon bones") to absolute quantity. */
            public final Map<String, Long>   itemTotals;

            SourceData(String sourceKey, String sourceName, Map<String, Long> itemTotals)
            {
                this.sourceKey  = sourceKey;
                this.sourceName = sourceName;
                this.itemTotals = itemTotals;
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DOWNLOAD – HISTORY (existing, unchanged)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fetches the complete kill history for {@code username} from the server,
     * grouped by boss for use by {@link LootStorageManager#mergeServerData}.
     */
    public Map<String, LootStorageData.BossKillData> fetchKillHistoryFromServer(String username) throws IOException
    {
        Request request = new Request.Builder()
                .url(config.apiUrl() + LOOT_HISTORY_PATH + encodePathSegment(username))
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                log.warn("Failed to fetch kill history: HTTP {}", response.code());
                return new HashMap<>();
            }

            String json = response.body().string();
            log.debug("History response: {}", json);

            JsonObject responseObj = gson.fromJson(json, JsonObject.class);
            if (responseObj == null || !responseObj.has("kills") || !responseObj.get("kills").isJsonArray())
            {
                log.warn("Invalid history response – missing 'kills' array");
                return new HashMap<>();
            }

            return parseHistory(responseObj.getAsJsonArray("kills"));
        }
        catch (Exception e)
        {
            log.error("Error fetching kill history from server", e);
            return new HashMap<>();
        }
    }

    private Map<String, LootStorageData.BossKillData> parseHistory(JsonArray killsArray)
    {
        Map<String, LootStorageData.BossKillData> result = new HashMap<>();
        int skipped = 0;

        for (int i = 0; i < killsArray.size(); i++)
        {
            try
            {
                JsonElement el = killsArray.get(i);
                if (!el.isJsonObject())
                {
                    skipped++;
                    continue;
                }
                JsonObject killObj = el.getAsJsonObject();

                String bossName = getString(killObj, "boss_name", null);
                if (bossName == null || bossName.isEmpty())
                {
                    // No boss name = unusable row; skip rather than bucketing under null.
                    skipped++;
                    continue;
                }

                final int  bossId          = getInt(killObj, "boss_id", 0);
                int        combatLevel      = getInt(killObj, "combat_level", 0);
                int        world            = getInt(killObj, "world", 0);
                long       killTimeSeconds  = getLong(killObj, "kill_time", 0L);

                LootStorageData.BossKillData bossData = result.computeIfAbsent(bossName, k -> {
                    LootStorageData.BossKillData bd = new LootStorageData.BossKillData();
                    bd.setNpcName(bossName);
                    bd.setNpcId(bossId);
                    bd.setKillCount(0);
                    bd.setPrestige(0);
                    bd.setTotalLootValue(0);
                    bd.setKills(new ArrayList<>());
                    bd.setAggregatedDrops(new HashMap<>());
                    return bd;
                });

                LootStorageData.KillRecord killRecord = new LootStorageData.KillRecord();
                killRecord.setTimestamp(killTimeSeconds * 1000);
                killRecord.setWorld(world);
                killRecord.setCombatLevel(combatLevel);
                killRecord.setSyncedToServer(true);
                killRecord.setDrops(new ArrayList<>());

                if (killObj.has("drops") && killObj.get("drops").isJsonArray())
                {
                    JsonArray dropsArray = killObj.getAsJsonArray("drops");

                    for (int j = 0; j < dropsArray.size(); j++)
                    {
                        JsonElement dropEl = dropsArray.get(j);
                        if (!dropEl.isJsonObject()) continue;
                        JsonObject dropObj = dropEl.getAsJsonObject();

                        int itemId  = getInt(dropObj, "item_id", 0);
                        int gePrice = getInt(dropObj, "ge_price", 0);
                        int highAlch = getInt(dropObj, "high_alch", 0);

                        LootStorageData.DropRecord drop = new LootStorageData.DropRecord();
                        drop.setItemId(itemId);
                        drop.setItemName(getString(dropObj, "item_name", ""));
                        drop.setQuantity(getInt(dropObj, "quantity", 0));
                        drop.setGePrice(gePrice);
                        drop.setHighAlch(highAlch);
                        drop.setTotalValue((long) gePrice * drop.getQuantity());
                        drop.setHidden(false);

                        killRecord.getDrops().add(drop);
                    }
                }

                bossData.getKills().add(killRecord);
                bossData.setKillCount(bossData.getKillCount() + 1);
            }
            catch (RuntimeException ex)
            {
                // One malformed row must not discard the whole download.
                skipped++;
            }
        }

        if (skipped > 0)
            log.warn("Skipped {} malformed kill record(s) in history response", skipped);

        // Re-resolve GE price / high alch for drops stored as 0 (noted, charged,
        // or untradeable items). ItemManager reads the client's item cache and
        // must run on the client thread; this method runs on a background sync
        // thread, so the whole history is resolved in a single client-thread hop.
        clientThread.invoke(() ->
        {
            for (LootStorageData.BossKillData bossData : result.values())
            {
                for (LootStorageData.KillRecord killRecord : bossData.getKills())
                {
                    for (LootStorageData.DropRecord drop : killRecord.getDrops())
                    {
                        int itemId = drop.getItemId();
                        if (itemId <= 0) continue;

                        if (drop.getGePrice() <= 0)
                        {
                            int gePrice = ItemValueResolver.perItemGeValue(itemManager, itemId);
                            if (gePrice > 0)
                            {
                                drop.setGePrice(gePrice);
                                drop.setTotalValue((long) gePrice * drop.getQuantity());
                            }
                        }
                        if (drop.getHighAlch() <= 0)
                        {
                            ItemComposition comp = itemManager.getItemComposition(itemId);
                            if (comp != null) drop.setHighAlch(comp.getHaPrice());
                        }
                    }
                }
            }
        });

        log.debug("Fetched {} bosses with total {} kills from server",
                result.size(),
                result.values().stream().mapToInt(b -> b.getKills().size()).sum());

        return result;
    }

    // ── JSON field helpers (null/type-safe) ───────────────────────────────────

    private static String getString(JsonObject obj, String key, String def)
    {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : def;
    }

    private static int getInt(JsonObject obj, String key, int def)
    {
        JsonElement el = obj.get(key);
        try
        {
            return (el != null && el.isJsonPrimitive()) ? el.getAsInt() : def;
        }
        catch (NumberFormatException e)
        {
            return def;
        }
    }

    private static long getLong(JsonObject obj, String key, long def)
    {
        JsonElement el = obj.get(key);
        try
        {
            return (el != null && el.isJsonPrimitive()) ? el.getAsLong() : def;
        }
        catch (NumberFormatException e)
        {
            return def;
        }
    }

    private static String encodePathSegment(String value)
    {
        if (value == null) return "";
        try
        {
            // URLEncoder targets query strings (space → '+'); convert to %20 for paths.
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        }
        catch (UnsupportedEncodingException e)
        {
            return value;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  INTERNAL
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Shared POST helper. Adds the auth header if available and logs the result.
     *
     * @return true on HTTP 2xx
     */
    private boolean postJson(String path, JsonObject payload, String contextForLog)
    {
        String url = config.apiUrl() + path;
        RequestBody body = RequestBody.create(JSON, gson.toJson(payload));

        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept",       "application/json");

        String token = state.getVerificationCode();
        if (token != null && !token.isEmpty())
        {
            rb.addHeader("Authorization", "Bearer " + token);
        }

        try (Response response = httpClient.newCall(rb.build()).execute())
        {
            if (!response.isSuccessful())
            {
                String responseBody = response.body() != null ? response.body().string() : "";
                log.error("[{}] failed: HTTP {} — {}", contextForLog, response.code(), responseBody);
                return false;
            }
            log.debug("[{}] ok", contextForLog);
            return true;
        }
        catch (IOException e)
        {
            log.error("[{}] network failure: {}", contextForLog, e.getMessage());
            return false;
        }
    }
}
