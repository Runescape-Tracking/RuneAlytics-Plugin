package com.runealytics;

import net.runelite.api.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Batches XP gains and sends them to the RuneAlytics server every 30 seconds.
 *
 * <h2>Batch window behaviour</h2>
 * <ol>
 *   <li>First call to {@link #onXpGained} opens a 30-second window and schedules
 *       {@link #flushBatch()} via the executor.</li>
 *   <li>All subsequent gains within that window are accumulated in {@link #xpBuffer}
 *       using a simple merge (skill → total gained).</li>
 *   <li>At T+30 s {@link #flushBatch()} drains the buffer atomically and calls
 *       {@link RunealyticsApiClient#syncXpBatch}.</li>
 *   <li>The window flag is reset so the <em>next</em> XP gain opens a fresh window.</li>
 * </ol>
 *
 * <h2>Thread-safety</h2>
 * {@code ConcurrentHashMap.merge} is atomic per-key, so {@link #xpBuffer} is safe
 * for concurrent writes from {@link #onXpGained} (client thread) and reads from
 * {@link #flushBatch()} (executor thread).  {@link #windowOpen} is an
 * {@link AtomicBoolean} so the compare-and-set that opens a new window is race-free.
 *
 * @see RuneAlyticsPlugin#onStatChanged  (calls {@link #onXpGained})
 * @see RuneAlyticsPlugin#shutDown       (calls {@link #flushImmediate})
 */
@Singleton
public class XpTrackerManager
{
    private static final Logger log = LoggerFactory.getLogger(XpTrackerManager.class);

    /** Length of one accumulation window in seconds */
    private static final int BATCH_WINDOW_SECONDS = 30;

    // ── Dependencies ──────────────────────────────────────────────────────────

    /** @see RunealyticsApiClient#syncXpBatch */
    private final RunealyticsApiClient apiClient;
    /** Shared executor injected by RuneLite — never create a new one here */
    private final ScheduledExecutorService executor;

    // ── Buffer ────────────────────────────────────────────────────────────────

    /**
     * Accumulates XP gained per skill within the current 30-second window.
     *
     * <p>Key:   {@link Skill} enum constant (never OVERALL).</p>
     * <p>Value: total XP gained for that skill during the current window.</p>
     *
     * <p>Using {@link ConcurrentHashMap} so both the client thread (writing via
     * {@link #onXpGained}) and the executor thread (reading in {@link #flushBatch})
     * can access it without an explicit lock.</p>
     */
    private final ConcurrentHashMap<Skill, Integer> xpBuffer = new ConcurrentHashMap<>();

    /**
     * {@code true} while a 30-second flush is already scheduled.
     * {@link AtomicBoolean#compareAndSet} guarantees only one window is ever open.
     */
    private final AtomicBoolean windowOpen = new AtomicBoolean(false);

    /**
     * Handle to the pending flush task.
     * Kept so {@link #flushImmediate()} can cancel it on shutdown.
     */
    private volatile ScheduledFuture<?> flushTask;

    // ── Constructor ───────────────────────────────────────────────────────────

    @Inject
    public XpTrackerManager(RunealyticsApiClient apiClient, ScheduledExecutorService executor)
    {
        this.apiClient = apiClient;
        this.executor  = executor;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record a single XP gain event.
     *
     * <p>Call this from {@code RuneAlyticsPlugin.onStatChanged} every time the
     * local player gains XP in a skill.  This method is safe to call from the
     * RuneLite client thread.</p>
     *
     * <p>If no batch window is currently open this will open one and schedule
     * {@link #flushBatch()} to run in {@value #BATCH_WINDOW_SECONDS} seconds.
     * Subsequent calls within the same window just accumulate into the buffer.</p>
     *
     * @param skill     the skill that gained XP (must not be {@link Skill#OVERALL})
     * @param xpGained  the raw XP amount gained this tick (must be &gt; 0)
     */
    public void onXpGained(Skill skill, int xpGained)
    {
        if (xpGained <= 0 || skill == Skill.OVERALL) return;

        // Thread-safe accumulation: atomically adds xpGained to the existing value
        // (or sets it if this is the first gain for this skill in the window).
        xpBuffer.merge(skill, xpGained, Integer::sum);

        // Open a new window on the first gain — compareAndSet is atomic,
        // so only one caller ever transitions false → true and schedules the task.
        if (windowOpen.compareAndSet(false, true))
        {
            log.debug("[XP] Batch window opened — flushing in {}s", BATCH_WINDOW_SECONDS);
            flushTask = executor.schedule(this::flushBatch, BATCH_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Non-destructive snapshot of XP accumulated in the <em>current</em>
     * 30-second window, for embedding in the live-map heartbeat as a preview.
     *
     * <p>Unlike {@link #flushBatch()}, this does NOT drain {@link #xpBuffer} or
     * touch {@link #windowOpen} — the authoritative 30-second flush to
     * {@code /xp/batch} (via {@link RunealyticsApiClient#syncXpBatch}) is
     * completely unaffected by this method existing or being called. The
     * heartbeat is a read-only passenger on this data, not a second writer.</p>
     *
     * @return skill-name (lowercase) → XP gained so far in the open window;
     *         empty map if no window is currently open
     */
    public Map<String, Integer> peekPendingGains()
    {
        Map<String, Integer> snapshot = new HashMap<>();
        for (Map.Entry<Skill, Integer> e : xpBuffer.entrySet())
        {
            if (e.getValue() != null && e.getValue() > 0)
                snapshot.put(e.getKey().getName().toLowerCase(), e.getValue());
        }
        return snapshot;
    }

    /**
     * Flush any pending XP immediately (e.g. on plugin shutdown or logout).
     *
     * <p>Cancels the scheduled flush task (if any) and calls {@link #flushBatch()}
     * synchronously on the <em>calling</em> thread.  Safe to call from any thread.</p>
     */
    public void flushImmediate()
    {
        // Cancel the 30-second task so it does not double-send
        ScheduledFuture<?> task = flushTask;
        if (task != null && !task.isDone())
        {
            task.cancel(false);
            flushTask = null;
        }

        if (!xpBuffer.isEmpty())
        {
            log.info("[XP] Immediate flush on shutdown ({} skill(s))", xpBuffer.size());
            flushBatch();
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Drains {@link #xpBuffer}, resets {@link #windowOpen}, and dispatches a
     * single POST to {@code /api/xp/batch}.
     *
     * <p>Runs on the {@link ScheduledExecutorService} thread — never on the
     * RuneLite client thread — so blocking I/O here is safe.</p>
     */
    private void flushBatch()
    {
        // Reset the window flag BEFORE draining the buffer so that any XP events
        // that arrive while we are building `toSend` open a new window rather than
        // being silently dropped.
        windowOpen.set(false);

        if (xpBuffer.isEmpty())
        {
            log.debug("[XP] Flush triggered but buffer is empty — nothing to send");
            return;
        }

        // Drain: remove each skill from the live map so concurrent writes during
        // drain go into a fresh bucket (they will trigger a new window via
        // compareAndSet above because windowOpen is already false).
        // Convert Skill enum keys to lowercase name strings so RunealyticsApiClient
        // has no dependency on the RuneLite API Skill type.
        Map<String, Integer> toSend = new HashMap<>();
        for (Skill skill : Skill.values())
        {
            Integer gained = xpBuffer.remove(skill);
            if (gained != null && gained > 0)
                toSend.put(skill.getName().toLowerCase(), gained);
        }

        if (toSend.isEmpty()) return;

        log.info("[XP] Sending batch: {} skill(s) — {}",
                toSend.size(),
                toSend.entrySet().stream()
                        .map(e -> e.getKey() + "+" + e.getValue())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse(""));

        apiClient.syncXpBatch(toSend);
    }
}