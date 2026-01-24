package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.runealytics.BossKillStats;
import com.runealytics.RuneAlyticsHttp;
import com.runealytics.RunealyticsConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * API client for syncing loot data to RuneAlytics
 */
@Singleton
public class LootTrackerApiClient
{
    private static final Logger log = LoggerFactory.getLogger(LootTrackerApiClient.class);
    private static final String LOOT_SYNC_PATH = "/loot/sync";
    private static final String LOOT_STATS_PATH = "/loot/stats/";
    private static final String LOOT_PRESTIGE_PATH = "/loot/prestige";

    private final OkHttpClient httpClient;
    private final RunealyticsConfig config;
    private final Gson gson = new Gson();

    @Inject
    public LootTrackerApiClient(OkHttpClient httpClient, RunealyticsConfig config)
    {
        this.httpClient = httpClient;
        this.config = config;
    }

    /**
     * Sync kill data to server
     */
    public void syncKillData(JsonObject payload) throws IOException
    {
        String token = config.authToken();

        if (token == null || token.isEmpty())
        {
            log.debug("No auth token, skipping loot sync");
            return;
        }

        if (!config.syncLootToServer())
        {
            log.debug("Loot sync disabled in config");
            return;
        }

        RequestBody body = RequestBody.create(RuneAlyticsHttp.JSON, gson.toJson(payload));
        Request request = new Request.Builder()
                .url(config.apiUrl() + LOOT_SYNC_PATH)
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.isSuccessful())
            {
                log.debug("Loot data synced successfully");
            }
            else
            {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.warn("Loot sync failed: HTTP {} - {}", response.code(), errorBody);
            }
        }
    }

    /**
     * Sync prestige to server
     */
    public void syncPrestige(JsonObject payload) throws IOException
    {
        String token = config.authToken();

        if (token == null || token.isEmpty())
        {
            return;
        }

        RequestBody body = RequestBody.create(RuneAlyticsHttp.JSON, gson.toJson(payload));
        Request request = new Request.Builder()
                .url(config.apiUrl() + LOOT_PRESTIGE_PATH)
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.isSuccessful())
            {
                log.debug("Prestige synced successfully");
            }
            else
            {
                log.warn("Prestige sync failed: HTTP {}", response.code());
            }
        }
    }

    /**
     * Fetch boss stats from server
     */
    public void fetchBossStats(String username, Consumer<List<BossKillStats>> callback) throws IOException
    {
        Request request = new Request.Builder()
                .url(config.apiUrl() + LOOT_STATS_PATH + username)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.isSuccessful() && response.body() != null)
            {
                String json = response.body().string();
                // Parse and convert to BossKillStats
                // For now, return empty list - implement parsing as needed
                callback.accept(new ArrayList<>());
            }
        }
    }
}