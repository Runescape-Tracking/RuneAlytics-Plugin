package com.runealytics.progress;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ProgressApiClientTest
{
    private MockWebServer server;
    private ProgressApiClient client;

    @Before
    public void setUp() throws Exception
    {
        server = new MockWebServer();
        server.start();
        String base = server.url("/api").toString();
        client = new ProgressApiClient(new OkHttpClient(), () -> base);
    }

    @After
    public void tearDown() throws Exception
    {
        server.shutdown();
    }

    private ProgressOutbox.Batch batch(String token)
    {
        return new ProgressOutbox.Batch("batch-1", 7, "delta", "{\"kind\":\"delta\"}", token, 0);
    }

    @Test
    public void postsWithExistingBearerTokenAndIdempotencyKey() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"));
        assertEquals(ProgressOutbox.Outcome.ACK, client.send(batch("secret-token")));

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/api/plugin/v1/accounts/me/progress", req.getPath());
        assertEquals("Bearer secret-token", req.getHeader("Authorization"));
        assertEquals("batch-1", req.getHeader("Idempotency-Key"));
        assertEquals("{\"kind\":\"delta\"}", req.getBody().readUtf8());
    }

    @Test
    public void mapsResponses()
    {
        int[][] cases = {{401, 0}, {404, 1}, {422, 2}, {503, 3}, {429, 3}, {409, 2}};
        ProgressOutbox.Outcome[] expected = {
            ProgressOutbox.Outcome.AUTH_FAILED, ProgressOutbox.Outcome.NOT_DEPLOYED,
            ProgressOutbox.Outcome.REJECTED, ProgressOutbox.Outcome.RETRY,
        };
        for (int[] c : cases)
        {
            server.enqueue(new MockResponse().setResponseCode(c[0]));
            assertEquals("HTTP " + c[0], expected[c[1]], client.send(batch("t")));
        }
    }

    @Test
    public void missingTokenIsNeverSent()
    {
        assertEquals(ProgressOutbox.Outcome.AUTH_FAILED, client.send(batch(null)));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void networkFailureIsRetryable() throws Exception
    {
        server.shutdown();
        assertEquals(ProgressOutbox.Outcome.RETRY, client.send(batch("t")));
    }
}
