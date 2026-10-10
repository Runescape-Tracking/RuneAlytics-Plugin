package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Packs telemetry buckets into schema-v1 batches, spools them to disk and
 * uploads them one at a time to
 * {@code POST {apiUrl}/plugin/v1/accounts/me/xp-telemetry}.
 *
 * <ul>
 *   <li>Batches are written to the spool BEFORE any network call, so a crash,
 *       logout or client exit never loses acknowledged-pending data.</li>
 *   <li>Exactly one request is in flight; OkHttp's async dispatcher does the
 *       I/O, so neither the client thread nor the EDT ever blocks.</li>
 *   <li>200 → delete. 409 (batch id reused) / 413 / 422 → permanent, delete.
 *       401/403 → keep, long pause. 429, 5xx, network → exponential backoff
 *       with full jitter (5 s doubling to 10 min).</li>
 *   <li>{@code sent_at} is set per attempt and is excluded from the server's
 *       idempotency hash, so retries replay safely.</li>
 * </ul>
 * RuneLite-independent; unit tested with MockWebServer.
 */
final class TelemetryUploader
{
    private static final Logger log = LoggerFactory.getLogger(TelemetryUploader.class);
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static final int MAX_BUCKETS_PER_BATCH = 120;
    static final long BASE_BACKOFF_MS = 5_000;
    static final long MAX_BACKOFF_MS = 600_000;
    static final long AUTH_PAUSE_MS = 600_000;

    private final OkHttpClient http;
    private final Supplier<String> apiUrl;
    private final Supplier<String> token;
    private final Supplier<TelemetrySpool> spool;
    private final String installationId;
    private final LongSupplier clock;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile long nextAttemptAt = 0;
    private volatile int failures = 0;
    private volatile String lastStatus = "idle";

    TelemetryUploader(OkHttpClient http, Supplier<String> apiUrl, Supplier<String> token,
                      Supplier<TelemetrySpool> spool, String installationId, LongSupplier clock)
    {
        this.http = http;
        this.apiUrl = apiUrl;
        this.token = token;
        this.spool = spool;
        this.installationId = installationId;
        this.clock = clock;
    }

    /** Group buckets by session and spool them as batches. Executor thread only. */
    void enqueue(List<TelemetryBucket> buckets) throws IOException
    {
        TelemetrySpool s = spool.get();
        if (s == null || buckets.isEmpty())
        {
            return;
        }
        Map<String, List<TelemetryBucket>> bySession = new LinkedHashMap<>();
        for (TelemetryBucket b : buckets)
        {
            bySession.computeIfAbsent(b.sessionId, k -> new ArrayList<>()).add(b);
        }
        for (List<TelemetryBucket> group : bySession.values())
        {
            for (int i = 0; i < group.size(); i += MAX_BUCKETS_PER_BATCH)
            {
                List<TelemetryBucket> part = group.subList(i, Math.min(group.size(), i + MAX_BUCKETS_PER_BATCH));
                long seq = s.nextSequence();
                String batchId = UUID.randomUUID().toString();
                s.put(seq, batchId, batchJson(batchId, seq, part).toString());
            }
        }
    }

    JsonObject batchJson(String batchId, long seq, List<TelemetryBucket> part)
    {
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        JsonArray arr = new JsonArray();
        for (TelemetryBucket b : part)
        {
            start = Math.min(start, b.startedAtMs);
            end = Math.max(end, b.endedAtMs);
            arr.add(b.toJson());
        }
        JsonObject o = new JsonObject();
        o.addProperty("schema_version", 1);
        o.addProperty("batch_id", batchId);
        o.addProperty("session_id", part.get(0).sessionId);
        o.addProperty("installation_id", installationId);
        o.addProperty("sequence", seq);
        o.addProperty("started_at", Instant.ofEpochMilli(start).toString());
        o.addProperty("ended_at", Instant.ofEpochMilli(end).toString());
        o.addProperty("world_mode", part.get(0).worldMode);
        o.add("buckets", arr);
        return o;
    }

    /** Try to send the oldest spooled batch. Non-blocking; safe to call often. */
    void pump()
    {
        long now = clock.getAsLong();
        String t = token.get();
        TelemetrySpool s = spool.get();
        if (s == null || t == null || t.isEmpty() || now < nextAttemptAt || !inFlight.compareAndSet(false, true))
        {
            return;
        }
        Path file;
        String body;
        try
        {
            List<Path> pending = s.pending();
            if (pending.isEmpty())
            {
                inFlight.set(false);
                lastStatus = "idle";
                return;
            }
            file = pending.get(0);
            JsonObject json = new JsonParser().parse(s.read(file)).getAsJsonObject();
            json.addProperty("sent_at", Instant.ofEpochMilli(now).toString());
            body = json.toString();
        }
        catch (Exception e)
        {
            inFlight.set(false);
            log.debug("[Telemetry] spool read failed: {}", e.getMessage());
            return;
        }

        Request request = new Request.Builder()
                .url(apiUrl.get() + "/plugin/v1/accounts/me/xp-telemetry")
                .post(RequestBody.create(JSON, body))
                .addHeader("Authorization", "Bearer " + t)
                .addHeader("Accept", "application/json")
                .build();

        final Path sent = file;
        http.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                backoff("network: " + e.getMessage());
                inFlight.set(false);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response r = response)
                {
                    int code = r.code();
                    if (code == 200)
                    {
                        s.remove(sent);
                        failures = 0;
                        nextAttemptAt = 0;
                        lastStatus = "ok";
                    }
                    else if (code == 409 || code == 413 || code == 422)
                    {
                        s.remove(sent); // permanent: never retry
                        lastStatus = "dropped " + code;
                        log.debug("[Telemetry] batch dropped HTTP {}", code);
                    }
                    else if (code == 401 || code == 403)
                    {
                        nextAttemptAt = clock.getAsLong() + AUTH_PAUSE_MS;
                        lastStatus = "unauthorized";
                    }
                    else
                    {
                        backoff("HTTP " + code);
                    }
                }
                finally
                {
                    inFlight.set(false);
                }
            }
        });
    }

    private void backoff(String why)
    {
        failures = Math.min(failures + 1, 20);
        long cap = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << Math.min(failures - 1, 10));
        nextAttemptAt = clock.getAsLong() + ThreadLocalRandom.current().nextLong(cap / 2, cap + 1);
        lastStatus = "retrying (" + why + ")";
    }

    long nextAttemptAt()
    {
        return nextAttemptAt;
    }

    int failures()
    {
        return failures;
    }

    boolean inFlight()
    {
        return inFlight.get();
    }

    String lastStatus()
    {
        return lastStatus;
    }
}
