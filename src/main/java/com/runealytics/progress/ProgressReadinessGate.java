package com.runealytics.progress;

/**
 * Bounded, tick-counted readiness check for the post-login progress scan.
 * Pure logic, client-thread only.
 *
 * <p>Account variables arrive from the server in a burst after login, and RuneLite
 * posts {@code VarbitChanged} for them. A fixed delay is not proof the burst has
 * finished, so the gate requires:</p>
 * <ol>
 *   <li>identity known (local player present, account hash != -1) and the account's
 *       local baseline loaded;</li>
 *   <li>at least {@link #MIN_TICKS} game ticks since {@code LOGGED_IN};</li>
 *   <li>no variable change during the last {@link #QUIET_TICKS} tick(s).</li>
 * </ol>
 * <p>When those hold the tracker attempts a scan; the scan's own sanity checks
 * (e.g. quest points vs. finished quests) decide whether each category is usable.
 * After {@link #MAX_TICKS} the gate reports {@link Status#TIMED_OUT} and the tracker
 * accepts whatever categories passed, marking the rest unavailable.</p>
 */
public final class ProgressReadinessGate
{
    public enum Status
    {
        /** Not logged in / suspended (e.g. hopping). */
        INACTIVE,
        /** Logged in, conditions not met yet. */
        WAITING,
        /** Conditions met; a scan may be attempted. */
        ATTEMPT,
        /** Cap reached; accept a partial scan. */
        TIMED_OUT,
        /** First usable scan applied. */
        READY
    }

    static final int MIN_TICKS = 2;
    static final int QUIET_TICKS = 1;
    static final int MAX_TICKS = 12;

    private boolean active;
    private boolean ready;
    private long loggedInTick;
    private long lastVarChangeTick = Long.MIN_VALUE;

    /** Called on LOGGED_IN (including after a world hop). */
    public void begin(long tick)
    {
        active = true;
        ready = false;
        loggedInTick = tick;
        lastVarChangeTick = Long.MIN_VALUE;
    }

    /** Called on logout, hop or shutdown. */
    public void suspend()
    {
        active = false;
        ready = false;
    }

    public void onVarChanged(long tick)
    {
        lastVarChangeTick = tick;
    }

    /** Marks the first usable scan as applied. */
    public void markReady()
    {
        if (active) ready = true;
    }

    public boolean isReady()
    {
        return active && ready;
    }

    public boolean isActive()
    {
        return active;
    }

    /**
     * @param tick           current plugin tick counter
     * @param identityKnown  local player present and account hash available
     * @param baselineLoaded the account's local baseline has been loaded from disk
     */
    public Status evaluate(long tick, boolean identityKnown, boolean baselineLoaded)
    {
        if (!active) return Status.INACTIVE;
        if (ready) return Status.READY;

        long elapsed = tick - loggedInTick;
        if (!identityKnown || !baselineLoaded)
        {
            // Without identity we cannot scope data to an account; keep waiting
            // (no time-out: nothing may be captured for an unknown account).
            return Status.WAITING;
        }
        if (elapsed >= MAX_TICKS) return Status.TIMED_OUT;
        if (elapsed < MIN_TICKS) return Status.WAITING;
        boolean quiet = lastVarChangeTick == Long.MIN_VALUE || tick - lastVarChangeTick > QUIET_TICKS;
        return quiet ? Status.ATTEMPT : Status.WAITING;
    }
}
