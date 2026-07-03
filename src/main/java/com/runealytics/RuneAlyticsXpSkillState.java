package com.runealytics;

import lombok.Getter;
import net.runelite.api.Experience;
import net.runelite.api.Skill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Per-skill XP session state for the RuneAlytics XP Tracker.
 *
 * <p>One instance is created per skill the moment the first {@link net.runelite.api.events.StatChanged}
 * for that skill is observed after login. That first observation is treated as
 * the <em>baseline</em> — its XP is recorded as {@link #startXp} and no gain is
 * counted, which is how invalid startup XP spikes are ignored (a skill's first
 * StatChanged after login carries the full current XP, not a delta).</p>
 *
 * <h2>XP/hr &amp; AFK smoothing</h2>
 * XP/hr is {@code totalGained / activeTime}. When AFK-ignoring is enabled, any
 * idle gap between two gains longer than the AFK timeout has its excess
 * subtracted from the active time, so standing idle does not deflate the rate.
 * The subtraction is also applied live (against "now") so the rate freezes while
 * currently AFK instead of decaying.
 *
 * <p>All mutation happens on the RuneLite client thread (from
 * {@code onStatChanged}); the read-only derived getters used by the Swing panel
 * only read {@code volatile}/primitive fields and copy the drop/sample lists, so
 * a slightly stale read on the EDT is harmless.</p>
 */
@Getter
class RuneAlyticsXpSkillState
{
    /** Max recent XP drops retained for the detail view. */
    static final int MAX_RECENT_DROPS = 25;
    /** Max trend samples retained per skill (down-sampled when exceeded). */
    static final int MAX_SAMPLES = 120;

    private final Skill skill;

    private final long startXp;
    private final int  startLevel;

    private volatile long currentXp;
    private volatile int  currentLevel;
    private volatile long totalGained;

    /** Wall-clock ms of the first real gain (0 until then). */
    private volatile long firstGainMs;
    /** Wall-clock ms of the most recent real gain. */
    private volatile long lastGainMs;
    /** Accumulated idle time (ms) beyond the AFK grace window, from past gaps. */
    private volatile long afkPausedMs;

    private volatile int lastDropXp;
    private volatile int actions;

    /** Most-recent-first list of XP drops (amount + time). Guarded by {@code this}. */
    private final Deque<XpDrop> recentDrops = new ArrayDeque<>();
    /** Cumulative-gained trend samples. Guarded by {@code this}. */
    private final List<Sample> samples = new ArrayList<>();

    RuneAlyticsXpSkillState(Skill skill, long baselineXp)
    {
        this.skill        = skill;
        this.startXp      = baselineXp;
        this.currentXp    = baselineXp;
        this.startLevel   = levelForXp(baselineXp);
        this.currentLevel = this.startLevel;
    }

    // ── Mutation (client thread) ──────────────────────────────────────────────

    /**
     * Records a raw XP observation for this skill.
     *
     * @return the positive XP gained, or {@code 0} when the observation was not a
     *         forward gain (equal or, defensively, lower XP).
     */
    synchronized int record(long newXp, long nowMs, boolean ignoreAfk, long afkTimeoutMs)
    {
        int gained = (int) (newXp - currentXp);
        if (gained <= 0)
        {
            // Defensive: never let XP go backwards (e.g. a stray duplicate event).
            if (newXp > currentXp) currentXp = newXp;
            return 0;
        }

        if (firstGainMs == 0L)
        {
            firstGainMs = nowMs;
        }
        else
        {
            long gap = nowMs - lastGainMs;
            if (gap > afkTimeoutMs) afkPausedMs += (gap - afkTimeoutMs);
        }
        lastGainMs = nowMs;

        currentXp    = newXp;
        currentLevel = levelForXp(newXp);
        totalGained  = currentXp - startXp;
        lastDropXp   = gained;
        actions++;

        recentDrops.addFirst(new XpDrop(gained, nowMs));
        while (recentDrops.size() > MAX_RECENT_DROPS) recentDrops.removeLast();

        samples.add(new Sample(nowMs, totalGained));
        if (samples.size() > MAX_SAMPLES) downSample();

        return gained;
    }

    /** Halves the sample buffer, preserving the overall trend shape. */
    private void downSample()
    {
        List<Sample> compact = new ArrayList<>(MAX_SAMPLES / 2 + 2);
        for (int i = 0; i < samples.size(); i += 2) compact.add(samples.get(i));
        // Always keep the latest point so the line ends at the real value.
        Sample last = samples.get(samples.size() - 1);
        if (compact.isEmpty() || compact.get(compact.size() - 1) != last) compact.add(last);
        samples.clear();
        samples.addAll(compact);
    }

    // ── Derived stats (EDT-safe reads) ────────────────────────────────────────

    /** Real (capped-99) level for display. */
    int displayLevel()
    {
        return Math.min(currentLevel, Experience.MAX_REAL_LEVEL);
    }

    boolean hasGains()
    {
        return totalGained > 0L;
    }

    /** XP remaining to the next level, or {@code 0} at the virtual cap. */
    long xpToNextLevel()
    {
        int next = levelForXp(currentXp) + 1;
        if (next > Experience.MAX_VIRT_LEVEL) return 0L;
        return Experience.getXpForLevel(next) - currentXp;
    }

    /** Progress through the current level as a fraction {@code 0..1}. */
    double levelProgress()
    {
        int lvl = levelForXp(currentXp);
        if (lvl >= Experience.MAX_VIRT_LEVEL) return 1.0;
        long base = Experience.getXpForLevel(lvl);
        long next = Experience.getXpForLevel(lvl + 1);
        if (next <= base) return 1.0;
        double p = (currentXp - base) / (double) (next - base);
        return Math.max(0.0, Math.min(1.0, p));
    }

    /** Active (AFK-adjusted when enabled) session time for this skill, in ms. */
    long activeMillis(long nowMs, boolean ignoreAfk, long afkTimeoutMs)
    {
        if (firstGainMs == 0L) return 0L;
        long elapsed = nowMs - firstGainMs;
        if (elapsed < 0L) elapsed = 0L;
        if (!ignoreAfk) return elapsed;

        long paused = afkPausedMs;
        long idle = nowMs - lastGainMs;
        if (idle > afkTimeoutMs) paused += (idle - afkTimeoutMs);
        return Math.max(0L, elapsed - paused);
    }

    long xpPerHour(long nowMs, boolean ignoreAfk, long afkTimeoutMs)
    {
        long active = activeMillis(nowMs, ignoreAfk, afkTimeoutMs);
        // Require ≥3s of active time before quoting a rate so the first couple of
        // gains don't produce a wildly inflated XP/hr figure.
        if (active < 3_000L || totalGained <= 0L) return 0L;
        return totalGained * 3_600_000L / active;
    }

    long actionsPerHour(long nowMs, boolean ignoreAfk, long afkTimeoutMs)
    {
        long active = activeMillis(nowMs, ignoreAfk, afkTimeoutMs);
        if (active < 3_000L || actions <= 0) return 0L;
        return (long) actions * 3_600_000L / active;
    }

    /** Estimated ms to the next level at the current rate; {@code -1} when unknown. */
    long timeToNextLevelMs(long nowMs, boolean ignoreAfk, long afkTimeoutMs)
    {
        long rate = xpPerHour(nowMs, ignoreAfk, afkTimeoutMs);
        long toNext = xpToNextLevel();
        if (rate <= 0L || toNext <= 0L) return -1L;
        return toNext * 3_600_000L / rate;
    }

    /** Snapshot copy of recent drops (most-recent-first) for the EDT. */
    synchronized List<XpDrop> recentDropsSnapshot()
    {
        return new ArrayList<>(recentDrops);
    }

    /** Snapshot copy of trend samples for the EDT. */
    synchronized List<Sample> samplesSnapshot()
    {
        return samples.isEmpty() ? Collections.emptyList() : new ArrayList<>(samples);
    }

    private static int levelForXp(long xp)
    {
        int clamped = (int) Math.min(Math.max(xp, 0L), Experience.MAX_SKILL_XP);
        return Experience.getLevelForXp(clamped);
    }

    // ── Value types ───────────────────────────────────────────────────────────

    static final class XpDrop
    {
        final int  amount;
        final long timeMs;

        XpDrop(int amount, long timeMs)
        {
            this.amount = amount;
            this.timeMs = timeMs;
        }
    }

    static final class Sample
    {
        final long timeMs;
        final long totalGained;

        Sample(long timeMs, long totalGained)
        {
            this.timeMs      = timeMs;
            this.totalGained = totalGained;
        }
    }
}
