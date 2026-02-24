package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import com.google.gson.reflect.TypeToken;
import okhttp3.HttpUrl;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;


@Singleton
public class RunealyticsApiClient
{
    private static final Logger log = LoggerFactory.getLogger(RunealyticsApiClient.class);

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final RunealyticsConfig config;

    @Inject
    public RunealyticsApiClient(RunealyticsConfig config)
    {
        this.config = config;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.syncTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.syncTimeout(), TimeUnit.SECONDS)
                .writeTimeout(config.syncTimeout(), TimeUnit.SECONDS)
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Feature flags
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fetches the feature-flag map for {@code username} from the server.
     *
     * <p>Called on {@code GameStateChanged → LOGGED_IN} from a background
     * thread.  The returned map is keyed by feature name (e.g.
     * {@code "loot_tracker"}, {@code "match_finder"}) and values are
     * {@code true} (tab visible) or {@code false} (tab hidden).
     *
     * <p>On any network or parse error an empty map is returned, which
     * causes {@link RuneAlyticsPanel#applyFeatureFlags} to leave all tabs
     * in their current state (safe default).
     *
     * @param username the verified RSN (lower-case)
     * @return mutable map of {@code featureKey → enabled}; never {@code null}
     */
    public Map<String, Boolean> fetchFeatureFlags(String username)
    {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(config.apiUrl() + "/plugin/features"))
                .newBuilder()
                .addQueryParameter("username", username.toLowerCase())
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept",       "application/json")
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

            String body = response.body() != null ? response.body().string() : "";
            JsonObject json = gson.fromJson(body, JsonObject.class);

            if (json == null || !json.has("flags"))
            {
                log.warn("Feature-flag response missing 'flags' field");
                return new HashMap<>();
            }

            // Deserialise the inner "flags" object as Map<String, Boolean>
            Type mapType = new TypeToken<Map<String, Boolean>>(){}.getType();
            Map<String, Boolean> flags = gson.fromJson(json.get("flags"), mapType);

            log.info("Feature flags received for {}: {}", username, flags);
            return flags != null ? flags : new HashMap<>();
        }
        catch (IOException e)
        {
            log.error("Failed to fetch feature flags for {}: {}", username, e.getMessage());
            return new HashMap<>();
        }
    }

    public boolean verifyToken(String token, String osrsRsn) throws IOException
    {
        if (token == null || token.isEmpty())
        {
            log.debug("No token provided for verification");
            return false;
        }

        // The endpoint might need both verification_code AND osrs_rsn
        JsonObject payload = new JsonObject();
        payload.addProperty("verification_code", token);

        if (osrsRsn != null && !osrsRsn.isEmpty())
        {
            payload.addProperty("osrs_rsn", osrsRsn);
        }

        log.info("Checking verification for RSN: {} with code: {}", osrsRsn, token);

        RequestBody body = RequestBody.create(RuneAlyticsHttp.JSON, gson.toJson(payload));
        Request request = new Request.Builder()
                .url(config.apiUrl() + "/check-verification")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            String responseBody = response.body() != null ? response.body().string() : "";

            log.info("Verification check response: HTTP {} - Body: {}", response.code(), responseBody);

            if (!response.isSuccessful())
            {
                log.warn("Verification check failed: HTTP {} - {}", response.code(), responseBody);
                return false;
            }

            if (responseBody.isEmpty())
            {
                log.warn("Empty response from verification check");
                return false;
            }

            try
            {
                JsonObject result = gson.fromJson(responseBody, JsonObject.class);

                if (result == null)
                {
                    log.warn("Could not parse verification response");
                    return false;
                }

                // Check multiple possible response formats
                boolean verified = false;

                if (result.has("verified"))
                {
                    verified = result.get("verified").getAsBoolean();
                }
                else if (result.has("success"))
                {
                    verified = result.get("success").getAsBoolean();
                }
                else if (result.has("is_verified"))
                {
                    verified = result.get("is_verified").getAsBoolean();
                }

                log.info("Token verification result for {}: verified={}", osrsRsn, verified);

                return verified;
            }
            catch (Exception e)
            {
                log.error("Error parsing verification response: {}", responseBody, e);
                return false;
            }
        }
    }

    public boolean autoVerifyAccount(String token, String username) throws IOException
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("token", token);
        payload.addProperty("username", username);
        payload.addProperty("verification_method", "plugin");

        RequestBody body = RequestBody.create(RuneAlyticsHttp.JSON, gson.toJson(payload));
        Request request = new Request.Builder()
                .url(config.apiUrl() + "/check-verification")
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                return false;
            }

            JsonObject result = gson.fromJson(response.body().string(), JsonObject.class);
            boolean success = result != null
                    && result.has("success")
                    && result.get("success").getAsBoolean();

            if (success)
            {
                log.info("Account {} auto-verified successfully", username);
            }

            return success;
        }
    }

    public boolean syncBankData(String token, JsonObject bankData) throws IOException
    {
        RequestBody body = RequestBody.create(RuneAlyticsHttp.JSON, gson.toJson(bankData));
        Request request = new Request.Builder()
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

    public boolean recordXpGain(String token, JsonObject xpData) throws IOException
    {
        RequestBody body = RequestBody.create(RuneAlyticsHttp.JSON, gson.toJson(xpData));
        Request request = new Request.Builder()
                .url(config.apiUrl() + "/xp/record")
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.isSuccessful())
            {
                log.debug("XP gain recorded successfully");
                return true;
            }

            log.error("Failed to record XP gain. Status: {}", response.code());
            return false;
        }
    }

    public boolean testConnection() throws IOException
    {
        Request request = new Request.Builder()
                .url(config.apiUrl() + "/health")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            return response.isSuccessful();
        }
    }
}
