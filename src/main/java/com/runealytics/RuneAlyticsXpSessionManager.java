package com.runealytics;

import lombok.Getter;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
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
 * <h2>Baseline / startup spikes</h2>
 * The first observation of any skill in a session is stored as its baseline and
 * counts as zero gain, which naturally discards the full-XP StatChanged events
 * RuneLite fires at login.
 *
 * <h2>Threading</h2>
 * {@link #recordXp} runs on the RuneLite client thread. The Swing panel reads
 * the aggregate getters and {@link #snapshotStates()} on the EDT; state fields
 * are {@code volatile} and collections are copied, so a marginally stale read is
 * harmless and never throws.
 */
@Singleton
class RuneAlyticsXpSessionManager
{
    private static final Logger log = LoggerFactory.getLogger(RuneAlyticsXpSessionManager.class);

    /** Cap on overall trend samples (down-sampled when exceeded). */
    private static final int MAX_OVERALL_SAMPLES = 200;

    private final RunealyticsConfig config;
    private final CurrentPlayerIdentityService identity;

    private final Map<Skill, RuneAlyticsXpSkillState> states = new ConcurrentHashMap<>();

    /** Normalized account key the current session belongs to ({@code null} = none). */
    @Getter private volatile String sessionAccountKey;
    /** Wall-clock ms the current session began ({@code 0} = not started). */
    @Getter private volatile long sessionStartMs;

    // Session-level active-time accounting (mirrors the per-skill model).
    private volatile long sessionFirstGainMs;
    private volatile long sessionLastGainMs;
    private volatile long sessionAfkPausedMs;

    private final List<RuneAlyticsXpSkillState.Sample> overallSamples = new ArrayList<>();

    @Inject
    RuneAlyticsXpSessionManager(RunealyticsConfig config, CurrentPlayerIdentityService identity)
    {
        this.config   = config;
        this.identity = identity;
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

        RuneAlyticsXpSkillState st = states.get(skill);
        if (st == null)
        {
            // First observation this session → baseline (ignore the startup spike).
            states.put(skill, new RuneAlyticsXpSkillState(skill, xp));
            return;
        }

        long now = System.currentTimeMillis();
        int gained = st.record(xp, now, config.xpIgnoreAfk(), afkTimeoutMs());
        if (gained > 0)
        {
            onSessionGain(now);
        }
    }

    private void onSessionGain(long nowMs)
    {
        if (sessionFirstGainMs == 0L)
        {
            sessionFirstGainMs = nowMs;
        }
        else
        {
            long gap = nowMs - sessionLastGainMs;
            if (gap > afkTimeoutMs()) sessionAfkPausedMs += (gap - afkTimeoutMs());
        }
        sessionLastGainMs = nowMs;

        long total = totalXpGained();
        synchronized (overallSamples)
        {
            overallSamples.add(new RuneAlyticsXpSkillState.Sample(nowMs, total));
            if (overallSamples.size() > MAX_OVERALL_SAMPLES)
            {
                List<RuneAlyticsXpSkillState.Sample> compact =
                        new ArrayList<>(MAX_OVERALL_SAMPLES / 2 + 2);
                for (int i = 0; i < overallSamples.size(); i += 2) compact.add(overallSamples.get(i));
                RuneAlyticsXpSkillState.Sample last = overallSamples.get(overallSamples.size() - 1);
                if (compact.isEmpty() || compact.get(compact.size() - 1) != last) compact.add(last);
                overallSamples.clear();
                overallSamples.addAll(compact);
            }
        }
    }

    private void startNewSession(String acct)
    {
        states.clear();
        synchronized (overallSamples) { overallSamples.clear(); }
        sessionAccountKey  = acct;
        sessionStartMs     = System.currentTimeMillis();
        sessionFirstGainMs = 0L;
        sessionLastGainMs  = 0L;
        sessionAfkPausedMs = 0L;
        log.debug("[XP-Session] started for account '{}'", acct);
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    /** Resets the current session (keeps the account so it re-baselines lazily). */
    void reset()
    {
        states.clear();
        synchronized (overallSamples) { overallSamples.clear(); }
        sessionStartMs     = System.currentTimeMillis();
        sessionFirstGainMs = 0L;
        sessionLastGainMs  = 0L;
        sessionAfkPausedMs = 0L;
        log.debug("[XP-Session] reset (account='{}')", sessionAccountKey);
    }

    /** Removes a single skill's session state; it re-baselines on its next gain. */
    void resetSkill(Skill skill)
    {
        if (skill != null) states.remove(skill);
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
        return sessionStartMs == 0L ? 0L : Math.max(0L, nowMs - sessionStartMs);
    }

    long overallXpPerHour(long nowMs)
    {
        long total = totalXpGained();
        if (total <= 0L || sessionFirstGainMs == 0L) return 0L;

        long elapsed = nowMs - sessionFirstGainMs;
        if (config.xpIgnoreAfk())
        {
            long paused = sessionAfkPausedMs;
            long idle = nowMs - sessionLastGainMs;
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
        List<RuneAlyticsXpSkillState> list = new ArrayList<>();
        for (RuneAlyticsXpSkillState st : states.values())
        {
            if (st.hasGains()) list.add(st);
        }
        list.sort(Comparator.comparingLong(RuneAlyticsXpSkillState::getLastGainMs).reversed());
        return list;
    }

    List<RuneAlyticsXpSkillState.Sample> overallSamplesSnapshot()
    {
        synchronized (overallSamples)
        {
            return overallSamples.isEmpty() ? Collections.emptyList() : new ArrayList<>(overallSamples);
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

        List<RuneAlyticsXpSyncPayload.SkillEntry> entries = new ArrayList<>();
        for (RuneAlyticsXpSkillState st : states.values())
        {
            if (!st.hasGains()) continue;
            entries.add(new RuneAlyticsXpSyncPayload.SkillEntry(
                    st.getSkill().getName().toLowerCase(),
                    st.getTotalGained(),
                    st.xpPerHour(now, ignoreAfk, afk),
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
