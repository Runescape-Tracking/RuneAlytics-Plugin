package com.runealytics.slayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

/**
 * Unsent Slayer events for one account, oldest first. Batches of up to
 * {@link #MAX_BATCH}; a failed batch is retried with the same events (same UUIDs)
 * after an exponential backoff with jitter. Thread-safe.
 */
public final class SlayerEventOutbox
{
    static final int MAX_BATCH = 50;
    /** Oldest events are dropped past this, so a long outage can't grow without bound. */
    static final int MAX_QUEUED = 2_000;
    static final long BACKOFF_BASE_MS = 5_000L;
    static final long BACKOFF_MAX_MS = 10 * 60_000L;

    private final List<SlayerTaskEvent> queue = new ArrayList<>();
    private int inflight;
    private int attempts;
    private long nextAttemptMs;

    public synchronized void add(SlayerTaskEvent e)
    {
        queue.add(e);
        while (queue.size() > MAX_QUEUED && queue.size() > inflight) queue.remove(inflight);
    }

    public synchronized void addAll(Collection<SlayerTaskEvent> events)
    {
        for (SlayerTaskEvent e : events) add(e);
    }

    /** The next batch to send, or {@code null} (empty, one in flight, or backing off). */
    public synchronized List<SlayerTaskEvent> take(long nowMs)
    {
        if (queue.isEmpty() || inflight > 0 || nowMs < nextAttemptMs) return null;
        inflight = Math.min(MAX_BATCH, queue.size());
        return new ArrayList<>(queue.subList(0, inflight));
    }

    /** The server answered: drop the acknowledged events (accepted, duplicate or rejected). */
    public synchronized void acknowledged(Collection<String> uuids)
    {
        List<SlayerTaskEvent> batch = queue.subList(0, inflight);
        batch.removeIf(e -> uuids.contains(e.getEventUuid()));
        inflight = 0;
        attempts = 0;
        nextAttemptMs = 0;
    }

    /** Network / server error: retry the same batch later. */
    public synchronized void failed(long nowMs, Random random)
    {
        inflight = 0;
        attempts++;
        int exp = Math.min(attempts - 1, 10);
        long base = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << exp);
        double jitter = 0.7 + random.nextDouble() * 0.6;
        nextAttemptMs = nowMs + Math.max(1_000L, Math.min(BACKOFF_MAX_MS, (long) (base * jitter)));
    }

    /** The server refused the request itself (bad request): retrying can't help, drop the batch. */
    public synchronized void dropInflight()
    {
        queue.subList(0, inflight).clear();
        inflight = 0;
    }

    public synchronized void clear()
    {
        queue.clear();
        inflight = 0;
        attempts = 0;
        nextAttemptMs = 0;
    }

    public synchronized List<SlayerTaskEvent> snapshot()
    {
        return new ArrayList<>(queue);
    }

    public synchronized boolean isEmpty()
    {
        return queue.isEmpty();
    }
}
