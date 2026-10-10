package com.runealytics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregates XP observations into fixed windows (default 60 s) for the
 * Account Analysis telemetry contract.
 *
 * <h2>Threading</h2>
 * {@link #offer}, {@link #sessionStart}, {@link #worldHop} and {@link #logout}
 * are called on the RuneLite client thread and only enqueue an immutable event
 * into a bounded lock-free queue (no I/O, no locks, no aggregation). All
 * aggregation happens in {@link #process(long)} on a background executor, so
 * the game tick is never blocked. This class is RuneLite-independent.
 *
 * <h2>Rules (schema v1)</h2>
 * <ul>
 *   <li>The first XP value seen for a skill in a session is a baseline — never
 *       counted as a gain.</li>
 *   <li>An XP decrease (tracker reset / bad read) re-baselines the skill and
 *       flags {@code tracker_reset}; nothing negative is ever emitted.</li>
 *   <li>Every logged-in window is emitted; a window without XP becomes an
 *       XP-free bucket (skill = null) so observed time is known.</li>
 *   <li>Estimated active time ({@code xp-gap-v1}): the part of the window
 *       within the AFK timeout after any XP event (events in the previous
 *       window count too). It is an estimate and is labelled as such.</li>
 *   <li>If processing resumes long after a window should have closed (client
 *       suspended), the silent span is NOT emitted and the next bucket is
 *       flagged {@code reconnect}.</li>
 *   <li>A new session id starts on login, account switch or world-mode
 *       change; world hops keep the session and flag {@code world_hop}.</li>
 * </ul>
 */
final class TrainingTelemetryCollector
{
    static final int MAX_QUEUED_EVENTS = 20_000;

    private final long windowMs;
    private final long afkTimeoutMs;

    // ── Client-thread side: bounded event queue ──────────────────────────────
    private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();

    private static final class Observation
    {
        final String skill;
        final long xp;
        final long atMs;
        final boolean rewardLamp;

        Observation(String skill, long xp, long atMs, boolean rewardLamp)
        {
            this.skill = skill;
            this.xp = xp;
            this.atMs = atMs;
            this.rewardLamp = rewardLamp;
        }
    }

    private static final class Control
    {
        final String type;
        final String accountKey;
        final String worldMode;
        final long atMs;

        Control(String type, String accountKey, String worldMode, long atMs)
        {
            this.type = type;
            this.accountKey = accountKey;
            this.worldMode = worldMode;
            this.atMs = atMs;
        }
    }

    // ── Executor side: aggregation state (single-threaded access) ────────────
    private String sessionId;
    private String accountKey;
    private String worldMode;
    private long windowStart;
    private long lastEventBefore = Long.MIN_VALUE;
    private final Map<String, Long> lastXp = new HashMap<>();
    /** Contiguous gain range for one skill; a reset starts a new segment. */
    private static final class Segment
    {
        final String skill;
        final long xpStart;
        long xpEnd;
        final Set<String> flags = new LinkedHashSet<>();

        Segment(String skill, long xpStart, long xpEnd)
        {
            this.skill = skill;
            this.xpStart = xpStart;
            this.xpEnd = xpEnd;
        }
    }

    private final List<Segment> segments = new ArrayList<>();
    private final Map<String, Segment> openSegment = new HashMap<>();
    private final Map<String, Set<String>> carriedFlags = new HashMap<>();
    private final List<Long> windowEvents = new ArrayList<>();
    private final Set<String> pendingFlags = new LinkedHashSet<>();

    TrainingTelemetryCollector(long windowMs, long afkTimeoutMs)
    {
        this.windowMs = windowMs;
        this.afkTimeoutMs = afkTimeoutMs;
    }

    // ── Client thread (cheap) ────────────────────────────────────────────────

    boolean offer(String skill, long xp, long atMs, boolean rewardLamp)
    {
        return enqueue(new Observation(skill, xp, atMs, rewardLamp));
    }

    void sessionStart(String accountKey, String worldMode, long atMs)
    {
        enqueue(new Control("start", accountKey, worldMode, atMs));
    }

    void worldHop(long atMs)
    {
        enqueue(new Control("hop", null, null, atMs));
    }

    void logout(long atMs)
    {
        enqueue(new Control("logout", null, null, atMs));
    }

    private boolean enqueue(Object event)
    {
        if (queued.incrementAndGet() > MAX_QUEUED_EVENTS)
        {
            queued.decrementAndGet();
            dropped.incrementAndGet();
            return false;
        }
        queue.add(event);
        return true;
    }

    long droppedEvents()
    {
        return dropped.get();
    }

    // ── Background executor ──────────────────────────────────────────────────

    /** Drains queued events and closes due windows; returns completed buckets. */
    synchronized List<TelemetryBucket> process(long nowMs)
    {
        List<TelemetryBucket> out = new ArrayList<>();
        Object e;
        while ((e = queue.poll()) != null)
        {
            queued.decrementAndGet();
            if (e instanceof Observation)
            {
                Observation o = (Observation) e;
                closeDueWindows(o.atMs, out);
                observe(o);
            }
            else
            {
                Control c = (Control) e;
                closeDueWindows(c.atMs, out);
                control(c, out);
            }
        }
        closeDueWindows(nowMs, out);
        return out;
    }

    String currentSessionId()
    {
        return sessionId;
    }

    String currentWorldMode()
    {
        return worldMode;
    }

    private void observe(Observation o)
    {
        if (sessionId == null)
        {
            return;
        }
        Long prev = lastXp.get(o.skill);
        lastXp.put(o.skill, o.xp);
        if (prev == null)
        {
            return; // baseline
        }
        if (o.xp < prev)
        {
            // Re-baselined above; the next gain opens a new, flagged segment.
            openSegment.remove(o.skill);
            carriedFlags.computeIfAbsent(o.skill, k -> new LinkedHashSet<>()).add("tracker_reset");
            return;
        }
        if (o.xp == prev)
        {
            return;
        }
        Segment seg = openSegment.get(o.skill);
        if (seg == null)
        {
            seg = new Segment(o.skill, prev, o.xp);
            Set<String> carried = carriedFlags.remove(o.skill);
            if (carried != null)
            {
                seg.flags.addAll(carried);
            }
            segments.add(seg);
            openSegment.put(o.skill, seg);
        }
        else
        {
            seg.xpEnd = o.xp;
        }
        if (o.rewardLamp)
        {
            seg.flags.add("reward_lamp");
        }
        windowEvents.add(o.atMs);
    }

    private void control(Control c, List<TelemetryBucket> out)
    {
        switch (c.type)
        {
            case "start":
                if (sessionId != null && c.accountKey.equals(accountKey) && c.worldMode.equals(worldMode))
                {
                    return; // duplicate login event
                }
                if (sessionId != null)
                {
                    emit(windowStart, c.atMs, out);
                }
                sessionId = UUID.randomUUID().toString();
                accountKey = c.accountKey;
                worldMode = c.worldMode;
                windowStart = c.atMs;
                lastXp.clear();
                carriedFlags.clear();
                resetWindow();
                lastEventBefore = Long.MIN_VALUE;
                pendingFlags.clear();
                break;
            case "hop":
                pendingFlags.add("world_hop");
                break;
            case "logout":
                if (sessionId != null)
                {
                    pendingFlags.add("logout");
                    emit(windowStart, c.atMs, out);
                }
                sessionId = null;
                accountKey = null;
                worldMode = null;
                lastXp.clear();
                resetWindow();
                break;
            default:
                break;
        }
    }

    private void closeDueWindows(long nowMs, List<TelemetryBucket> out)
    {
        if (sessionId == null)
        {
            return;
        }
        while (nowMs >= windowStart + windowMs)
        {
            long end = windowStart + windowMs;
            emit(windowStart, end, out);
            windowStart = end;
            if (nowMs - windowStart > 2 * windowMs)
            {
                // Suspended / stalled: do not invent observed time for the gap.
                windowStart = nowMs;
                pendingFlags.add("reconnect");
                lastEventBefore = Long.MIN_VALUE;
            }
        }
    }

    private void emit(long start, long end, List<TelemetryBucket> out)
    {
        long elapsed = end - start;
        if (elapsed < 1_000)
        {
            resetWindow();
            return;
        }
        long active = activeMs(start, end);
        boolean any = false;
        for (Segment seg : segments)
        {
            if (seg.xpEnd <= seg.xpStart)
            {
                continue;
            }
            any = true;
            List<String> flags = new ArrayList<>(pendingFlags);
            for (String f : seg.flags)
            {
                if (!flags.contains(f))
                {
                    flags.add(f);
                }
            }
            out.add(new TelemetryBucket(UUID.randomUUID().toString(), sessionId, worldMode, seg.skill,
                    seg.xpStart, seg.xpEnd, start, end, elapsed, active, flags));
        }
        if (!any)
        {
            out.add(new TelemetryBucket(UUID.randomUUID().toString(), sessionId, worldMode, null,
                    null, null, start, end, elapsed, active, new ArrayList<>(pendingFlags)));
        }
        if (!windowEvents.isEmpty())
        {
            lastEventBefore = windowEvents.get(windowEvents.size() - 1);
        }
        pendingFlags.clear();
        resetWindow();
    }

    /** Measure of [start,end] covered by [event, event + afkTimeout) for any XP event. */
    long activeMs(long start, long end)
    {
        long covered = 0;
        long cursor = start;
        List<Long> events = new ArrayList<>(windowEvents.size() + 1);
        if (lastEventBefore != Long.MIN_VALUE)
        {
            events.add(lastEventBefore);
        }
        events.addAll(windowEvents);
        events.sort(Long::compare);
        for (long e : events)
        {
            long from = Math.max(cursor, Math.max(start, e));
            long to = Math.min(end, e + afkTimeoutMs);
            if (to > from)
            {
                covered += to - from;
                cursor = to;
            }
        }
        return covered;
    }

    private void resetWindow()
    {
        segments.clear();
        openSegment.clear();
        windowEvents.clear();
    }
}
