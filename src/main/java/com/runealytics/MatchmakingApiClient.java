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
    private static final String ENGAGE_COMBAT_PATH = MATCHMAKING_BASE + "/engage-combat";
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
     * Polls or loads a match. Inventory, gear, overhead prayer, and skull status
     * are sent so the server can validate and return updated compliance results.
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
            boolean isSkulled,
            boolean protectItem
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        addPlayerStateToPayload(payload, playerInventory, playerGear, overheadIconOrdinal, isSkulled, protectItem);
        return executeRequest(GET_MATCH_PATH, payload, matchCode, osrsRsn);
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
            boolean isSkulled,
            boolean protectItem
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        addPlayerStateToPayload(payload,
                playerInventory != null ? playerInventory : new JsonArray(),
                playerGear      != null ? playerGear      : new JsonArray(),
                overheadIconOrdinal, isSkulled, protectItem);
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
            boolean isSkulled,
            boolean protectItem
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        addPlayerStateToPayload(payload, playerInventory, playerGear, overheadIconOrdinal, isSkulled, protectItem);
        return executeRequest(BEGIN_MATCH_PATH, payload, matchCode, osrsRsn);
    }

    /**
     * Signals that combat has started between the two match participants. This
     * is the only call that transitions the match from Ready to Fighting
     * server-side.
     *
     * <p>The server treats this idempotently, so both clients may report the
     * same engagement safely.</p>
     */
    public MatchmakingApiResult engageCombat(
            String verificationCode,
            String matchCode,
            String osrsRsn,
            String authenticationToken,
            JsonArray playerInventory,
            JsonArray playerGear,
            int overheadIconOrdinal,
            boolean isSkulled,
            boolean protectItem
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        addPlayerStateToPayload(payload, playerInventory, playerGear, overheadIconOrdinal, isSkulled, protectItem);
        return executeRequest(ENGAGE_COMBAT_PATH, payload, matchCode, osrsRsn);
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
            boolean isSkulled,
            boolean protectItem
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        addPlayerStateToPayload(payload,
                playerInventory != null && playerInventory.isJsonArray() ? playerInventory.getAsJsonArray() : null,
                playerGear      != null && playerGear.isJsonArray()      ? playerGear.getAsJsonArray()      : null,
                overheadIconOrdinal, isSkulled, protectItem);
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
            boolean isSkulled,
            boolean protectItem)
    {
        // Lean item data — {id, qty} / {slot, id, qty}; the website prices each
        // item.
        if (inventory != null) payload.add("player_inventory", inventory);
        if (gear      != null) payload.add("player_gear",      gear);

        // Always send overhead_icon (even -1 = no overhead) so the server clears
        // state when the player turns the prayer off.
        payload.addProperty("overhead_icon", overheadIconOrdinal);
        payload.addProperty("is_skulled",    isSkulled);

        // Protect Item prayer — drives the OSRS keep-on-death count the server
        // uses for the informational risk-value display.
        payload.addProperty("protect_item",  protectItem);
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
        String  gearRules     = stringify(json.get("gear_rules"));

        MatchmakingRally rally = null;
        if (json.has("rally") && json.get("rally").isJsonObject())
        {
            JsonObject rallyObj = json.getAsJsonObject("rally");
            rally = new MatchmakingRally(getInt(rallyObj, "x"), getInt(rallyObj, "y"), getInt(rallyObj, "plane"));
        }

        MatchmakingWinner winner = null;
        if (json.has("winner") && json.get("winner").isJsonObject())
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

        MatchmakingSession session = new MatchmakingSession(
                matchCode, osrsRsn,
                player1, player2,
                player1Joined, player2Joined,
                player1Ready, player2Ready,
                world, zone, status, gearRules,
                rally, winner, token, tokenExpiresAt,
                p1Validation, p2Validation
        );

        // ── Server-computed risk-value display (no gold wager) ───────────────
        MatchmakingSession.RiskInfo p1Risk = parseRiskInfo(json, "player1_risk");
        MatchmakingSession.RiskInfo p2Risk = parseRiskInfo(json, "player2_risk");
        long   matchTotalRisk      = json.has("match_total_risk_value")
                ? getLong(json, "match_total_risk_value") : 0L;
        String matchTotalRiskLabel = getString(json, "match_total_risk_label");
        if (matchTotalRiskLabel.isEmpty()) matchTotalRiskLabel = "0 gp";
        session.setRiskData(p1Risk, p2Risk, matchTotalRisk, matchTotalRiskLabel);

        return session;
    }

    /**
     * Parses a player's risk block:
     * {@code {is_skulled, protect_item, kept_count, skull_label, kept_label,
     *         total_value, total_value_label, risk_value, risk_value_label,
     *         most_valuable_kept, kept_items[], at_risk_items[]}}
     */
    private MatchmakingSession.RiskInfo parseRiskInfo(JsonObject json, String key)
    {
        if (!json.has(key) || !json.get(key).isJsonObject())
        {
            return MatchmakingSession.RISK_UNKNOWN;
        }

        JsonObject obj = json.getAsJsonObject(key);

        return new MatchmakingSession.RiskInfo(
                getBoolean(obj, "is_skulled"),
                getBoolean(obj, "protect_item"),
                getInt(obj,     "kept_count"),
                getString(obj,  "skull_label"),
                getString(obj,  "kept_label"),
                getLong(obj,    "total_value"),
                getString(obj,  "total_value_label"),
                getLong(obj,    "risk_value"),
                getString(obj,  "risk_value_label"),
                parseRiskItem(obj.get("most_valuable_kept")),
                parseRiskItems(obj.get("kept_items")),
                parseRiskItems(obj.get("at_risk_items"))
        );
    }

    private List<MatchmakingSession.RiskItem> parseRiskItems(JsonElement el)
    {
        List<MatchmakingSession.RiskItem> items = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return items;
        for (JsonElement e : el.getAsJsonArray())
        {
            MatchmakingSession.RiskItem item = parseRiskItem(e);
            if (item != null) items.add(item);
        }
        return items;
    }

    private MatchmakingSession.RiskItem parseRiskItem(JsonElement el)
    {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject obj = el.getAsJsonObject();
        return new MatchmakingSession.RiskItem(
                getInt(obj,    "id"),
                getString(obj, "name"),
                getInt(obj,    "qty"),
                getLong(obj,   "value"),
                getString(obj, "value_label")
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

    private long getLong(JsonObject json, String field)
    {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) return 0L;
        JsonElement element = json.get(field);
        if (!element.isJsonPrimitive()) return 0L;
        try { return element.getAsLong(); } catch (Exception ignored) { return 0L; }
    }

    private String stringify(JsonElement element)
    {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        return gson.toJson(element);
    }
}
