package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import java.util.List;
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

        if (token == null || token.isEmpty())
        {
            log.warn("[XP Batch] Skipping — no verification token in state");
            return;
        }
        if (username == null || username.isEmpty())
        {
            log.warn("[XP Batch] Skipping — no username in state");
            return;
        }
        if (xpGains.isEmpty())
        {
            log.debug("[XP Batch] Skipping — empty gains map");
            return;
        }

        JsonObject skillsObj = new JsonObject();
        xpGains.forEach(skillsObj::addProperty);

        JsonObject payload = new JsonObject();
        payload.addProperty("username",     username);
        payload.add("xp_gains",             skillsObj);
        payload.addProperty("timestamp",    System.currentTimeMillis() / 1000);
        payload.addProperty("game_mode",    state.getCurrentGameMode()    != null ? state.getCurrentGameMode()    : "regular");
        payload.addProperty("account_type", state.getCurrentAccountSubtype() != null ? state.getCurrentAccountSubtype() : "normal");

        // Optional shared location object (top-level for the batch). Omitted
        // entirely when unavailable so the server's nullable handling and older
        // website code keep working.
        PlayerLocationSnapshot location = state.getCurrentLocation();
        if (location != null)
        {
            JsonObject locationJson = location.toJson();
            payload.add("location", locationJson);
            log.debug("[XP Batch] location payload: {}", locationJson);
        }
        else
        {
            log.debug("[XP Batch] no location captured — omitting location field");
        }

        String payloadJson = gson.toJson(payload);
        String url         = config.apiUrl() + "/xp/batch";

        log.info("[XP Batch] POST {} | skills={} payload={}", url, xpGains.size(), payloadJson);

        RequestBody body    = RequestBody.create(JSON, payloadJson);
        Request     request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type",  "application/json")
                .addHeader("Accept",        "application/json")
                .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            @SuppressWarnings("NullableProblems")
            public void onFailure(Call call, IOException e)
            {
                log.warn("[XP Batch] Network failure: {}", e.getMessage());
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public void onResponse(Call call, Response response)
            {
                try
                {
                    String body = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful())
                        log.info("[XP Batch] OK HTTP {} — {}", response.code(), body);
                    else
                        log.warn("[XP Batch] FAILED HTTP {} — {}", response.code(), body);
                }
                catch (IOException e)
                {
                    log.warn("[XP Batch] Could not read response body: {}", e.getMessage());
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PRIVACY SETTINGS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Pushes the player's current privacy preferences to {@code /plugin/privacy}
     * so the website knows whether the account wants to be visible.
     *
     * <p>Sent whenever a privacy toggle changes and once on login, so the site's
     * stored visibility always mirrors the in-client setting. {@code bank_privacy}
     * controls who can see bank/wealth; {@code player_visibility} controls who can
     * see online status and map location ({@code public}/{@code friends}/{@code private}).</p>
     */
    public void syncPrivacySettings(PrivacySetting bankPrivacy, PrivacySetting playerVisibility)
    {
        String token    = state.getVerificationCode();
        String username = state.getVerifiedUsername();

        if (token == null || token.isEmpty())
        {
            log.debug("[Privacy] Skipping — no verification token in state");
            return;
        }
        if (username == null || username.isEmpty())
        {
            log.debug("[Privacy] Skipping — no username in state");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("username",          username);
        payload.addProperty("bank_privacy",      wireValue(bankPrivacy));
        payload.addProperty("player_visibility", wireValue(playerVisibility));
        payload.addProperty("timestamp",         System.currentTimeMillis() / 1000);

        String payloadJson = gson.toJson(payload);
        String url         = config.apiUrl() + "/plugin/privacy";

        log.info("[Privacy] POST {} | bank={} player={}", url,
                wireValue(bankPrivacy), wireValue(playerVisibility));

        RequestBody body    = RequestBody.create(JSON, payloadJson);
        Request     request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type",  "application/json")
                .addHeader("Accept",        "application/json")
                .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            @SuppressWarnings("NullableProblems")
            public void onFailure(Call call, IOException e)
            {
                log.warn("[Privacy] Network failure: {}", e.getMessage());
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public void onResponse(Call call, Response response)
            {
                try
                {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful())
                        log.debug("[Privacy] OK HTTP {} — {}", response.code(), responseBody);
                    else
                        log.warn("[Privacy] FAILED HTTP {} — {}", response.code(), responseBody);
                }
                catch (IOException e)
                {
                    log.warn("[Privacy] Could not read response body: {}", e.getMessage());
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HEARTBEAT  (live map location)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Sends a periodic heartbeat to {@code /plugin/heartbeat} while the player is
     * logged in. Carries the live map location plus the player's friends and
     * ignore lists (as reported by OSRS) so the website can render the player on
     * the live map and enforce who may see them:
     *
     * <ul>
     *   <li>{@code public}  — anyone can see the location.</li>
     *   <li>{@code friends} — only players on {@code friends} and/or players who
     *       are mutually tracking each other may see the location.</li>
     *   <li>{@code private} — nobody, including the server's own database/logs,
     *       ever sees the real location. See note below.</li>
     * </ul>
     *
     * <p>The {@code ignores} list lets the server hide the player from anyone the
     * player has ignored regardless of the chosen visibility. {@code public}/
     * {@code friends} access control is enforced server-side — this client only
     * reports the raw inputs for those two cases.</p>
     *
     * <p><b>{@code private} is the one case NOT left to the server.</b> The
     * caller ({@code RuneAlyticsPlugin.sendHeartbeatTick}) substitutes
     * {@link PlayerLocationSnapshot#privacyDecoy()} for the real location
     * <i>before</i> this method is ever invoked, so by the time {@code location}
     * reaches here it is already a harmless, obviously-fake Grand Exchange
     * position on world {@link PlayerLocationSnapshot#PRIVACY_DECOY_WORLD}. Do
     * not "optimize" this method to special-case {@code visibility == PRIVATE}
     * and skip sending {@code location} — that would require trusting that no
     * future change to this method ever re-introduces a real-location path. The
     * decoy already prevents the real coordinates from existing in this call's
     * scope at all, which is the stronger guarantee.</p>
     *
     * <p>Also carries the player's current worn equipment and inventory so the
     * website can show "what they're wearing / holding" alongside the map dot.
     * {@code gear_visibility} mirrors {@code bank_privacy} from
     * {@link #syncPrivacySettings} — the same setting that gates bank/wealth
     * visibility also gates who may see equipment/inventory, since both are
     * "what gear/items does this account have" data. The plugin always
     * uploads; the server is responsible for honoring the privacy flag when
     * deciding who else may see the fields.</p>
     *
     * @param location      current world location (omitted when {@code null})
     * @param friends       in-game friends list names
     * @param ignores       in-game ignore list names
     * @param visibility    the player's map/online {@link PrivacySetting}
     * @param equipment     worn equipment as {@code [{slot, id, qty}, ...]}
     * @param inventory     inventory contents as {@code [{id, qty}, ...]}
     * @param gearVisibility the player's bank/gear {@link PrivacySetting}, reused to gate equipment/inventory
     * @param xpPreview     <b>non-authoritative</b> snapshot of XP gained so far in the
     *                      in-progress 30s batch window (skill name → XP), from
     *                      {@link XpTrackerManager#peekPendingGains()}. May be empty/null
     *                      when no window is open. This is a live preview only — the
     *                      authoritative XP totals come exclusively from
     *                      {@link #syncXpBatch}'s {@code /xp/batch} POST, which is
     *                      unaffected by this field. The server MUST NOT add
     *                      {@code xp_preview} values to a player's XP totals, or XP will
     *                      be double-counted once the same gains are flushed via
     *                      {@code /xp/batch} moments later.
     */
    public void sendHeartbeat(PlayerLocationSnapshot location, List<String> friends,
                              List<String> ignores, PrivacySetting visibility,
                              JsonArray equipment, JsonArray inventory, PrivacySetting gearVisibility,
                              Map<String, Integer> xpPreview)
    {
        String token    = state.getVerificationCode();
        String username = state.getVerifiedUsername();

        if (token == null || token.isEmpty())
        {
            log.debug("[Heartbeat] Skipping — no verification token in state");
            return;
        }
        if (username == null || username.isEmpty())
        {
            log.debug("[Heartbeat] Skipping — no username in state");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("username",        username);
        payload.addProperty("visibility",      wireValue(visibility));
        payload.addProperty("gear_visibility", wireValue(gearVisibility));
        payload.add("friends",                 toJsonArray(friends));
        payload.add("ignores",                 toJsonArray(ignores));
        payload.add("equipment",               equipment != null ? equipment : new JsonArray());
        payload.add("inventory",               inventory != null ? inventory : new JsonArray());

        // Non-authoritative — see javadoc above. Omitted entirely when empty so
        // older server versions that don't expect this field see nothing new.
        if (xpPreview != null && !xpPreview.isEmpty())
        {
            JsonObject xpPreviewJson = new JsonObject();
            for (Map.Entry<String, Integer> e : xpPreview.entrySet())
            {
                xpPreviewJson.addProperty(e.getKey(), e.getValue());
            }
            payload.add("xp_preview", xpPreviewJson);
        }

        payload.addProperty("timestamp",       System.currentTimeMillis() / 1000);

        // Location is optional — omit entirely when unavailable so the server's
        // nullable handling keeps working.
        if (location != null)
        {
            payload.add("location", location.toJson());
        }

        String payloadJson = gson.toJson(payload);
        String url         = config.apiUrl() + "/plugin/heartbeat";

        log.debug("[Heartbeat] POST {} | visibility={} gear_visibility={} friends={} ignores={} "
                        + "equipment={} inventory={} location={}",
                url, wireValue(visibility), wireValue(gearVisibility),
                friends != null ? friends.size() : 0,
                ignores != null ? ignores.size() : 0,
                equipment != null ? equipment.size() : 0,
                inventory != null ? inventory.size() : 0,
                location != null);

        RequestBody body    = RequestBody.create(JSON, payloadJson);
        Request     request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type",  "application/json")
                .addHeader("Accept",        "application/json")
                .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            @SuppressWarnings("NullableProblems")
            public void onFailure(Call call, IOException e)
            {
                log.warn("[Heartbeat] Network failure: {}", e.getMessage());
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public void onResponse(Call call, Response response)
            {
                try
                {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful())
                    {
                        log.debug("[Heartbeat] OK HTTP {} — {}", response.code(), responseBody);
                        // The website replies with the players this account is
                        // allowed to see; cache them for the minimap overlay.
                        List<MapPlayer> players = parseVisiblePlayers(responseBody);
                        state.setVisibleMapPlayers(players);
                        log.debug("[Heartbeat] {} visible map player(s) received", players.size());
                    }
                    else
                    {
                        log.warn("[Heartbeat] FAILED HTTP {} — {}", response.code(), responseBody);
                    }
                }
                catch (IOException e)
                {
                    log.warn("[Heartbeat] Could not read response body: {}", e.getMessage());
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    /**
     * Parses the {@code players} array from a heartbeat response into the list of
     * players the local account may see on the live map. Tolerant of a missing /
     * malformed array (returns an empty list) so a bad response never throws.
     */
    private List<MapPlayer> parseVisiblePlayers(String responseBody)
    {
        try
        {
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            if (json == null || !json.has("players") || !json.get("players").isJsonArray())
            {
                return new java.util.ArrayList<>();
            }
            Type listType = new TypeToken<List<MapPlayer>>() {}.getType();
            List<MapPlayer> players = gson.fromJson(json.get("players"), listType);
            return players != null ? players : new java.util.ArrayList<>();
        }
        catch (RuntimeException e)
        {
            log.warn("[Heartbeat] Could not parse visible players: {}", e.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    /** Normalizes a {@link PrivacySetting} to its lowercase wire token. */
    private static String wireValue(PrivacySetting setting)
    {
        return (setting != null ? setting : PrivacySetting.PUBLIC).name().toLowerCase();
    }

    /** Builds a JSON string array, tolerating a {@code null} input list. */
    private static JsonArray toJsonArray(List<String> values)
    {
        JsonArray arr = new JsonArray();
        if (values != null)
        {
            for (String v : values)
            {
                if (v != null && !v.isEmpty()) arr.add(v);
            }
        }
        return arr;
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

            if (json == null || !json.has("flags") || !json.get("flags").isJsonObject())
            {
                log.warn("Feature-flag response missing or invalid 'flags' object");
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
        catch (com.google.gson.JsonSyntaxException e)
        {
            // Malformed body / unexpected JSON shape must not bubble out of the
            // 60s feature-flag poll or the login executor task.
            log.warn("Feature-flag response was not valid JSON for {}: {}", username, e.getMessage());
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
        return verifyTokenWithDetail(token, osrsRsn) == null;
    }

    /**
     * Like {@link #verifyToken} but returns the server's human-readable error
     * message on failure instead of a plain boolean, so the UI can show the
     * exact reason rather than a generic fallback string.
     *
     * @return {@code null} on success; a non-empty error string on failure
     */
    public String verifyTokenWithDetail(String token, String osrsRsn) throws IOException
    {
        if (token == null || token.isEmpty())
        {
            log.debug("No token provided for verification check");
            return "No verification code provided.";
        }

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
            if (response.isSuccessful()) return null;

            // Surface the server's own message so the user sees the real reason.
            String serverMsg = RuneAlyticsJson.extractMessage(responseBody);
            return (serverMsg != null && !serverMsg.isEmpty())
                    ? serverMsg
                    : "Verification failed (HTTP " + response.code() + ").";
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
