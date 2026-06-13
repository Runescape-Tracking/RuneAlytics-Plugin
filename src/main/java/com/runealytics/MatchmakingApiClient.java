package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.ArrayList;
import java.util.List;

@Singleton
public class MatchmakingApiClient
{
    private static final Logger log = LoggerFactory.getLogger(MatchmakingApiClient.class);

    private static final String MATCHMAKING_BASE  = "/matchmaking/runelite";
    private static final String GET_MATCH_PATH    = MATCHMAKING_BASE + "/get-match";
    private static final String ACCEPT_MATCH_PATH = MATCHMAKING_BASE + "/accept";
    private static final String BEGIN_MATCH_PATH  = MATCHMAKING_BASE + "/begin-match";
    private static final String REPORT_MATCH_PATH = MATCHMAKING_BASE + "/report-match";
    private static final String REPORT_ITEMS_PATH = MATCHMAKING_BASE + "/report-items";

    private final OkHttpClient httpClient;
    private final RunealyticsConfig config;
    private final Gson gson;

    @Inject
    public MatchmakingApiClient(OkHttpClient httpClient, RunealyticsConfig config, Gson gson)
    {
        this.httpClient = httpClient;
        this.config     = config;
        this.gson       = gson;
    }

    /**
     * Poll / load a match.  Inventory, gear, overhead prayer, and skull status
     * are sent on every poll so the server can run real-time validation and
     * return updated compliance results — zero rule logic lives in the plugin.
     *
     * @param overheadIconOrdinal {@code HeadIcon.ordinal()} of the active
     *                            overhead prayer, or {@code -1} for none.
     * @param isSkulled           {@code true} if the local player is currently
     *                            skulled (skull icon visible).
     */
    public MatchmakingApiResult getMatch(
            String verificationCode,
            String matchCode,
            String osrsRsn,
            JsonArray playerInventory,
            JsonArray playerGear,
            int overheadIconOrdinal,
            boolean isSkulled
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        addPlayerStateToPayload(payload, playerInventory, playerGear, overheadIconOrdinal, isSkulled);
        return executeRequest(GET_MATCH_PATH, payload, matchCode, osrsRsn);
    }

    /** Overload kept for the initial load call before a snapshot is available. */
    public MatchmakingApiResult getMatch(String verificationCode, String matchCode, String osrsRsn)
            throws IOException
    {
        return getMatch(verificationCode, matchCode, osrsRsn, null, null, -1, false);
    }

    /**
     * Accepts a match for the local player.  Inventory + gear are required by
     * the server to create the initial gear snapshot row.
     */
    public MatchmakingApiResult acceptMatch(
            String verificationCode,
            String matchCode,
            String osrsRsn,
            String authenticationToken,
            JsonArray playerInventory,
            JsonArray playerGear,
            int overheadIconOrdinal,
            boolean isSkulled
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        addPlayerStateToPayload(payload,
                playerInventory != null ? playerInventory : new JsonArray(),
                playerGear      != null ? playerGear      : new JsonArray(),
                overheadIconOrdinal, isSkulled);
        return executeRequest(ACCEPT_MATCH_PATH, payload, matchCode, osrsRsn);
    }

    /**
     * Signals that the local player is at the rally point and ready to fight.
     * Latest inventory + gear are included so the server can re-validate before
     * the match transitions to Fighting.
     */
    public MatchmakingApiResult beginMatch(
            String verificationCode,
            String matchCode,
            String osrsRsn,
            String authenticationToken,
            JsonArray playerInventory,
            JsonArray playerGear,
            int overheadIconOrdinal,
            boolean isSkulled
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        addPlayerStateToPayload(payload, playerInventory, playerGear, overheadIconOrdinal, isSkulled);
        return executeRequest(BEGIN_MATCH_PATH, payload, matchCode, osrsRsn);
    }

    /** Overload for callers that do not yet have a snapshot. */
    public MatchmakingApiResult beginMatch(
            String verificationCode,
            String matchCode,
            String osrsRsn,
            String authenticationToken
    ) throws IOException
    {
        return beginMatch(verificationCode, matchCode, osrsRsn, authenticationToken, null, null, -1, false);
    }

    /**
     * Reports the match result (winner/loser by RSN).
     */
    public MatchmakingApiResult reportMatch(
            String verificationCode,
            String matchCode,
            String osrsRsn,
            String authenticationToken,
            String deathRsn
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        payload.addProperty("osrs_rsn_death", deathRsn);
        return executeRequest(REPORT_MATCH_PATH, payload, matchCode, osrsRsn);
    }

    /**
     * Reports the current inventory and gear snapshot to the dedicated items
     * endpoint (called at the start of a Fighting round and on every change).
     */
    public MatchmakingApiResult reportItems(
            String verificationCode,
            String matchCode,
            String osrsRsn,
            String authenticationToken,
            JsonElement playerInventory,
            JsonElement playerGear,
            int overheadIconOrdinal,
            boolean isSkulled
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        addPlayerStateToPayload(payload,
                playerInventory != null && playerInventory.isJsonArray() ? playerInventory.getAsJsonArray() : null,
                playerGear      != null && playerGear.isJsonArray()      ? playerGear.getAsJsonArray()      : null,
                overheadIconOrdinal, isSkulled);
        return executeRequest(REPORT_ITEMS_PATH, payload, matchCode, osrsRsn);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private JsonObject basePayload(String verificationCode, String matchCode, String osrsRsn)
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("verification_code", verificationCode);
        payload.addProperty("match_code",        matchCode);
        payload.addProperty("osrs_rsn",          osrsRsn);
        return payload;
    }

    /**
     * Adds player_inventory, player_gear, overhead_icon, and is_skulled to a
     * payload.  {@code overheadIconOrdinal} of {@code -1} means no overhead.
     */
    private void addPlayerStateToPayload(
            JsonObject payload,
            JsonArray inventory,
            JsonArray gear,
            int overheadIconOrdinal,
            boolean isSkulled)
    {
        if (inventory != null) payload.add("player_inventory", inventory);
        if (gear      != null) payload.add("player_gear",      gear);

        // ALWAYS send overhead_icon (even -1 = no overhead) so the server
        // clears stale state when the player turns the prayer off.  Previously
        // we omitted the field when it was -1, which made the server hold the
        // last-known value forever.
        payload.addProperty("overhead_icon", overheadIconOrdinal);
        payload.addProperty("is_skulled",    isSkulled);
    }

    private MatchmakingApiResult executeRequest(
            String path,
            JsonObject payload,
            String matchCode,
            String osrsRsn
    ) throws IOException
    {
        RequestBody body    = RequestBody.create(RuneAlyticsHttp.JSON, gson.toJson(payload));
        Request     request = new Request.Builder()
                .url(config.apiUrl() + path)
                .addHeader("Accept", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            String     responseBody  = response.body() != null ? response.body().string() : "";
            JsonObject json          = parseJson(responseBody);
            String     message       = json != null ? getString(json, "message") : null;
            boolean    tokenRefresh  = json != null
                    && (hasTrue(json, "token_refresh") || hasTrue(json, "refresh_token"));

            if (response.isSuccessful())
            {
                // Some endpoints (e.g. accept) return a primitive ("true") not an object.
                // Treat any 2xx as success; only populate a session when we have an object.
                MatchmakingSession session = json != null ? parseMatchSession(json, matchCode, osrsRsn) : null;
                return new MatchmakingApiResult(session, message, responseBody, true, tokenRefresh);
            }

            log.debug("Matchmaking request failed: {} {}", response.code(), responseBody);
            return new MatchmakingApiResult(null, message, responseBody, false, tokenRefresh);
        }
    }

    private JsonObject parseJson(String responseBody)
    {
        if (responseBody == null || responseBody.isEmpty())
        {
            return null;
        }

        // Skip parse attempt for primitive responses (e.g. "true" from acceptMatch)
        // to avoid a misleading JsonSyntaxException in the debug log.
        if (!responseBody.trim().startsWith("{")) return null;

        try
        {
            return gson.fromJson(responseBody, JsonObject.class);
        }
        catch (Exception ex)
        {
            log.debug("Failed to parse matchmaking response: {} | body: {}", ex.getMessage(),
                    responseBody.length() > 200 ? responseBody.substring(0, 200) + "…" : responseBody);
            return null;
        }
    }

    private boolean hasTrue(JsonObject json, String field)
    {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) return false;
        try   { return json.get(field).getAsBoolean(); }
        catch (Exception ignored) { return false; }
    }

    private MatchmakingSession parseMatchSession(JsonObject json, String matchCode, String osrsRsn)
    {
        String  player1       = getString(json, "player1_osrs_username");
        String  player2       = getString(json, "player2_osrs_username");
        boolean player1Joined = getBoolean(json, "player1_joined");
        boolean player2Joined = getBoolean(json, "player2_joined");
        boolean player1Ready  = getBoolean(json, "player1_ready_to_fight");
        boolean player2Ready  = getBoolean(json, "player2_ready_to_fight");
        int     world         = getInt(json,     "world");
        String  zone          = getString(json,  "zone");
        String  status        = getString(json,  "status");
        String  risk          = getString(json,  "risk");
        String  gearRules     = stringify(json.get("gear_rules"));

        MatchmakingRally rally = null;
        if (json.has("rally") && !json.get("rally").isJsonNull())
        {
            JsonObject rallyObj = json.getAsJsonObject("rally");
            rally = new MatchmakingRally(getInt(rallyObj, "x"), getInt(rallyObj, "y"), getInt(rallyObj, "plane"));
        }

        MatchmakingWinner winner = null;
        if (json.has("winner") && !json.get("winner").isJsonNull())
        {
            JsonObject winnerObj  = json.getAsJsonObject("winner");
            winner = new MatchmakingWinner(
                    getString(winnerObj, "osrs_rsn"),
                    getInt(winnerObj,    "combat_level"),
                    getInt(winnerObj,    "elo"));
        }

        String token          = null;
        String tokenExpiresAt = null;
        if (json.has("authentication") && json.get("authentication").isJsonObject())
        {
            JsonObject auth   = json.getAsJsonObject("authentication");
            token             = getString(auth, "token");
            tokenExpiresAt    = getString(auth, "expires_at");
        }

        // ── Parse server-returned validation results ─────────────────────────
        MatchmakingSession.PlayerValidation p1Validation = MatchmakingSession.VALIDATION_UNKNOWN;
        MatchmakingSession.PlayerValidation p2Validation = MatchmakingSession.VALIDATION_UNKNOWN;

        if (json.has("player_validations") && json.get("player_validations").isJsonObject())
        {
            JsonObject validations = json.getAsJsonObject("player_validations");
            p1Validation = parsePlayerValidation(validations, "player1");
            p2Validation = parsePlayerValidation(validations, "player2");
        }

        return new MatchmakingSession(
                matchCode, osrsRsn,
                player1, player2,
                player1Joined, player2Joined,
                player1Ready, player2Ready,
                world, zone, status, risk, gearRules,
                rally, winner, token, tokenExpiresAt,
                p1Validation, p2Validation
        );
    }

    /**
     * Parses a single player's validation block from:
     * {@code {"is_valid": true, "issues": [{code, message, severity}, ...]}}
     */
    private MatchmakingSession.PlayerValidation parsePlayerValidation(JsonObject validations, String playerKey)
    {
        if (!validations.has(playerKey) || !validations.get(playerKey).isJsonObject())
        {
            return MatchmakingSession.VALIDATION_UNKNOWN;
        }

        JsonObject block  = validations.getAsJsonObject(playerKey);
        boolean    valid  = getBoolean(block, "is_valid");

        List<MatchmakingSession.ValidationIssue> issues = new ArrayList<>();
        if (block.has("issues") && block.get("issues").isJsonArray())
        {
            for (JsonElement el : block.getAsJsonArray("issues"))
            {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String code     = getString(obj, "code");
                String message  = getString(obj, "message");
                String severity = getString(obj, "severity");
                if (!code.isEmpty() && !message.isEmpty())
                {
                    issues.add(new MatchmakingSession.ValidationIssue(code, message, severity));
                }
            }
        }

        return new MatchmakingSession.PlayerValidation(valid, issues);
    }

    private String getString(JsonObject json, String field)
    {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) return "";
        JsonElement element = json.get(field);
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private boolean getBoolean(JsonObject json, String field)
    {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) return false;
        JsonElement element = json.get(field);
        if (!element.isJsonPrimitive()) return false;
        try { return element.getAsBoolean(); } catch (Exception ignored) { return false; }
    }

    private int getInt(JsonObject json, String field)
    {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) return 0;
        JsonElement element = json.get(field);
        if (!element.isJsonPrimitive()) return 0;
        try { return element.getAsInt(); } catch (Exception ignored) { return 0; }
    }

    private String stringify(JsonElement element)
    {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        return gson.toJson(element);
    }
}
