package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One aggregated, immutable observation window for the Account Analysis
 * telemetry contract (schema v1). A {@code null} skill means "logged in, no
 * XP gained in this window" so the server can account for observed time.
 *
 * <p>Pure Java (no RuneLite types) so it can be unit tested in isolation.</p>
 */
final class TelemetryBucket
{
    final String bucketId;
    final String sessionId;
    final String worldMode;
    final String skill;          // lowercase skill name, or null for an XP-free window
    final Long xpStart;          // null when skill is null
    final Long xpEnd;
    final long startedAtMs;
    final long endedAtMs;
    final long elapsedMs;
    final long estimatedActiveMs;
    final List<String> flags;

    TelemetryBucket(String bucketId, String sessionId, String worldMode, String skill, Long xpStart, Long xpEnd,
                    long startedAtMs, long endedAtMs, long elapsedMs, long estimatedActiveMs, List<String> flags)
    {
        this.bucketId = Objects.requireNonNull(bucketId);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.worldMode = Objects.requireNonNull(worldMode);
        this.skill = skill;
        this.xpStart = xpStart;
        this.xpEnd = xpEnd;
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        this.elapsedMs = elapsedMs;
        this.estimatedActiveMs = Math.min(estimatedActiveMs, elapsedMs);
        this.flags = Collections.unmodifiableList(flags);
    }

    static final String ESTIMATOR_VERSION = "xp-gap-v1";

    JsonObject toJson()
    {
        JsonObject o = new JsonObject();
        o.addProperty("bucket_id", bucketId);
        o.addProperty("revision", 0);
        o.addProperty("skill", skill);
        o.addProperty("xp_start", xpStart);
        o.addProperty("xp_end", xpEnd);
        o.addProperty("started_at", Instant.ofEpochMilli(startedAtMs).toString());
        o.addProperty("ended_at", Instant.ofEpochMilli(endedAtMs).toString());
        o.addProperty("elapsed_ms", elapsedMs);
        o.add("observed_active_ms", null);
        o.addProperty("estimated_active_ms", estimatedActiveMs);
        o.addProperty("active_estimator_version", ESTIMATOR_VERSION);
        o.add("method_id", null);
        o.addProperty("method_attribution", "unknown");
        o.add("action_count", null);
        o.addProperty("observation_coverage", 1.0);
        JsonArray f = new JsonArray();
        for (String flag : flags)
        {
            f.add(flag);
        }
        o.add("flags", f);
        return o;
    }
}
