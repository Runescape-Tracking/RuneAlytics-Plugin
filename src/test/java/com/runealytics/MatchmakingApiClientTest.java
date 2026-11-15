package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Response-parsing and request-building coverage for the matchmaking client,
 * exercising {@code parseMatchSession} (rally, winner, auth, validations, risk),
 * token-refresh detection, primitive responses, and payload coercion.
 */
public class MatchmakingApiClientTest
{
    private MockWebServer server;
    private MatchmakingApiClient client;

    @Before
    public void setUp() throws Exception
    {
        server = new MockWebServer();
        server.start();
        RunealyticsConfig config = org.mockito.Mockito.mock(RunealyticsConfig.class);
        org.mockito.Mockito.when(config.apiUrl()).thenReturn(server.url("/api").toString());
        client = new MatchmakingApiClient(new OkHttpClient(), config, new Gson());
    }

    @After
    public void tearDown() throws Exception
    {
        server.shutdown();
    }

    private RecordedRequest awaitRequest() throws InterruptedException
    {
        RecordedRequest req = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(req);
        return req;
    }

    private static JsonArray oneItem()
    {
        JsonArray arr = new JsonArray();
        JsonObject o = new JsonObject();
        o.addProperty("id", 4151);
        o.addProperty("qty", 1);
        arr.add(o);
        return arr;
    }

    @Test
    public void getMatch_fullResponse_parsesEntireSession() throws Exception
    {
        JsonObject resp = new JsonObject();
        resp.addProperty("player1_osrs_username", "Zezima");
        resp.addProperty("player2_osrs_username", "Durial321");
        resp.addProperty("player1_joined", true);
        resp.addProperty("player2_joined", false);
        resp.addProperty("player1_ready_to_fight", true);
        resp.addProperty("player2_ready_to_fight", false);
        resp.addProperty("world", 330);
        resp.addProperty("zone", "Edge");
        resp.addProperty("status", "Ready");

        JsonObject gearRules = new JsonObject();
        gearRules.addProperty("no_melee", true);
        resp.add("gear_rules", gearRules);

        JsonObject rally = new JsonObject();
        rally.addProperty("x", 1);
        rally.addProperty("y", 2);
        rally.addProperty("plane", 0);
        resp.add("rally", rally);

        JsonObject winner = new JsonObject();
        winner.addProperty("osrs_rsn", "Zezima");
        winner.addProperty("combat_level", 126);
        winner.addProperty("elo", 1500);
        resp.add("winner", winner);

        JsonObject auth = new JsonObject();
        auth.addProperty("token", "TKN");
        auth.addProperty("expires_at", "2026");
        resp.add("authentication", auth);

        JsonObject validations = new JsonObject();
        JsonObject p1v = new JsonObject();
        p1v.addProperty("is_valid", false);
        JsonArray issues = new JsonArray();
        JsonObject issue = new JsonObject();
        issue.addProperty("code", "GEAR");
        issue.addProperty("message", "No melee allowed");
        issue.addProperty("severity", "error");
        issues.add(issue);
        p1v.add("issues", issues);
        validations.add("player1", p1v);
        JsonObject p2v = new JsonObject();
        p2v.addProperty("is_valid", true);
        validations.add("player2", p2v);
        resp.add("player_validations", validations);

        JsonObject p1risk = new JsonObject();
        p1risk.addProperty("is_skulled", true);
        p1risk.addProperty("kept_count", 2);
        p1risk.addProperty("total_value_label", "1m");
        JsonObject mvk = new JsonObject();
        mvk.addProperty("id", 4151);
        mvk.addProperty("name", "Whip");
        mvk.addProperty("qty", 1);
        mvk.addProperty("value", 3000000);
        mvk.addProperty("value_label", "3m");
        p1risk.add("most_valuable_kept", mvk);
        resp.add("player1_risk", p1risk);

        resp.addProperty("match_total_risk_value", 5000000);
        resp.addProperty("match_total_risk_label", "5m");

        server.enqueue(new MockResponse().setResponseCode(200).setBody(new Gson().toJson(resp)));

        MatchmakingApiResult result = client.getMatch(
                "CODE", "MATCH", "Zezima", oneItem(), oneItem(), 1, true, false);

        assertTrue(result.isSuccess());
        MatchmakingSession s = result.getSession();
        assertNotNull(s);
        assertEquals("MATCH", s.getMatchCode());
        assertEquals("Zezima", s.getLocalRsn());
        assertEquals("Durial321", s.getPlayer2Username());
        assertTrue(s.isPlayer1Joined());
        assertFalse(s.isPlayer2Joined());
        assertEquals(330, s.getWorld());
        assertEquals("Edge", s.getZone());
        assertEquals("Ready", s.getStatus());
        assertEquals("{\"no_melee\":true}", s.getGearRules());

        assertNotNull(s.getRally());
        assertEquals(1, s.getRally().getX());
        assertEquals(2, s.getRally().getY());

        assertNotNull(s.getWinner());
        assertEquals(126, s.getWinner().getCombatLevel());
        assertEquals(1500, s.getWinner().getElo());

        assertEquals("TKN", s.getLocalToken());
        assertEquals("2026", s.getTokenExpiresAt());

        assertFalse(s.getLocalValidation().isValid());
        assertEquals("No melee allowed", s.getLocalValidation().firstErrorMessage());

        assertTrue(s.getLocalRisk().isSkulled());
        assertEquals(2, s.getLocalRisk().getKeptCount());
        assertEquals("Whip", s.getLocalRisk().getMostValuableKept().getName());
        assertEquals(5000000L, s.getMatchTotalRiskValue());
        assertEquals("5m", s.getMatchTotalRiskLabel());
    }

    @Test
    public void getMatch_emptyObject_buildsSessionWithDefaults() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        MatchmakingApiResult result = client.getMatch(
                "CODE", "MATCH", "Zezima", null, null, -1, false, false);

        assertTrue(result.isSuccess());
        MatchmakingSession s = result.getSession();
        assertNotNull(s);
        assertNull(s.getRally());
        assertNull(s.getWinner());
        assertEquals("0 gp", s.getMatchTotalRiskLabel());
        assertEquals(MatchmakingSession.RISK_UNKNOWN, s.getPlayer1Risk());
    }

    @Test
    public void getMatch_primitiveTrueResponse_successWithoutSession() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("true"));
        MatchmakingApiResult result = client.getMatch(
                "CODE", "MATCH", "Zezima", null, null, -1, false, false);

        assertTrue(result.isSuccess());
        assertNull(result.getSession());
    }

    @Test
    public void getMatch_nonSuccess_failureCarriesServerMessage() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"message\":\"Match not found\"}"));
        MatchmakingApiResult result = client.getMatch(
                "CODE", "MATCH", "Zezima", null, null, -1, false, false);

        assertFalse(result.isSuccess());
        assertNull(result.getSession());
        assertEquals("Match not found", result.getMessage());
    }

    @Test
    public void getMatch_tokenRefreshDetectedFromEitherField() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"token_refresh\":true}"));
        assertTrue(client.getMatch("C", "M", "R", null, null, -1, false, false).isTokenRefresh());

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"refresh_token\":true}"));
        assertTrue(client.getMatch("C", "M", "R", null, null, -1, false, false).isTokenRefresh());

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"token_refresh\":false}"));
        assertFalse(client.getMatch("C", "M", "R", null, null, -1, false, false).isTokenRefresh());
    }

    @Test
    public void getMatch_buildsExpectedPayload() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client.getMatch("CODE", "MATCH", "Zezima", oneItem(), oneItem(), 3, true, false);

        RecordedRequest req = awaitRequest();
        assertTrue(req.getPath().endsWith("/matchmaking/runelite/get-match"));
        JsonObject sent = new Gson().fromJson(req.getBody().readUtf8(), JsonObject.class);
        assertEquals("CODE", sent.get("verification_code").getAsString());
        assertEquals("MATCH", sent.get("match_code").getAsString());
        assertEquals("Zezima", sent.get("osrs_rsn").getAsString());
        assertEquals(3, sent.get("overhead_icon").getAsInt());
        assertTrue(sent.get("is_skulled").getAsBoolean());
        assertFalse(sent.get("protect_item").getAsBoolean());
        assertEquals(1, sent.getAsJsonArray("player_inventory").size());
    }

    @Test
    public void acceptMatch_nullInventoryAndGear_coercedToEmptyArrays() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client.acceptMatch("CODE", "MATCH", "Zezima", "AUTH", null, null, -1, false, false);

        JsonObject sent = new Gson().fromJson(awaitRequest().getBody().readUtf8(), JsonObject.class);
        assertEquals("AUTH", sent.get("authentication_token").getAsString());
        assertEquals(0, sent.getAsJsonArray("player_inventory").size());
        assertEquals(0, sent.getAsJsonArray("player_gear").size());
    }

    @Test
    public void reportItems_nonArrayElements_omittedFromPayload() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        // Pass an object (not an array) as inventory → field omitted entirely.
        client.reportItems("CODE", "MATCH", "Zezima", "AUTH",
                new JsonObject(), oneItem(), -1, false, false);

        JsonObject sent = new Gson().fromJson(awaitRequest().getBody().readUtf8(), JsonObject.class);
        assertFalse(sent.has("player_inventory"));
        assertTrue(sent.has("player_gear"));
    }

    @Test
    public void reportMatch_includesDeathRsn() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client.reportMatch("CODE", "MATCH", "Zezima", "AUTH", "Durial321");

        JsonObject sent = new Gson().fromJson(awaitRequest().getBody().readUtf8(), JsonObject.class);
        assertEquals("Durial321", sent.get("osrs_rsn_death").getAsString());
    }
}
