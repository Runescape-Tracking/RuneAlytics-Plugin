package com.runealytics;

import lombok.Getter;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory XP session tracker for the RuneAlytics XP Tracker tab.
 *
 * <p>Distinct from {@link XpTrackerManager}, which batches incremental XP gains
 * to {@code /xp/batch} for the website. This manager keeps a rich, per-skill
 * <em>session</em> model (baseline XP, XP/hr, recent drops, trend samples) that
 * drives the side-panel UI and the {@code /xp/session} snapshot sync.</p>
 *
 * <h2>Account scoping</h2>
 * Every {@link #recordXp} call resolves the current account key via
 * {@link CurrentPlayerIdentityService}. If it differs from the account the
 * current session belongs to, the session is reset and re-baselined for the new
 * account — so a different RuneLite profile / RuneScape account can never mix
 * its XP into another's session.
 *
 * <h2>"Today" total</h2>
 * A per-account running total of XP earned on the current calendar day is kept
 * and persisted (throttled) via {@link ConfigManager}, so it survives client
 * restarts and spans multiple sessions within the same day, resetting at local
 * midnight.
 *
 * <h2>Rate history</h2>
 * XP/hr is sampled on a throttled cadence (driven by the panel's refresh timer)
 * into rolling, last-hour buffers — one overall and one per skill — which the
 * sparkline charts plot.
 *
 * <h2>Threading</h2>
 * {@link #recordXp} runs on the RuneLite client thread; {@link #sampleRates} and
 * the aggregate getters run on the Swing EDT. State fields are {@code volatile}
 * and collections are copied / concurrent, so a marginally stale read is
 * harmless and never throws.
 */
@Singleton
class RuneAlyticsXpSessionManager
{
    private static final Logger log = LoggerFactory.getLogger(RuneAlyticsXpSessionManager.class);

    private static final String CFG_GROUP = "runealytics";

    /** Rolling rate-history window (1 hour). */
    private static final long RATE_WINDOW_MS = 3_600_000L;
    /** Minimum gap between rate samples. */
    private static final long RATE_SAMPLE_INTERVAL_MS = 3_000L;
    /** Cap on overall rate-history samples. */
    private static final int  MAX_RATE_SAMPLES = 300;
    /** Throttle for persisting the "today" total to config. */
    private static final long TODAY_PERSIST_INTERVAL_MS = 10_000L;

    private final RunealyticsConfig config;
    private final CurrentPlayerIdentityService identity;
    private final ConfigManager configManager;

    private final Map<Skill, RuneAlyticsXpSkillState> states = new ConcurrentHashMap<>();

    /** Normalized account key the current session belongs to ({@code null} = none). */
    @Getter private volatile String sessionAccountKey;
    /** Wall-clock ms the current session began ({@code 0} = not started). */
    @Getter private volatile long sessionStartMs;

    // Session-level active-time accounting (active-clock domain).
    private volatile boolean sessionStarted;
    private volatile long sessionFirstActiveMs;
    private volatile long sessionLastActiveMs;
    private volatile long sessionAfkPausedMs;

    // Active-session clock: real elapsed time minus any logged-out spans, so the
    // session runtime and every XP/hr figure freeze while logged out and resume
    // on login. pauseStartWall != 0 means we are currently paused (logged out).
    private volatile boolean loggedIn;
    private volatile long pausedAccumMs;
    private volatile long pauseStartWall;

    // Rolling overall XP/hr history (last hour).
    private final List<RuneAlyticsXpSkillState.Sample> overallRateHistory = new ArrayList<>();
    private volatile long lastRateSampleMs;

    // "Today" running total (per account, persisted).
    private volatile long   todayXpGained;
    private volatile String todayDate;
    private volatile long   lastTodayPersistMs;

    // Skills hidden from the list (still counted in totals). Persisted globally.
    private static final String HIDDEN_KEY = "xpHiddenSkills";
    private final java.util.Set<Skill> hiddenSkills = ConcurrentHashMap.newKeySet();

    @Inject
    RuneAlyticsXpSessionManager(RunealyticsConfig config, CurrentPlayerIdentityService identity,
                                ConfigManager configManager)
    {
        this.config        = config;
        this.identity      = identity;
        this.configManager = configManager;
        loadHidden();
    }

    // ── Ingest (client thread) ────────────────────────────────────────────────

    /**
     * Records a raw XP observation for a skill. Safe to call every StatChanged.
     *
     * @param skill the skill (OVERALL is ignored)
     * @param xp    the skill's current total XP as reported by the event
     */
    void recordXp(Skill skill, long xp)
    {
        if (skill == Skill.OVERALL) return;

        String acct = identity.getAccountKey();
        if (acct == null) return; // no logged-in player yet — nothing to scope to

        if (!acct.equals(sessionAccountKey))
        {
            startNewSession(acct);
        }

        long now = System.currentTimeMillis();
        RuneAlyticsXpSkillState st = states.get(skill);
        if (st == null)
        {
            // First observation this session → baseline (ignore the startup spike).
            states.put(skill, new RuneAlyticsXpSkillState(skill, xp));
            return;
        }

        long activeNow = activeElapsed(now);
        int gained = st.record(xp, now, activeNow, config.xpIgnoreAfk(), afkTimeoutMs());
        if (gained > 0)
        {
            addToday(gained, now, acct);
            onSessionGain(activeNow);
        }
    }

    private void onSessionGain(long activeNow)
    {
        if (!sessionStarted)
        {
            sessionStarted       = true;
            sessionFirstActiveMs = activeNow;
        }
        else
        {
            long gap = activeNow - sessionLastActiveMs;
            if (gap > afkTimeoutMs()) sessionAfkPausedMs += (gap - afkTimeoutMs());
        }
        sessionLastActiveMs = activeNow;
    }

    private void startNewSession(String acct)
    {
        states.clear();
        synchronized (overallRateHistory) { overallRateHistory.clear(); }
        sessionAccountKey    = acct;
        sessionStartMs       = System.currentTimeMillis();
        sessionStarted       = false;
        sessionFirstActiveMs = 0L;
        sessionLastActiveMs  = 0L;
        sessionAfkPausedMs   = 0L;
        lastRateSampleMs     = 0L;
        // A gain implies we're in-game (covers the plugin being enabled
        // mid-session, when no LOGGED_IN event fires), so start the clock running.
        loggedIn             = true;
        pausedAccumMs        = 0L;
        pauseStartWall       = 0L;
        loadToday(acct);
        log.debug("[XP-Session] started for account '{}'", acct);
    }

    // ── Active-session clock ──────────────────────────────────────────────────

    /**
     * Notifies the manager of login state so the active-session clock can pause
     * (logout) and resume (login). Called from the plugin's game-state handler.
     */
    void setLoggedIn(boolean in)
    {
        long now = System.currentTimeMillis();
        if (in)
        {
            if (pauseStartWall > 0L)
            {
                pausedAccumMs += Math.max(0L, now - pauseStartWall);
                pauseStartWall = 0L;
            }
        }
        else if (pauseStartWall == 0L)
        {
            pauseStartWall = now;
        }
        loggedIn = in;
    }

    /** Active elapsed session ms (real elapsed minus logged-out spans). */
    long activeElapsed(long wallNow)
    {
        if (sessionStartMs == 0L) return 0L;
        long paused = pausedAccumMs + (pauseStartWall > 0L ? Math.max(0L, wallNow - pauseStartWall) : 0L);
        return Math.max(0L, (wallNow - sessionStartMs) - paused);
    }

    // ── "Today" total ─────────────────────────────────────────────────────────

    private void addToday(long gained, long now, String acct)
    {
        String today = LocalDate.now().toString();
        if (!today.equals(todayDate))
        {
            todayDate     = today;
            todayXpGained = 0L;
        }
        todayXpGained += gained;

        if (now - lastTodayPersistMs > TODAY_PERSIST_INTERVAL_MS)
        {
            lastTodayPersistMs = now;
            persistToday(acct);
        }
    }

    private void loadToday(String acct)
    {
        String today = LocalDate.now().toString();
        try
        {
            String storedDate = configManager.getConfiguration(CFG_GROUP, todayDateKey(acct));
            String storedVal  = configManager.getConfiguration(CFG_GROUP, todayTotalKey(acct));
            if (today.equals(storedDate) && storedVal != null)
            {
                todayXpGained = Long.parseLong(storedVal.trim());
            }
            else
            {
                todayXpGained = 0L;
            }
        }
        catch (Exception e)
        {
            log.debug("[XP-Session] could not load today total for '{}': {}", acct, e.getMessage());
            todayXpGained = 0L;
        }
        todayDate = today;
    }

    private void persistToday(String acct)
    {
        try
        {
            configManager.setConfiguration(CFG_GROUP, todayDateKey(acct),  todayDate);
            configManager.setConfiguration(CFG_GROUP, todayTotalKey(acct), Long.toString(todayXpGained));
        }
        catch (Exception e)
        {
            log.debug("[XP-Session] could not persist today total for '{}': {}", acct, e.getMessage());
        }
    }

    private static String sanitize(String acct)
    {
        return acct == null ? "unknown" : acct.replaceAll("[^a-z0-9]", "_");
    }

    private static String todayTotalKey(String acct) { return "xpTodayTotal_" + sanitize(acct); }
    private static String todayDateKey(String acct)  { return "xpTodayDate_"  + sanitize(acct); }

    long todayXpGained()
    {
        // Reset the displayed value at local midnight even before the next gain.
        return LocalDate.now().toString().equals(todayDate) ? todayXpGained : 0L;
    }

    // ── Rate sampling (EDT-driven, throttled) ─────────────────────────────────

    /**
     * Samples the current overall and per-skill XP/hr into the rolling last-hour
     * buffers. Throttled internally — safe to call every panel refresh tick.
     */
    void sampleRates(long now)
    {
        if (now - lastRateSampleMs < RATE_SAMPLE_INTERVAL_MS) return;
        lastRateSampleMs = now;

        boolean ignoreAfk = config.xpIgnoreAfk();
        long afk = afkTimeoutMs();
        long activeNow = activeElapsed(now);

        long rate = overallXpPerHour(now);
        synchronized (overallRateHistory)
        {
            overallRateHistory.add(new RuneAlyticsXpSkillState.Sample(now, rate));
            long cutoff = now - RATE_WINDOW_MS;
            overallRateHistory.removeIf(s -> s.timeMs < cutoff);
            while (overallRateHistory.size() > MAX_RATE_SAMPLES) overallRateHistory.remove(0);
        }

        for (RuneAlyticsXpSkillState st : states.values())
        {
            st.sampleRate(now, activeNow, ignoreAfk, afk, RATE_WINDOW_MS);
        }
    }

    List<RuneAlyticsXpSkillState.Sample> overallRateHistorySnapshot()
    {
        synchronized (overallRateHistory)
        {
            return overallRateHistory.isEmpty()
                    ? Collections.emptyList() : new ArrayList<>(overallRateHistory);
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    /** Resets the current session (keeps the account so it re-baselines lazily). */
    void reset()
    {
        states.clear();
        synchronized (overallRateHistory) { overallRateHistory.clear(); }
        sessionStartMs       = System.currentTimeMillis();
        sessionStarted       = false;
        sessionFirstActiveMs = 0L;
        sessionLastActiveMs  = 0L;
        sessionAfkPausedMs   = 0L;
        lastRateSampleMs     = 0L;
        pausedAccumMs        = 0L;
        pauseStartWall       = loggedIn ? 0L : sessionStartMs;
        log.debug("[XP-Session] reset (account='{}')", sessionAccountKey);
    }

    /** Removes a single skill's session state; it re-baselines on its next gain. */
    void resetSkill(Skill skill)
    {
        if (skill != null) states.remove(skill);
    }

    /** Resets only a single skill's XP/hr timing, keeping its gained XP. */
    void resetSkillRate(Skill skill)
    {
        RuneAlyticsXpSkillState st = (skill == null) ? null : states.get(skill);
        if (st != null) st.resetRate();
    }

    long afkTimeoutMs()
    {
        return Math.max(1, config.xpAfkTimeout()) * 60_000L;
    }

    // ── Aggregate reads (EDT-safe) ────────────────────────────────────────────

    long totalXpGained()
    {
        long sum = 0L;
        for (RuneAlyticsXpSkillState st : states.values()) sum += st.getTotalGained();
        return sum;
    }

    long runtimeMs(long nowMs)
    {
        // Session runtime excludes logged-out time (pauses on logout).
        return activeElapsed(nowMs);
    }

    long overallXpPerHour(long nowMs)
    {
        long total = totalXpGained();
        if (total <= 0L || !sessionStarted) return 0L;

        long activeNow = activeElapsed(nowMs);
        long elapsed = activeNow - sessionFirstActiveMs;
        if (config.xpIgnoreAfk())
        {
            long paused = sessionAfkPausedMs;
            long idle = activeNow - sessionLastActiveMs;
            if (idle > afkTimeoutMs()) paused += (idle - afkTimeoutMs());
            elapsed -= paused;
        }
        if (elapsed < 3_000L) return 0L;
        return total * 3_600_000L / elapsed;
    }

    int levelsGained()
    {
        int sum = 0;
        for (RuneAlyticsXpSkillState st : states.values())
        {
            int start = Math.min(st.getStartLevel(),   Experience.MAX_REAL_LEVEL);
            int now   = Math.min(st.getCurrentLevel(), Experience.MAX_REAL_LEVEL);
            if (now > start) sum += (now - start);
        }
        return sum;
    }

    boolean hasAnyGains()
    {
        for (RuneAlyticsXpSkillState st : states.values())
        {
            if (st.hasGains()) return true;
        }
        return false;
    }

    RuneAlyticsXpSkillState getState(Skill skill)
    {
        return skill == null ? null : states.get(skill);
    }

    /** Skills that have gained XP this session, most-recently-trained first. */
    List<RuneAlyticsXpSkillState> snapshotStates()
    {
        return snapshotStates(false, false);
    }

    /**
     * Session skills, most-recently-trained first (so the actively-training skill
     * floats to the top). Untrained skills — which have {@code lastGainMs == 0} —
     * sort to the bottom in skill order and are only included when
     * {@code includeUntrained} is set. Hidden skills are excluded unless
     * {@code includeHidden} is set (they always still count toward totals).
     */
    List<RuneAlyticsXpSkillState> snapshotStates(boolean includeUntrained, boolean includeHidden)
    {
        List<RuneAlyticsXpSkillState> list = new ArrayList<>();
        for (RuneAlyticsXpSkillState st : states.values())
        {
            if (!includeUntrained && !st.hasGains()) continue;
            if (!includeHidden && hiddenSkills.contains(st.getSkill())) continue;
            list.add(st);
        }
        list.sort(Comparator
                .comparingLong(RuneAlyticsXpSkillState::getLastGainWallMs).reversed()
                .thenComparingInt(s -> s.getSkill().ordinal()));
        return list;
    }

    // ── Hidden skills ─────────────────────────────────────────────────────────

    boolean isHidden(Skill skill)
    {
        return skill != null && hiddenSkills.contains(skill);
    }

    int hiddenCount()
    {
        return hiddenSkills.size();
    }

    void setHidden(Skill skill, boolean hidden)
    {
        if (skill == null) return;
        boolean changed = hidden ? hiddenSkills.add(skill) : hiddenSkills.remove(skill);
        if (changed) persistHidden();
    }

    private void loadHidden()
    {
        try
        {
            String csv = configManager.getConfiguration(CFG_GROUP, HIDDEN_KEY);
            if (csv == null || csv.isEmpty()) return;
            for (String name : csv.split(","))
            {
                String n = name.trim();
                if (n.isEmpty()) continue;
                try { hiddenSkills.add(Skill.valueOf(n)); }
                catch (IllegalArgumentException ignored) { /* stale/unknown skill name */ }
            }
        }
        catch (Exception e)
        {
            log.debug("[XP-Session] could not load hidden skills: {}", e.getMessage());
        }
    }

    private void persistHidden()
    {
        try
        {
            StringBuilder sb = new StringBuilder();
            for (Skill s : hiddenSkills)
            {
                if (sb.length() > 0) sb.append(',');
                sb.append(s.name());
            }
            configManager.setConfiguration(CFG_GROUP, HIDDEN_KEY, sb.toString());
        }
        catch (Exception e)
        {
            log.debug("[XP-Session] could not persist hidden skills: {}", e.getMessage());
        }
    }

    // ── Sync payload ──────────────────────────────────────────────────────────

    /**
     * Builds an immutable snapshot of the current session for the website sync.
     * Scoped entirely to the in-memory session, which belongs to a single account.
     *
     * @param username    normalized username to stamp on the payload
     * @param profileId   RuneLite rsprofile / account id, or {@code null}
     * @param gameMode    current game mode (e.g. "regular", "ironman")
     * @param accountType current account subtype (e.g. "normal", "ironman")
     */
    RuneAlyticsXpSyncPayload buildPayload(String username, String profileId,
                                          String gameMode, String accountType)
    {
        long now = System.currentTimeMillis();
        boolean ignoreAfk = config.xpIgnoreAfk();
        long afk = afkTimeoutMs();
        long activeNow = activeElapsed(now);

        List<RuneAlyticsXpSyncPayload.SkillEntry> entries = new ArrayList<>();
        for (RuneAlyticsXpSkillState st : states.values())
        {
            if (!st.hasGains()) continue;
            entries.add(new RuneAlyticsXpSyncPayload.SkillEntry(
                    st.getSkill().getName().toLowerCase(),
                    st.getTotalGained(),
                    st.xpPerHour(activeNow, ignoreAfk, afk),
                    st.displayLevel(),
                    st.getCurrentXp()));
        }

        long startSec = sessionStartMs / 1_000L;
        long durationSec = runtimeMs(now) / 1_000L;

        return new RuneAlyticsXpSyncPayload(
                username, profileId, gameMode, accountType,
                startSec, durationSec, totalXpGained(), now / 1_000L, entries);
    }
}
