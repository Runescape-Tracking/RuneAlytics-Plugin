package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.runealytics.RuneAlyticsHttp.JSON;

/**
 * Thin HTTP client for the loot-related RuneAlytics endpoints.
 *
 * <p>This used to contain a half-dozen overlapping sync methods, each with
 * their own hand-rolled JSON layout. They have been consolidated into one
 * batch upload path ({@link #bulkSyncKills}) plus history download
 * ({@link #fetchKillHistoryFromServer}). Payload construction is delegated to
 * {@link LootKillJsonBuilder} so there's a single schema in the codebase.</p>
 */
@Slf4j
@Singleton
public class LootTrackerApiClient
{
    private static final String LOOT_BULK_SYNC_PATH = "/loot/bulk-sync";
    private static final String LOOT_HISTORY_PATH   = "/loot/history/";

    private final OkHttpClient       httpClient;
    private final RunealyticsConfig  config;
    private final RuneAlyticsState   state;
    private final Gson               gson;

    @Inject
    public LootTrackerApiClient(
            OkHttpClient httpClient,
            RunealyticsConfig config,
            RuneAlyticsState state,
            Gson gson)
    {
        this.httpClient = httpClient;
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
     * <p>NPC id and prestige come from the per-boss {@link LootStorageData.BossKillData}
     * (kills don't store these themselves), so callers should pass that map
     * directly from storage rather than rebuilding it.</p>
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

                killPayloads.add(LootKillJsonBuilder.buildKill(kill, npcName, npcId, prestige));
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
    //  DOWNLOAD – HISTORY
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fetches the complete kill history for {@code username} from the server,
     * grouped by boss for use by {@link LootStorageManager#mergeServerData}.
     */
    public Map<String, LootStorageData.BossKillData> fetchKillHistoryFromServer(String username) throws IOException
    {
        Request request = new Request.Builder()
                .url(config.apiUrl() + LOOT_HISTORY_PATH + username)
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

        for (int i = 0; i < killsArray.size(); i++)
        {
            JsonObject killObj = killsArray.get(i).getAsJsonObject();

            String bossName        = killObj.get("boss_name").getAsString();
            int    bossId          = killObj.get("boss_id").getAsInt();
            int    combatLevel     = killObj.get("combat_level").getAsInt();
            int    world           = killObj.get("world").getAsInt();
            long   killTimeSeconds = killObj.get("kill_time").getAsLong();

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
                    JsonObject dropObj = dropsArray.get(j).getAsJsonObject();

                    LootStorageData.DropRecord drop = new LootStorageData.DropRecord();
                    drop.setItemId(dropObj.get("item_id").getAsInt());
                    drop.setItemName(dropObj.get("item_name").getAsString());
                    drop.setQuantity(dropObj.get("quantity").getAsInt());
                    drop.setGePrice(dropObj.get("ge_price").getAsInt());
                    drop.setHighAlch(dropObj.get("high_alch").getAsInt());
                    drop.setTotalValue(drop.getGePrice() * drop.getQuantity());
                    drop.setHidden(false);

                    killRecord.getDrops().add(drop);
                }
            }

            bossData.getKills().add(killRecord);
            bossData.setKillCount(bossData.getKillCount() + 1);
        }

        log.info("Fetched {} bosses with total {} kills from server",
                result.size(),
                result.values().stream().mapToInt(b -> b.getKills().size()).sum());

        return result;
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
            log.info("[{}] ok", contextForLog);
            return true;
        }
        catch (IOException e)
        {
            log.error("[{}] network failure: {}", contextForLog, e.getMessage());
            return false;
        }
    }
}
