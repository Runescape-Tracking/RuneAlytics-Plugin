package com.runealytics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TelemetryUploaderTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private MockWebServer server;
    private TelemetrySpool spool;
    private TelemetryUploader uploader;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);

    @Before
    public void setUp() throws Exception
    {
        server = new MockWebServer();
        server.start();
        spool = new TelemetrySpool(tmp.newFolder("spool").toPath());
        uploader = new TelemetryUploader(new OkHttpClient(), () -> server.url("/api").toString(),
                () -> "TOKEN", () -> spool, "install-0001", now::get);
    }

    @After
    public void tearDown() throws Exception
    {
        server.shutdown();
    }

    private List<TelemetryBucket> buckets(int n)
    {
        List<TelemetryBucket> out = new ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            long s = now.get() - 60_000L * (n - i);
            out.add(new TelemetryBucket("b" + i, "s1", "regular", "mining", 100L * i, 100L * i + 100, s, s + 60_000, 60_000, 50_000, Collections.emptyList()));
        }
        return out;
    }

    private void awaitIdle()
    {
        for (int i = 0; i < 200 && uploader.inFlight(); i++)
        {
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
        }
    }

    @Test
    public void spoolsBeforeSendingAndDeletesOnAck() throws Exception
    {
        uploader.enqueue(buckets(3));
        assertEquals(1, spool.pending().size());

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"));
        uploader.pump();
        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        awaitIdle();

        assertEquals("/api/plugin/v1/accounts/me/xp-telemetry", req.getPath());
        assertEquals("Bearer TOKEN", req.getHeader("Authorization"));
        JsonObject body = new JsonParser().parse(req.getBody().readUtf8()).getAsJsonObject();
        assertEquals(1, body.get("schema_version").getAsInt());
        assertEquals(3, body.getAsJsonArray("buckets").size());
        assertTrue(body.has("sent_at"));
        assertEquals("install-0001", body.get("installation_id").getAsString());
        assertTrue(spool.pending().isEmpty());
    }

    @Test
    public void serverErrorsBackOffWithJitterAndRetrySameBatch() throws Exception
    {
        uploader.enqueue(buckets(1));
        server.enqueue(new MockResponse().setResponseCode(503));
        uploader.pump();
        server.takeRequest(5, TimeUnit.SECONDS);
        awaitIdle();

        assertEquals(1, spool.pending().size());
        long wait = uploader.nextAttemptAt() - now.get();
        assertTrue("backoff 2.5–5 s, got " + wait, wait >= 2_500 && wait <= 5_000);

        uploader.pump(); // too early: no request
        assertEquals(1, server.getRequestCount());

        now.addAndGet(wait);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        uploader.pump();
        String first = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        awaitIdle();
        assertTrue(spool.pending().isEmpty());
        assertTrue(first.contains("\"batch_id\""));
    }

    @Test
    public void permanentRejectionsAreDroppedAndAuthFailuresPause() throws Exception
    {
        uploader.enqueue(buckets(1));
        server.enqueue(new MockResponse().setResponseCode(422));
        uploader.pump();
        server.takeRequest(5, TimeUnit.SECONDS);
        awaitIdle();
        assertTrue(spool.pending().isEmpty());

        uploader.enqueue(buckets(1));
        server.enqueue(new MockResponse().setResponseCode(401));
        uploader.pump();
        server.takeRequest(5, TimeUnit.SECONDS);
        awaitIdle();
        assertEquals(1, spool.pending().size());
        assertTrue(uploader.nextAttemptAt() - now.get() >= TelemetryUploader.AUTH_PAUSE_MS);
    }

    @Test
    public void networkFailureKeepsBatchAndOnlyOneRequestInFlight() throws Exception
    {
        uploader.enqueue(buckets(1));
        uploader.enqueue(buckets(1));
        server.shutdown();
        uploader.pump();
        uploader.pump(); // second pump while in flight is a no-op
        for (int i = 0; i < 300 && uploader.inFlight(); i++)
        {
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
        }
        assertFalse(uploader.inFlight());
        assertEquals(2, spool.pending().size());
        assertEquals(1, uploader.failures());
    }

    @Test
    public void largeGroupsAreSplitAndSequencesAreMonotonic() throws Exception
    {
        uploader.enqueue(buckets(TelemetryUploader.MAX_BUCKETS_PER_BATCH + 5));
        List<Path> files = spool.pending();
        assertEquals(2, files.size());
        long a = new JsonParser().parse(spool.read(files.get(0))).getAsJsonObject().get("sequence").getAsLong();
        long b = new JsonParser().parse(spool.read(files.get(1))).getAsJsonObject().get("sequence").getAsLong();
        assertTrue(b > a);
    }

    @Test
    public void spoolIsBounded() throws Exception
    {
        for (int i = 0; i < TelemetrySpool.MAX_FILES + 20; i++)
        {
            spool.put(spool.nextSequence(), "id" + i, "{}");
        }
        assertEquals(TelemetrySpool.MAX_FILES, spool.pending().size());
        assertTrue(Files.exists(spool.pending().get(0)));
    }
}
