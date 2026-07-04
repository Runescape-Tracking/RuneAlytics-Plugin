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
 * <p>One instance is created per skill the moment the first
 * {@link net.runelite.api.events.StatChanged} for that skill is observed after
 * login. That first observation is treated as the <em>baseline</em> — its XP is
 * recorded as {@link #startXp} and no gain is counted, which is how invalid
 * startup XP spikes are ignored.</p>
 *
 * <h2>Timekeeping</h2>
 * All rate math ({@link #xpPerHour}, {@link #actionsPerHour},
 * {@link #timeToNextLevelMs}) works in the <em>active-session clock</em> supplied
 * by {@link RuneAlyticsXpSessionManager}. That clock excludes logged-out time, so
 * XP/hr and time-to-level freeze while the player is logged out and resume on
 * login — the rate is computed purely from time actually spent in-game. When AFK
 * smoothing is enabled, in-game idle beyond the AFK timeout is also excluded.
 *
 * <p>Drop timestamps, the "LIVE" marker and chart sample X-positions use real
 * wall-clock time so "time ago" and the last-hour window stay truthful.</p>
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

    /** True once at least one real gain has been recorded. */
    private volatile boolean started;
    /** Active-clock ms of the first real gain. */
    private volatile long firstActiveMs;
    /** Active-clock ms of the most recent real gain. */
    private volatile long lastActiveMs;
    /** Accumulated in-game idle (active-clock ms) beyond the AFK grace window. */
    private volatile long afkPausedMs;

    /** Wall-clock ms of the most recent gain — drives "LIVE" + list ordering. */
    private volatile long lastGainWallMs;

    private volatile int lastDropXp;
    private volatile int actions;

    /** Most-recent-first list of XP drops (amount + wall time). Guarded by {@code this}. */
    private final Deque<XpDrop> recentDrops = new ArrayDeque<>();
    /** Cumulative-gained trend samples (wall time). Guarded by {@code this}. */
    private final List<Sample> samples = new ArrayList<>();
    /** Rolling XP/hr samples (last hour, wall time). Guarded by {@code this}. */
    private final List<Sample> rateHistory = new ArrayList<>();

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
     * @param newXp     the skill's current total XP
     * @param wallNow   real wall-clock ms
     * @param activeNow active-session-clock ms (excludes logged-out time)
     * @return the positive XP gained, or {@code 0} when not a forward gain.
     */
    synchronized int record(long newXp, long wallNow, long activeNow, boolean ignoreAfk, long afkTimeoutMs)
    {
        int gained = (int) (newXp - currentXp);
        if (gained <= 0)
        {
            if (newXp > currentXp) currentXp = newXp;
            return 0;
        }

        if (!started)
        {
            started       = true;
            firstActiveMs = activeNow;
        }
        else
        {
            long gap = activeNow - lastActiveMs;
            if (gap > afkTimeoutMs) afkPausedMs += (gap - afkTimeoutMs);
        }
        lastActiveMs   = activeNow;
        lastGainWallMs = wallNow;

        currentXp    = newXp;
        currentLevel = levelForXp(newXp);
        totalGained  = currentXp - startXp;
        lastDropXp   = gained;
        actions++;

        recentDrops.addFirst(new XpDrop(gained, wallNow));
        while (recentDrops.size() > MAX_RECENT_DROPS) recentDrops.removeLast();

        samples.add(new Sample(wallNow, totalGained));
        if (samples.size() > MAX_SAMPLES) downSample();

        return gained;
    }

    private void downSample()
    {
        List<Sample> compact = new ArrayList<>(MAX_SAMPLES / 2 + 2);
        for (int i = 0; i < samples.size(); i += 2) compact.add(samples.get(i));
        Sample last = samples.get(samples.size() - 1);
        if (compact.isEmpty() || compact.get(compact.size() - 1) != last) compact.add(last);
        samples.clear();
        samples.addAll(compact);
    }

    // ── Derived stats (EDT-safe reads) ────────────────────────────────────────

    int displayLevel()
    {
        return Math.min(currentLevel, Experience.MAX_REAL_LEVEL);
    }

    boolean hasGains()
    {
        return totalGained > 0L;
    }

    long xpToNextLevel()
    {
        int next = levelForXp(currentXp) + 1;
        if (next > Experience.MAX_VIRT_LEVEL) return 0L;
        return Experience.getXpForLevel(next) - currentXp;
    }

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

    /** Active training time (active-clock ms), AFK-adjusted when enabled. */
    long activeMillis(long activeNow, boolean ignoreAfk, long afkTimeoutMs)
    {
        if (!started) return 0L;
        long elapsed = activeNow - firstActiveMs;
        if (elapsed < 0L) elapsed = 0L;
        if (!ignoreAfk) return elapsed;

        long paused = afkPausedMs;
        long idle = activeNow - lastActiveMs;
        if (idle > afkTimeoutMs) paused += (idle - afkTimeoutMs);
        return Math.max(0L, elapsed - paused);
    }

    long xpPerHour(long activeNow, boolean ignoreAfk, long afkTimeoutMs)
    {
        long active = activeMillis(activeNow, ignoreAfk, afkTimeoutMs);
        if (active < 3_000L || totalGained <= 0L) return 0L;
        return totalGained * 3_600_000L / active;
    }

    long actionsPerHour(long activeNow, boolean ignoreAfk, long afkTimeoutMs)
    {
        long active = activeMillis(activeNow, ignoreAfk, afkTimeoutMs);
        if (active < 3_000L || actions <= 0) return 0L;
        return (long) actions * 3_600_000L / active;
    }

    long timeToNextLevelMs(long activeNow, boolean ignoreAfk, long afkTimeoutMs)
    {
        long rate = xpPerHour(activeNow, ignoreAfk, afkTimeoutMs);
        long toNext = xpToNextLevel();
        if (rate <= 0L || toNext <= 0L) return -1L;
        return toNext * 3_600_000L / rate;
    }

    synchronized List<XpDrop> recentDropsSnapshot()
    {
        return new ArrayList<>(recentDrops);
    }

    synchronized List<Sample> samplesSnapshot()
    {
        return samples.isEmpty() ? Collections.emptyList() : new ArrayList<>(samples);
    }

    /** Appends a rolling XP/hr sample (wall X, rate Y) and prunes past {@code windowMs}. */
    synchronized void sampleRate(long wallNow, long activeNow, boolean ignoreAfk,
                                 long afkTimeoutMs, long windowMs)
    {
        rateHistory.add(new Sample(wallNow, xpPerHour(activeNow, ignoreAfk, afkTimeoutMs)));
        long cutoff = wallNow - windowMs;
        rateHistory.removeIf(s -> s.timeMs < cutoff);
        while (rateHistory.size() > MAX_SAMPLES * 3) rateHistory.remove(0);
    }

    synchronized List<Sample> rateHistorySnapshot()
    {
        return rateHistory.isEmpty() ? Collections.emptyList() : new ArrayList<>(rateHistory);
    }

    /** Resets only the XP/hr timing (and rate history), keeping the XP gained. */
    synchronized void resetRate()
    {
        started        = false;
        firstActiveMs  = 0L;
        lastActiveMs   = 0L;
        afkPausedMs    = 0L;
        lastGainWallMs = 0L;
        rateHistory.clear();
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
