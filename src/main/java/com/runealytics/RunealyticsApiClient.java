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
import java.util.concurrent.TimeUnit;

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
