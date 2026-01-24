package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;

@Singleton
public class MatchmakingApiClient
{
    private static final Logger log = LoggerFactory.getLogger(MatchmakingApiClient.class);

    private static final String MATCHMAKING_BASE = "/matchmaking/runelite";
    private static final String GET_MATCH_PATH = MATCHMAKING_BASE + "/get-match";
    private static final String ACCEPT_MATCH_PATH = MATCHMAKING_BASE + "/accept";
    private static final String BEGIN_MATCH_PATH = MATCHMAKING_BASE + "/begin-match";
    private static final String REPORT_MATCH_PATH = MATCHMAKING_BASE + "/report-match";
    private static final String REPORT_ITEMS_PATH = MATCHMAKING_BASE + "/report-items";

    private final OkHttpClient httpClient;
    private final RunealyticsConfig config;
    private final Gson gson = new Gson();

    @Inject
    public MatchmakingApiClient(OkHttpClient httpClient, RunealyticsConfig config)
    {
        this.httpClient = httpClient;
        this.config = config;
    }

    public MatchmakingApiResult getMatch(String verificationCode, String matchCode, String osrsRsn) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        return executeRequest(GET_MATCH_PATH, payload, matchCode, osrsRsn);
    }

    public MatchmakingApiResult acceptMatch(
            String verificationCode,
            String matchCode,
            String osrsRsn,
            String authenticationToken
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        return executeRequest(ACCEPT_MATCH_PATH, payload, matchCode, osrsRsn);
    }

    public MatchmakingApiResult beginMatch(
            String verificationCode,
            String matchCode,
            String osrsRsn,
            String authenticationToken
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        return executeRequest(BEGIN_MATCH_PATH, payload, matchCode, osrsRsn);
    }

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

    public MatchmakingApiResult reportItems(
            String verificationCode,
            String matchCode,
            String osrsRsn,
            String authenticationToken,
            JsonElement playerInventory,
            JsonElement playerGear
    ) throws IOException
    {
        JsonObject payload = basePayload(verificationCode, matchCode, osrsRsn);
        payload.addProperty("authentication_token", authenticationToken);
        payload.add("player_inventory", playerInventory);
        payload.add("player_gear", playerGear);
        return executeRequest(REPORT_ITEMS_PATH, payload, matchCode, osrsRsn);
    }

    private JsonObject basePayload(String verificationCode, String matchCode, String osrsRsn)
    {
        JsonObject payload = new JsonObject();
        payload.addProperty("verification_code", verificationCode);
        payload.addProperty("match_code", matchCode);
        payload.addProperty("osrs_rsn", osrsRsn);
        return payload;
    }

    private MatchmakingApiResult executeRequest(
            String path,
            JsonObject payload,
            String matchCode,
            String osrsRsn
    ) throws IOException
    {
        RequestBody body = RequestBody.create(RuneAlyticsHttp.JSON, gson.toJson(payload));
        Request request = new Request.Builder()
                .url(config.apiUrl() + path)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            String responseBody = response.body() != null ? response.body().string() : "";
            ParsedResponse parsedResponse = parseResponseBody(responseBody);
            JsonObject json = parsedResponse.getJsonObject();
            String message = json != null ? getString(json, "message") : parsedResponse.getPrimitiveMessage();
            boolean tokenRefresh = json != null && (hasTrue(json, "token_refresh") || hasTrue(json, "refresh_token"));
            boolean success = response.isSuccessful() && (json != null || parsedResponse.isPrimitiveSuccess());

            if (success && json != null)
            {
                MatchmakingSession session = parseMatchSession(json, matchCode, osrsRsn);
                return new MatchmakingApiResult(session, message, responseBody, true, tokenRefresh);
            }

            if (success)
            {
                return new MatchmakingApiResult(null, message, responseBody, true, tokenRefresh);
            }

            if (!response.isSuccessful())
            {
                log.debug("Matchmaking request failed: {} {}", response.code(), responseBody);
            }

            return new MatchmakingApiResult(null, message, responseBody, false, tokenRefresh);
        }
    }

    private ParsedResponse parseResponseBody(String responseBody)
    {
        if (responseBody == null)
        {
            return ParsedResponse.empty();
        }

        String trimmedBody = responseBody.trim();
        if (trimmedBody.isEmpty())
        {
            return ParsedResponse.empty();
        }

        if (isLikelyPrimitiveResponse(trimmedBody))
        {
            return ParsedResponse.fromRaw(trimmedBody);
        }

        try
        {
            JsonReader reader = new JsonReader(new StringReader(trimmedBody));
            reader.setLenient(true);
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || element.isJsonNull())
            {
                return ParsedResponse.empty();
            }

            if (element.isJsonObject())
            {
                return ParsedResponse.fromObject(element.getAsJsonObject());
            }

            log.debug("Matchmaking response was not a JSON object: {}", element);
            return ParsedResponse.fromPrimitive(element);
        }
        catch (Exception ex)
        {
            log.debug("Failed to parse matchmaking response", ex);
            return ParsedResponse.fromRaw(trimmedBody);
        }
    }

    private boolean isLikelyPrimitiveResponse(String responseBody)
    {
        if (responseBody.isEmpty())
        {
            return false;
        }

        char firstChar = responseBody.charAt(0);
        if (firstChar == '{' || firstChar == '[' || firstChar == '"')
        {
            return false;
        }

        String lower = responseBody.toLowerCase(Locale.ROOT);
        return lower.startsWith("true")
                || lower.startsWith("false")
                || lower.startsWith("1")
                || lower.startsWith("0");
    }

    private static class ParsedResponse
    {
        private final JsonObject jsonObject;
        private final boolean primitiveSuccess;
        private final String primitiveMessage;

        private ParsedResponse(JsonObject jsonObject, boolean primitiveSuccess, String primitiveMessage)
        {
            this.jsonObject = jsonObject;
            this.primitiveSuccess = primitiveSuccess;
            this.primitiveMessage = primitiveMessage;
        }

        private static ParsedResponse empty()
        {
            return new ParsedResponse(null, false, "");
        }

        private static ParsedResponse fromObject(JsonObject jsonObject)
        {
            return new ParsedResponse(jsonObject, false, "");
        }

        private static ParsedResponse fromPrimitive(JsonElement element)
        {
            if (!element.isJsonPrimitive())
            {
                return new ParsedResponse(null, false, element.toString());
            }

            boolean success = false;
            String message = "";

            try
            {
                success = element.getAsBoolean();
            }
            catch (Exception ignored)
            {
                success = false;
            }

            try
            {
                if (element.getAsJsonPrimitive().isNumber())
                {
                    success = element.getAsInt() != 0;
                }
                else if (element.getAsJsonPrimitive().isString())
                {
                    message = element.getAsString();
                }
            }
            catch (Exception ignored)
            {
                // Keep best-effort success/message values.
            }

            return new ParsedResponse(null, success, message);
        }

        private static ParsedResponse fromRaw(String rawBody)
        {
            String lower = rawBody.toLowerCase(Locale.ROOT);

            if (lower.startsWith("true"))
            {
                return new ParsedResponse(null, true, "");
            }

            if (lower.startsWith("false"))
            {
                return new ParsedResponse(null, false, "");
            }

            if (lower.startsWith("1"))
            {
                return new ParsedResponse(null, true, "");
            }

            if (lower.startsWith("0"))
            {
                return new ParsedResponse(null, false, "");
            }

            return new ParsedResponse(null, false, rawBody);
        }

        private JsonObject getJsonObject()
        {
            return jsonObject;
        }

        private boolean isPrimitiveSuccess()
        {
            return primitiveSuccess;
        }

        private String getPrimitiveMessage()
        {
            return primitiveMessage;
        }
    }

    private boolean hasTrue(JsonObject json, String field)
    {
        if (json == null || !json.has(field) || json.get(field).isJsonNull())
        {
            return false;
        }

        try
        {
            return json.get(field).getAsBoolean();
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private MatchmakingSession parseMatchSession(JsonObject json, String matchCode, String osrsRsn)
    {
        String player1 = getString(json, "player1_osrs_username");
        String player2 = getString(json, "player2_osrs_username");
        boolean player1Joined = getBoolean(json, "player1_joined");
        boolean player2Joined = getBoolean(json, "player2_joined");
        boolean player1Ready = getBoolean(json, "player1_ready_to_fight");
        boolean player2Ready = getBoolean(json, "player2_ready_to_fight");
        int world = getInt(json, "world");
        String zone = getString(json, "zone");
        String status = getString(json, "status");
        String risk = getString(json, "risk");
        String gearRules = stringify(json.get("gear_rules"));

        MatchmakingRally rally = null;
        if (json.has("rally") && !json.get("rally").isJsonNull())
        {
            JsonObject rallyObj = json.getAsJsonObject("rally");
            int x = getInt(rallyObj, "x");
            int y = getInt(rallyObj, "y");
            int plane = getInt(rallyObj, "plane");
            rally = new MatchmakingRally(x, y, plane);
        }

        MatchmakingWinner winner = null;
        if (json.has("winner") && !json.get("winner").isJsonNull())
        {
            JsonObject winnerObj = json.getAsJsonObject("winner");
            String winnerRsn = getString(winnerObj, "osrs_rsn");
            int combatLevel = getInt(winnerObj, "combat_level");
            int elo = getInt(winnerObj, "elo");
            winner = new MatchmakingWinner(winnerRsn, combatLevel, elo);
        }

        String token = null;
        String tokenExpiresAt = null;
        if (json.has("authentication") && json.get("authentication").isJsonObject())
        {
            JsonObject auth = json.getAsJsonObject("authentication");
            token = getString(auth, "token");
            tokenExpiresAt = getString(auth, "expires_at");
        }

        return new MatchmakingSession(
                matchCode,
                osrsRsn,
                player1,
                player2,
                player1Joined,
                player2Joined,
                player1Ready,
                player2Ready,
                world,
                zone,
                status,
                risk,
                gearRules,
                rally,
                winner,
                token,
                tokenExpiresAt
        );
    }

    private String getString(JsonObject json, String field)
    {
        if (json == null || !json.has(field) || json.get(field).isJsonNull())
        {
            return "";
        }

        JsonElement element = json.get(field);
        if (element.isJsonPrimitive())
        {
            return element.getAsString();
        }

        return element.toString();
    }

    private boolean getBoolean(JsonObject json, String field)
    {
        if (json == null || !json.has(field) || json.get(field).isJsonNull())
        {
            return false;
        }

        JsonElement element = json.get(field);
        if (element.isJsonPrimitive())
        {
            try
            {
                return element.getAsBoolean();
            }
            catch (Exception ignored)
            {
                return false;
            }
        }

        return false;
    }

    private int getInt(JsonObject json, String field)
    {
        if (json == null || !json.has(field) || json.get(field).isJsonNull())
        {
            return 0;
        }

        JsonElement element = json.get(field);
        if (element.isJsonPrimitive())
        {
            try
            {
                return element.getAsInt();
            }
            catch (Exception ignored)
            {
                return 0;
            }
        }

        return 0;
    }

    private String stringify(JsonElement element)
    {
        if (element == null || element.isJsonNull())
        {
            return "";
        }

        if (element.isJsonPrimitive())
        {
            return element.getAsString();
        }

        return gson.toJson(element);
    }
}
