package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
 * Request-building and response-parsing coverage for the general API client,
 * driven by a real OkHttp stack against MockWebServer. Covers verification,
 * feature flags, bank sync, and the async XP/privacy/heartbeat payloads.
 */
public class RunealyticsApiClientTest
{
    private MockWebServer server;
    private RuneAlyticsState state;
    private RunealyticsApiClient client;

    @Before
    public void setUp() throws Exception
    {
        server = new MockWebServer();
        server.start();

        RunealyticsConfig config = org.mockito.Mockito.mock(RunealyticsConfig.class);
        org.mockito.Mockito.when(config.apiUrl()).thenReturn(server.url("/api").toString());
        org.mockito.Mockito.when(config.syncTimeout()).thenReturn(5);

        state = new RuneAlyticsState();
        state.setVerificationCode("TOKEN");
        state.setVerifiedUsername("Zezima");

        client = new RunealyticsApiClient(new OkHttpClient(), config, state, new Gson());
    }

    @After
    public void tearDown() throws Exception
    {
        server.shutdown();
    }

    private RecordedRequest awaitRequest() throws InterruptedException
    {
        RecordedRequest req = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull("expected an HTTP request to be sent", req);
        return req;
    }

    // ── verifyTokenWithDetail ────────────────────────────────────────────────

    @Test
    public void verifyToken_noToken_returnsMessageWithoutHttpCall() throws Exception
    {
        assertEquals("No verification code provided.", client.verifyTokenWithDetail(null, "rsn"));
        assertEquals("No verification code provided.", client.verifyTokenWithDetail("", "rsn"));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void verifyToken_success_returnsNullAndNormalizesPayload() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        assertNull(client.verifyTokenWithDetail("abc123", "Zezima"));

        RecordedRequest req = awaitRequest();
        assertTrue(req.getPath().endsWith("/api/verify-runelite"));
        JsonObject sent = new Gson().fromJson(req.getBody().readUtf8(), JsonObject.class);
        assertEquals("ABC123", sent.get("verification_code").getAsString());
        assertEquals("zezima", sent.get("osrs_rsn").getAsString());
    }

    @Test
    public void verifyToken_failure_returnsServerMessage() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"message\":\"Bad code\"}"));
        assertEquals("Bad code", client.verifyTokenWithDetail("abc", "rsn"));
    }

    @Test
    public void verifyToken_failureWithoutMessage_usesHttpFallback() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(403).setBody(""));
        assertEquals("Verification failed (HTTP 403).", client.verifyTokenWithDetail("abc", "rsn"));
    }

    @Test
    public void verifyToken_booleanWrapperDelegates() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        assertTrue(client.verifyToken("abc", "rsn"));

        server.enqueue(new MockResponse().setResponseCode(400).setBody("{}"));
        assertFalse(client.verifyToken("abc", "rsn"));
    }

    @Test
    public void verifyToken_omitsRsnWhenBlank() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client.verifyTokenWithDetail("abc", "   ");

        JsonObject sent = new Gson().fromJson(awaitRequest().getBody().readUtf8(), JsonObject.class);
        assertFalse(sent.has("osrs_rsn"));
    }

    // ── fetchFeatureFlags ────────────────────────────────────────────────────

    @Test
    public void fetchFeatureFlags_success_parsesMapAndLowercasesUsername() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"flags\":{\"live_map\":true,\"beta\":false}}"));

        Map<String, Boolean> flags = client.fetchFeatureFlags("Zezima");
        assertEquals(Boolean.TRUE, flags.get("live_map"));
        assertEquals(Boolean.FALSE, flags.get("beta"));

        RecordedRequest req = awaitRequest();
        assertTrue(req.getPath().contains("username=zezima"));
    }

    @Test
    public void fetchFeatureFlags_nonSuccess_returnsEmpty() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));
        assertTrue(client.fetchFeatureFlags("Zezima").isEmpty());
    }

    @Test
    public void fetchFeatureFlags_missingFlagsObject_returnsEmpty() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"other\":1}"));
        assertTrue(client.fetchFeatureFlags("Zezima").isEmpty());
    }

    @Test
    public void fetchFeatureFlags_malformedJson_returnsEmpty() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("not json at all"));
        assertTrue(client.fetchFeatureFlags("Zezima").isEmpty());
    }

    // ── syncBankData ─────────────────────────────────────────────────────────

    @Test
    public void syncBankData_successAndFailureStatuses() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        assertTrue(client.syncBankData("tok", new JsonObject()));

        RecordedRequest req = awaitRequest();
        assertEquals("Bearer tok", req.getHeader("Authorization"));

        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));
        assertFalse(client.syncBankData("tok", new JsonObject()));
    }

    // ── async: XP batch ──────────────────────────────────────────────────────

    @Test
    public void syncXpBatch_sendsAuthorizedPayload() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        Map<String, Integer> gains = new HashMap<>();
        gains.put("attack", 25);
        client.syncXpBatch(gains);

        RecordedRequest req = awaitRequest();
        assertTrue(req.getPath().endsWith("/api/xp/batch"));
        assertEquals("Bearer TOKEN", req.getHeader("Authorization"));
        JsonObject sent = new Gson().fromJson(req.getBody().readUtf8(), JsonObject.class);
        assertEquals(25, sent.getAsJsonObject("xp_gains").get("attack").getAsInt());
        assertEquals("Zezima", sent.get("username").getAsString());
    }

    @Test
    public void syncXpBatch_skipsWhenNoTokenOrEmptyGains() throws Exception
    {
        state.setVerificationCode(null);
        client.syncXpBatch(Collections.singletonMap("attack", 10));

        state.setVerificationCode("TOKEN");
        client.syncXpBatch(new HashMap<>());

        assertEquals(0, server.getRequestCount());
    }

    // ── async: privacy ───────────────────────────────────────────────────────

    @Test
    public void syncPrivacySettings_mapsEnumsToWireTokens() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client.syncPrivacySettings(PrivacySetting.FRIENDS, null);

        JsonObject sent = new Gson().fromJson(awaitRequest().getBody().readUtf8(), JsonObject.class);
        assertEquals("friends", sent.get("bank_privacy").getAsString());
        // null visibility defaults to public
        assertEquals("public", sent.get("player_visibility").getAsString());
    }

    // ── async: heartbeat + visible players parsing ───────────────────────────

    @Test
    public void sendHeartbeat_sendsPayloadAndCachesVisiblePlayers() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"players\":[{\"username\":\"Ally\",\"world_x\":3200,"
                        + "\"world_y\":3210,\"plane\":0,\"world\":330}]}"));

        client.sendHeartbeat(null, Arrays.asList("Friend1", "", null), Collections.emptyList(),
                PrivacySetting.PRIVATE, null, null, PrivacySetting.PUBLIC,
                Collections.singletonMap("attack", 5));

        RecordedRequest req = awaitRequest();
        JsonObject sent = new Gson().fromJson(req.getBody().readUtf8(), JsonObject.class);
        assertEquals("private", sent.get("visibility").getAsString());
        // toJsonArray skips null/empty entries → only "Friend1" remains
        assertEquals(1, sent.getAsJsonArray("friends").size());
        assertEquals(5, sent.getAsJsonObject("xp_preview").get("attack").getAsInt());

        // The response callback caches visible players onto shared state.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (state.getVisibleMapPlayers().isEmpty() && System.nanoTime() < deadline)
        {
            Thread.yield();
        }
        assertEquals(1, state.getVisibleMapPlayers().size());
        assertEquals("Ally", state.getVisibleMapPlayers().get(0).getUsername());
    }
}
