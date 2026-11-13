package com.runealytics.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Builds the JSON body for {@code POST /api/plugin/v1/accounts/me/progress}.
 *
 * <p>Runs on a background thread from immutable inputs. The body is built once per
 * batch and resent byte-for-byte on retries, so the server can treat a repeated
 * {@code batch_id} with identical content as a replay.</p>
 */
public final class ProgressPayloadBuilder
{
    public static final int SCHEMA_VERSION = 1;
    public static final String KIND_SNAPSHOT = "snapshot";
    public static final String KIND_DELTA = "delta";

    private ProgressPayloadBuilder()
    {
    }

    /** Batch-level fields shared by snapshots and deltas. */
    public static final class Envelope
    {
        final String batchId;
        final String sessionId;
        final String installationId;
        final long sequence;
        final String username;
        final String worldMode;
        final String profileType;
        final String clientVersion;
        final long builtAtMs;

        public Envelope(String batchId, String sessionId, String installationId, long sequence, String username,
                        String worldMode, String profileType, String clientVersion, long builtAtMs)
        {
            this.batchId = batchId;
            this.sessionId = sessionId;
            this.installationId = installationId;
            this.sequence = sequence;
            this.username = username;
            this.worldMode = worldMode;
            this.profileType = profileType;
            this.clientVersion = clientVersion;
            this.builtAtMs = builtAtMs;
        }
    }

    /** Full snapshot of everything in the baseline, plus any pending transitions. */
    public static JsonObject snapshot(Envelope env, ProgressBaseline b, Collection<ProgressTransition> transitions)
    {
        JsonObject root = envelope(env, KIND_SNAPSHOT, b);

        JsonObject coverage = new JsonObject();
        coverage.addProperty("quests", b.getQuests() != null ? ProgressCoverage.COMPLETE : ProgressCoverage.UNAVAILABLE);
        coverage.addProperty("diary_tiers", b.getDiaries() != null ? ProgressCoverage.COMPLETE : ProgressCoverage.UNAVAILABLE);
        coverage.addProperty("diary_tasks", ProgressCoverage.NOT_SUPPORTED);
        coverage.addProperty("combat_achievements", b.getCombat() != null && b.getCombatCoverage() != null
                ? b.getCombatCoverage() : ProgressCoverage.UNAVAILABLE);
        coverage.addProperty("collection_log", ProgressCoverage.NOT_SUPPORTED);
        root.add("coverage", coverage);

        if (b.getQuests() != null)
        {
            root.add("quests", quests(b.getQuests().values(), b.getQuestsObservedAtMs()));
        }
        if (b.getDiaries() != null)
        {
            root.add("diaries", diaries(b.getDiaries().values(), b.getDiariesObservedAtMs()));
        }
        if (b.getCombat() != null)
        {
            root.add("combat_achievements", combat(b.getCombat(), b.getCombatObservedAtMs()));
        }
        root.add("transitions", transitions(transitions));
        return root;
    }

    /** Only the given changed records (current values from the baseline) plus transitions. */
    public static JsonObject delta(Envelope env, ProgressBaseline b, Collection<Integer> questIds,
                                   Collection<String> diaryKeys, boolean includeCombat,
                                   Collection<ProgressTransition> transitions)
    {
        JsonObject root = envelope(env, KIND_DELTA, b);
        JsonObject coverage = new JsonObject();

        if (b.getQuests() != null && !questIds.isEmpty())
        {
            JsonArray arr = new JsonArray();
            for (Integer id : questIds)
            {
                QuestRecord q = b.getQuests().get(id);
                if (q != null) arr.add(quest(q, b.getQuestsObservedAtMs()));
            }
            if (arr.size() > 0)
            {
                root.add("quests", arr);
                coverage.addProperty("quests", ProgressCoverage.PARTIAL_CHANGED_ONLY);
            }
        }
        if (b.getDiaries() != null && !diaryKeys.isEmpty())
        {
            JsonArray arr = new JsonArray();
            for (String key : diaryKeys)
            {
                DiaryRecord d = b.getDiaries().get(key);
                if (d != null) arr.add(diary(d, b.getDiariesObservedAtMs()));
            }
            if (arr.size() > 0)
            {
                root.add("diaries", arr);
                coverage.addProperty("diary_tiers", ProgressCoverage.PARTIAL_CHANGED_ONLY);
            }
        }
        if (includeCombat && b.getCombat() != null)
        {
            // The combat block is always sent whole (it is small); its own coverage applies.
            root.add("combat_achievements", combat(b.getCombat(), b.getCombatObservedAtMs()));
            coverage.addProperty("combat_achievements", b.getCombatCoverage());
        }
        root.add("coverage", coverage);
        root.add("transitions", transitions(transitions));
        return root;
    }

    // ── Pieces ────────────────────────────────────────────────────────────────

    private static JsonObject envelope(Envelope env, String kind, ProgressBaseline b)
    {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);
        root.addProperty("batch_id", env.batchId);
        root.addProperty("kind", kind);
        root.addProperty("session_id", env.sessionId);
        root.addProperty("installation_id", env.installationId);
        root.addProperty("sequence", env.sequence);
        root.addProperty("username", env.username);
        root.addProperty("world_mode", env.worldMode != null ? env.worldMode : "unknown");
        root.addProperty("profile_type", env.profileType);

        JsonObject client = new JsonObject();
        client.addProperty("plugin_progress_version", env.clientVersion);
        client.addProperty("catalog_version", b.getCatalogVersion());
        root.add("client", client);

        root.addProperty("captured_at", iso(latestObservedAt(b, env.builtAtMs)));
        root.addProperty("built_at", iso(env.builtAtMs));
        return root;
    }

    static long latestObservedAt(ProgressBaseline b, long fallback)
    {
        long max = Long.MIN_VALUE;
        if (b.getQuestsObservedAtMs() != null) max = Math.max(max, b.getQuestsObservedAtMs());
        if (b.getDiariesObservedAtMs() != null) max = Math.max(max, b.getDiariesObservedAtMs());
        if (b.getCombatObservedAtMs() != null) max = Math.max(max, b.getCombatObservedAtMs());
        return max == Long.MIN_VALUE ? fallback : max;
    }

    private static JsonArray quests(Collection<QuestRecord> records, Long observedAt)
    {
        JsonArray arr = new JsonArray();
        for (QuestRecord q : records) arr.add(quest(q, observedAt));
        return arr;
    }

    private static JsonObject quest(QuestRecord q, Long observedAt)
    {
        JsonObject o = new JsonObject();
        o.addProperty("quest_id", q.getId());
        o.addProperty("key", q.getKey());
        o.addProperty("name", q.getName());
        o.addProperty("state", q.getStatus().name());
        if (observedAt != null) o.addProperty("observed_at", iso(observedAt));
        return o;
    }

    private static JsonArray diaries(Collection<DiaryRecord> records, Long observedAt)
    {
        JsonArray arr = new JsonArray();
        for (DiaryRecord d : records) arr.add(diary(d, observedAt));
        return arr;
    }

    private static JsonObject diary(DiaryRecord d, Long observedAt)
    {
        JsonObject o = new JsonObject();
        o.addProperty("region", d.getRegion());
        o.addProperty("tier", d.getTier());
        o.addProperty("complete_var", d.getCompleteVar());
        o.addProperty("reward_var", d.getRewardVar());
        o.addProperty("task_count", d.getTaskCount());
        JsonObject vars = new JsonObject();
        vars.addProperty("complete", d.getCompleteVarbit());
        vars.addProperty("reward", d.getRewardVarbit());
        vars.addProperty("count", d.getCountVarbit());
        o.add("varbits", vars);
        if (observedAt != null) o.addProperty("observed_at", iso(observedAt));
        return o;
    }

    private static JsonObject combat(CombatRecord c, Long observedAt)
    {
        JsonObject o = new JsonObject();
        JsonObject words = new JsonObject();
        for (Map.Entry<Integer, Long> e : c.getTaskWords().entrySet())
        {
            words.addProperty(String.valueOf(e.getKey()), e.getValue());
        }
        o.add("task_words", words);
        o.add("tier_completed_counts", intMap(c.getTierCompleted()));
        o.addProperty("points", c.getPoints());
        o.add("tier_status", intMap(c.getTierStatus()));
        o.add("lamp_claimed", intMap(c.getLampClaimed()));
        o.addProperty("completed_bit_count", c.completedBitCount());
        if (observedAt != null) o.addProperty("observed_at", iso(observedAt));
        return o;
    }

    private static JsonObject intMap(Map<String, Integer> map)
    {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : map.entrySet()) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    private static JsonArray transitions(Collection<ProgressTransition> transitions)
    {
        JsonArray arr = new JsonArray();
        if (transitions == null) return arr;
        for (ProgressTransition t : transitions)
        {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.getId());
            o.addProperty("category", t.getCategory());
            o.addProperty("key", t.getKey());
            if (t.getName() != null) o.addProperty("name", t.getName());
            o.addProperty("field", t.getField());
            o.addProperty("from", t.getFrom());
            o.addProperty("to", t.getTo());
            o.addProperty("observed_at", iso(t.getObservedAtMs()));
            o.addProperty("detection", t.getDetection());
            if (t.getPreviousObservedAtMs() != null)
            {
                o.addProperty("previous_observed_at", iso(t.getPreviousObservedAtMs()));
            }
            arr.add(o);
        }
        return arr;
    }

    static String iso(long epochMs)
    {
        return Instant.ofEpochMilli(epochMs).toString();
    }
}
