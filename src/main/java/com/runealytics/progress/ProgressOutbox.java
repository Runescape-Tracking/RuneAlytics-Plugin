package com.runealytics.progress;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Pending progress uploads and the persisted baseline for ONE account
 * (account hash + RuneScape profile type). Thread-safe; every method is
 * {@code synchronized} and does only in-memory work (no I/O, no HTTP).
 *
 * <h2>Delivery model</h2>
 * <ul>
 *   <li>Changed record keys accumulate in {@code pending*}; values are read from the
 *       baseline when a batch is built, so the newest observation always wins.</li>
 *   <li>At most one batch is in flight. Its body is frozen and resent unchanged on
 *       retry (same {@code batch_id}, same {@code sequence}), so the server can
 *       deduplicate. Changes that arrive meanwhile wait in {@code pending*}.</li>
 *   <li>Retryable failures back off exponentially with jitter. A rejected batch is
 *       dropped and a full snapshot is requested instead, so the server state is
 *       repaired on the next successful upload.</li>
 *   <li>Upload credentials are bound in memory only (never persisted) and only for
 *       the account they were verified for.</li>
 * </ul>
 */
public final class ProgressOutbox
{
    static final int MAX_PENDING_TRANSITIONS = 1_000;
    static final int MAX_TRANSITIONS_PER_BATCH = 200;
    static final long BACKOFF_BASE_MS = 5_000L;
    static final long BACKOFF_MAX_MS = 10 * 60_000L;
    static final long NOT_DEPLOYED_RETRY_MS = 30 * 60_000L;
    static final long REJECTED_COOLDOWN_MS = 60 * 60_000L;

    /** Outcome of one upload attempt, decided from the HTTP response. */
    public enum Outcome
    {
        /** 2xx: the server stored (or already had) the batch. */
        ACK,
        /** Network error, 408, 429, 5xx: resend the same batch later. */
        RETRY,
        /** 401: the token is no longer valid; wait for re-verification. */
        AUTH_FAILED,
        /** 404/405/410: the website does not have the endpoint yet. */
        NOT_DEPLOYED,
        /** Other 4xx: the batch will never be accepted; drop it and resend a snapshot. */
        REJECTED
    }

    /** A batch ready to send. Immutable. */
    public static final class Batch
    {
        private final String batchId;
        private final long sequence;
        private final String kind;
        private final String body;
        private final String token;
        private final int transitionCount;

        Batch(String batchId, long sequence, String kind, String body, String token, int transitionCount)
        {
            this.batchId = batchId;
            this.sequence = sequence;
            this.kind = kind;
            this.body = body;
            this.token = token;
            this.transitionCount = transitionCount;
        }

        public String getBatchId()
        {
            return batchId;
        }

        public long getSequence()
        {
            return sequence;
        }

        public String getKind()
        {
            return kind;
        }

        public String getBody()
        {
            return body;
        }

        /** Bearer token captured from the binding for this account. Never logged. */
        String getToken()
        {
            return token;
        }

        public int getTransitionCount()
        {
            return transitionCount;
        }
    }

    /** Persisted in-flight batch. */
    static final class Inflight
    {
        String batchId;
        long sequence;
        String kind;
        String body;
        int transitionCount;
        int attempts;
        long nextAttemptAtMs;
    }

    /** On-disk form. Field names are part of the local file format. */
    static final class Persisted
    {
        int schema = 1;
        String accountKey;
        ProgressBaseline baseline;
        long nextSequence;
        boolean needSnapshot;
        List<Integer> pendingQuests;
        List<String> pendingDiaries;
        boolean pendingCombat;
        List<ProgressTransition> pendingTransitions;
        Inflight inflight;
        String worldMode;
        long rejectedUntilMs;
    }

    private final String accountKey;
    private final String profileType;

    private ProgressBaseline baseline = ProgressBaseline.empty();
    private long nextSequence = 1;
    private boolean needSnapshot;
    private final Set<Integer> pendingQuests = new LinkedHashSet<>();
    private final Set<String> pendingDiaries = new LinkedHashSet<>();
    private boolean pendingCombat;
    private final LinkedHashMap<String, ProgressTransition> pendingTransitions = new LinkedHashMap<>();
    private Inflight inflight;
    private String worldMode;
    private long rejectedUntilMs;
    private int droppedTransitions;

    private boolean dirty;

    // In-memory only: never written to disk.
    private String boundUsername;
    private String boundToken;

    public ProgressOutbox(String accountKey, String profileType)
    {
        this.accountKey = accountKey;
        this.profileType = profileType;
    }

    public String getAccountKey()
    {
        return accountKey;
    }

    public String getProfileType()
    {
        return profileType;
    }

    // ── Baseline + changes (from the client-thread scan, applied on a worker) ─

    public synchronized ProgressBaseline getBaseline()
    {
        return baseline;
    }

    /**
     * Records the result of one scan: replaces the baseline and queues changed keys
     * and transitions for upload.
     */
    public synchronized void apply(ProgressDiffer.Result diff, String currentWorldMode)
    {
        // Never let an older scan (e.g. one delivered late by a multi-threaded
        // executor) overwrite a newer baseline. Its keys/transitions still merge.
        ProgressBaseline incoming = diff.getBaseline();
        if (baseline.isEmpty() || ProgressPayloadBuilder.latestObservedAt(incoming, Long.MIN_VALUE)
                >= ProgressPayloadBuilder.latestObservedAt(baseline, Long.MIN_VALUE))
        {
            baseline = incoming;
        }
        if (currentWorldMode != null) worldMode = currentWorldMode;
        pendingQuests.addAll(diff.getChangedQuests());
        pendingDiaries.addAll(diff.getChangedDiaries());
        pendingCombat |= diff.isCombatChanged();
        droppedTransitions += diff.getDroppedTransitions();
        for (ProgressTransition t : diff.getTransitions())
        {
            pendingTransitions.put(t.getId(), t);
        }
        while (pendingTransitions.size() > MAX_PENDING_TRANSITIONS)
        {
            Iterator<String> it = pendingTransitions.keySet().iterator();
            it.next();
            it.remove();
            droppedTransitions++;
        }
        dirty = true;
    }

    /** Next batch sent will be a full snapshot (login reconciliation, manual resync). */
    public synchronized void requestSnapshot()
    {
        if (!needSnapshot)
        {
            needSnapshot = true;
            dirty = true;
        }
        // A manual resync also lifts a rejection cool-down.
        rejectedUntilMs = 0;
    }

    public synchronized boolean isSnapshotRequested()
    {
        return needSnapshot;
    }

    public synchronized int pendingTransitionCount()
    {
        return pendingTransitions.size();
    }

    /** Transitions dropped because a bound was hit; reset on read. For instrumentation. */
    public synchronized int takeDroppedTransitionCount()
    {
        int n = droppedTransitions;
        droppedTransitions = 0;
        return n;
    }

    // ── Credentials ──────────────────────────────────────────────────────────

    /** Binds the verified username and token for THIS account. Call only after identity checks. */
    public synchronized void bind(String username, String token)
    {
        this.boundUsername = username;
        this.boundToken = token;
    }

    public synchronized void unbind()
    {
        this.boundUsername = null;
        this.boundToken = null;
    }

    public synchronized boolean isBound()
    {
        return boundToken != null && !boundToken.isEmpty() && boundUsername != null && !boundUsername.isEmpty();
    }

    // ── Batching ─────────────────────────────────────────────────────────────

    /** True when there is something to send (ignoring timing and credentials). */
    public synchronized boolean hasWork()
    {
        return inflight != null || hasPendingContent();
    }

    private boolean hasPendingContent()
    {
        if (baseline.isEmpty()) return false;
        return needSnapshot || !pendingQuests.isEmpty() || !pendingDiaries.isEmpty()
                || pendingCombat || !pendingTransitions.isEmpty();
    }

    /** Earliest time (ms) a batch could be sent, or {@code Long.MAX_VALUE} when idle. */
    public synchronized long nextDueAtMs(long nowMs)
    {
        if (inflight != null) return inflight.nextAttemptAtMs;
        if (!hasPendingContent()) return Long.MAX_VALUE;
        return Math.max(nowMs, rejectedUntilMs);
    }

    /**
     * Returns the batch to send now, or {@code null} when nothing is due, nothing is
     * pending or credentials are not bound. Builds and freezes a new batch when no
     * batch is in flight.
     */
    public synchronized Batch prepare(long nowMs, String sessionId, String installationId, String clientVersion,
                                      Gson gson, Supplier<String> idFactory)
    {
        if (!isBound()) return null;

        if (inflight != null)
        {
            if (nowMs < inflight.nextAttemptAtMs) return null;
            return toBatch(inflight);
        }

        if (nowMs < rejectedUntilMs || !hasPendingContent()) return null;

        String batchId = idFactory.get();
        long sequence = nextSequence++;
        ProgressPayloadBuilder.Envelope env = new ProgressPayloadBuilder.Envelope(batchId, sessionId, installationId,
                sequence, boundUsername, worldMode, profileType, clientVersion, nowMs);

        List<ProgressTransition> take = new ArrayList<>();
        Iterator<Map.Entry<String, ProgressTransition>> it = pendingTransitions.entrySet().iterator();
        while (it.hasNext() && take.size() < MAX_TRANSITIONS_PER_BATCH)
        {
            take.add(it.next().getValue());
            it.remove();
        }

        JsonObject body;
        String kind;
        if (needSnapshot)
        {
            kind = ProgressPayloadBuilder.KIND_SNAPSHOT;
            body = ProgressPayloadBuilder.snapshot(env, baseline, take);
            needSnapshot = false;
        }
        else
        {
            kind = ProgressPayloadBuilder.KIND_DELTA;
            body = ProgressPayloadBuilder.delta(env, baseline, pendingQuests, pendingDiaries, pendingCombat, take);
        }
        // A snapshot carries every record, so it also satisfies all pending deltas.
        pendingQuests.clear();
        pendingDiaries.clear();
        pendingCombat = false;

        Inflight f = new Inflight();
        f.batchId = batchId;
        f.sequence = sequence;
        f.kind = kind;
        f.body = gson.toJson(body);
        f.transitionCount = take.size();
        f.attempts = 0;
        f.nextAttemptAtMs = nowMs;
        inflight = f;
        dirty = true;
        return toBatch(f);
    }

    private Batch toBatch(Inflight f)
    {
        return new Batch(f.batchId, f.sequence, f.kind, f.body, boundToken, f.transitionCount);
    }

    /**
     * Applies the outcome of sending {@code batchId}. Ignored if that batch is no
     * longer the one in flight (e.g. a stale response after a reset).
     *
     * @return number of transitions lost (only for {@link Outcome#REJECTED})
     */
    public synchronized int onResult(String batchId, Outcome outcome, long nowMs, Random random)
    {
        if (inflight == null || !inflight.batchId.equals(batchId)) return 0;
        dirty = true;
        switch (outcome)
        {
            case ACK:
                inflight = null;
                return 0;
            case RETRY:
                inflight.attempts++;
                inflight.nextAttemptAtMs = nowMs + backoffMs(inflight.attempts, random);
                return 0;
            case AUTH_FAILED:
                inflight.attempts++;
                inflight.nextAttemptAtMs = nowMs + BACKOFF_MAX_MS;
                boundToken = null;
                boundUsername = null;
                return 0;
            case NOT_DEPLOYED:
                inflight.attempts++;
                inflight.nextAttemptAtMs = nowMs + NOT_DEPLOYED_RETRY_MS;
                return 0;
            case REJECTED:
            default:
                int lost = inflight.transitionCount;
                inflight = null;
                needSnapshot = true;
                rejectedUntilMs = nowMs + REJECTED_COOLDOWN_MS;
                return lost;
        }
    }

    /** Exponential backoff with ±30% jitter, capped at {@link #BACKOFF_MAX_MS}. */
    static long backoffMs(int attempts, Random random)
    {
        int exp = Math.max(0, Math.min(attempts - 1, 20));
        long base = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << exp);
        double jitter = 0.7 + 0.6 * random.nextDouble();
        return Math.max(1_000L, Math.min(BACKOFF_MAX_MS, (long) (base * jitter)));
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    /** Serializes the outbox if it changed since the last call; otherwise returns {@code null}. */
    public synchronized String serializeIfDirty(Gson gson)
    {
        if (!dirty) return null;
        dirty = false;
        Persisted p = new Persisted();
        p.accountKey = accountKey;
        p.baseline = baseline;
        p.nextSequence = nextSequence;
        p.needSnapshot = needSnapshot;
        p.pendingQuests = new ArrayList<>(pendingQuests);
        p.pendingDiaries = new ArrayList<>(pendingDiaries);
        p.pendingCombat = pendingCombat;
        p.pendingTransitions = new ArrayList<>(pendingTransitions.values());
        p.inflight = inflight;
        p.worldMode = worldMode;
        p.rejectedUntilMs = rejectedUntilMs;
        return gson.toJson(p);
    }

    /** Marks the outbox dirty again after a failed write so the next attempt retries it. */
    public synchronized void markDirty()
    {
        dirty = true;
    }

    /**
     * Restores an outbox from disk. A file written for a different account key is
     * ignored (returns an empty outbox) so one account's data can never be loaded
     * under another.
     */
    public static ProgressOutbox restore(String accountKey, String profileType, String json, Gson gson)
    {
        ProgressOutbox box = new ProgressOutbox(accountKey, profileType);
        if (json == null || json.isEmpty()) return box;
        Persisted p = gson.fromJson(json, Persisted.class);
        if (p == null || p.schema != 1 || !accountKey.equals(p.accountKey)) return box;
        synchronized (box)
        {
            box.baseline = p.baseline != null ? p.baseline : ProgressBaseline.empty();
            box.nextSequence = Math.max(1, p.nextSequence);
            box.needSnapshot = p.needSnapshot;
            if (p.pendingQuests != null) box.pendingQuests.addAll(p.pendingQuests);
            if (p.pendingDiaries != null) box.pendingDiaries.addAll(p.pendingDiaries);
            box.pendingCombat = p.pendingCombat;
            if (p.pendingTransitions != null)
            {
                for (ProgressTransition t : p.pendingTransitions)
                {
                    if (t != null && t.getId() != null) box.pendingTransitions.put(t.getId(), t);
                }
            }
            box.inflight = p.inflight != null && p.inflight.batchId != null && p.inflight.body != null ? p.inflight : null;
            if (box.inflight != null) box.inflight.nextAttemptAtMs = 0; // retry promptly after restart
            box.worldMode = p.worldMode;
            box.rejectedUntilMs = p.rejectedUntilMs;
        }
        return box;
    }
}
