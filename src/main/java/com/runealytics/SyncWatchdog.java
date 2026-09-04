package com.runealytics;

import lombok.extern.slf4j.Slf4j;

/**
 * Timeout/watchdog mechanism for long-running sync operations.
 *
 * <p>Tracks the start time of a sync operation and provides methods to check
 * whether the operation has exceeded a timeout threshold. This helps detect
 * stalled or hung sync operations and allows graceful recovery.</p>
 *
 * <p>The watchdog does NOT automatically interrupt or cancel operations — it
 * only detects and reports timeout conditions. Callers must check
 * {@link #hasTimedOut()} periodically during long operations and take
 * appropriate action.</p>
 */
@Slf4j
public class SyncWatchdog
{
    /** Maximum time a sync operation is allowed to run (milliseconds). */
    private static final long SYNC_TIMEOUT_MS = 30_000; // 30 seconds

    private final long startTimeMs;

    public SyncWatchdog()
    {
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * @return {@code true} if the watchdog has detected a timeout
     */
    public boolean hasTimedOut()
    {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        return elapsedMs > SYNC_TIMEOUT_MS;
    }

    /**
     * @return elapsed time since sync start (milliseconds)
     */
    public long getElapsedMs()
    {
        return System.currentTimeMillis() - startTimeMs;
    }

    /**
     * @return remaining time before timeout (milliseconds), or 0 if already timed out
     */
    public long getRemainingMs()
    {
        long remaining = SYNC_TIMEOUT_MS - getElapsedMs();
        return Math.max(0, remaining);
    }

    /**
     * Logs a warning if elapsed time is significant (> 15 seconds).
     * Useful for detecting slow syncs before they timeout.
     */
    public void logWarningIfSlow()
    {
        long elapsed = getElapsedMs();
        if (elapsed > 15_000) // 15 seconds
        {
            log.warn("[watchdog] Sync operation running slowly: {}ms elapsed", elapsed);
        }
    }
}
