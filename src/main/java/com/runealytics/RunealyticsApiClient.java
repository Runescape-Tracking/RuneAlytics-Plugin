package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.runealytics.RuneAlyticsHttp.JSON;

@Singleton
public class RunealyticsApiClient
{
    private static final Logger log = LoggerFactory.getLogger(RunealyticsApiClient.class);

    private final OkHttpClient  httpClient;
    private final Gson          gson;
    private final RunealyticsConfig config;
    private final RuneAlyticsState  state;

    @Inject
    public RunealyticsApiClient(OkHttpClient httpClient, RunealyticsConfig config,
                                RuneAlyticsState state, Gson gson)
    {
        this.config = config;
        this.gson   = gson;
        this.state  = state;

        this.httpClient = httpClient.newBuilder()
                .connectTimeout(config.syncTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.syncTimeout(),    TimeUnit.SECONDS)
                .writeTimeout(config.syncTimeout(),   TimeUnit.SECONDS)
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  XP BATCH SYNC
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Sends a batched XP payload to {@code /xp/batch}.
     *
     * @param xpGains skill-name (lowercase) → total XP gained in the window
     */
    public void syncXpBatch(Map<String, Integer> xpGains)
    {
        String token    = state.getVerificationCode();
        String username = state.getVerifiedUsername();

        if (token == null || username == null || xpGains.isEmpty()) return;

        JsonObject skillsObj = new JsonObject();
        xpGains.forEach(skillsObj::addProperty);

        JsonObject payload = new JsonObject();
        payload.addProperty("username",  username);
        payload.add("xp_gains",          skillsObj);
        payload.addProperty("timestamp", System.currentTimeMillis() / 1000);

        RequestBody body    = RequestBody.create(JSON, gson.toJson(payload));
        Request     request = new Request.Builder()
                .url(config.apiUrl() + "/xp/batch")
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            @SuppressWarnings("NullableProblems")
            public void onFailure(Call call, IOException e)
            {
                log.warn("Failed to sync XP batch: {}", e.getMessage());
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public void onResponse(Call call, Response response)
            {
                response.close();
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FEATURE FLAGS
    // ═════════════════════════════════════════════════════════════════════════

    public Map<String, Boolean> fetchFeatureFlags(String username)
    {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(config.apiUrl() + "/plugin/features"))
                .newBuilder()
                .addQueryParameter("username", username.toLowerCase())
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept",               "application/json")
                .addHeader("X-RuneAlytics-Client", "RuneLite")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                log.warn("Feature-flag request failed: HTTP {}", response.code());
                return new HashMap<>();
            }

            String     body = response.body() != null ? response.body().string() : "";
            JsonObject json = gson.fromJson(body, JsonObject.class);

            if (json == null || !json.has("flags"))
            {
                log.warn("Feature-flag response missing 'flags' field");
                return new HashMap<>();
            }

            Type                 mapType = new TypeToken<Map<String, Boolean>>() {}.getType();
            Map<String, Boolean> flags   = gson.fromJson(json.get("flags"), mapType);

            log.info("Feature flags received for {}: {}", username, flags);
            return flags != null ? flags : new HashMap<>();
        }
        catch (IOException e)
        {
            log.error("Failed to fetch feature flags for {}: {}", username, e.getMessage());
            return new HashMap<>();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  VERIFICATION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Checks whether {@code token} is a valid verification code for {@code osrsRsn}.
     *
     * @return {@code true} if the server confirms verification
     */
    public boolean verifyToken(String token, String osrsRsn) throws IOException
    {
        if (token == null || token.isEmpty())
        {
            log.debug("No token provided for verification check");
            return false;
        }

        // Normalise to match server expectations: code uppercase, RSN lowercase trimmed
        String normCode = token.trim().toUpperCase();
        String normRsn  = (osrsRsn != null) ? osrsRsn.trim().toLowerCase() : "";

        JsonObject payload = new JsonObject();
        payload.addProperty("verification_code", normCode);
        if (!normRsn.isEmpty())
            payload.addProperty("osrs_rsn", normRsn);

        log.info("[VerifyCheck] POST /verify-runelite rsn={}", normRsn);

        RequestBody body    = RequestBody.create(JSON, gson.toJson(payload));
        Request     request = new Request.Builder()
                .url(config.apiUrl() + "/verify-runelite")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept",       "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            String responseBody = response.body() != null ? response.body().string() : "";
            log.info("[VerifyCheck] HTTP {} body={}", response.code(), responseBody);
            return response.isSuccessful();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  BANK SYNC
    // ═════════════════════════════════════════════════════════════════════════

    public boolean syncBankData(String token, JsonObject bankData) throws IOException
    {
        RequestBody body    = RequestBody.create(JSON, gson.toJson(bankData));
        Request     request = new Request.Builder()
                .url(config.apiUrl() + "/bank/sync")
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.isSuccessful())
            {
                log.debug("Bank data synced successfully");
                return true;
            }
            log.error("Failed to sync bank data. Status: {}", response.code());
            return false;
        }
    }
}
