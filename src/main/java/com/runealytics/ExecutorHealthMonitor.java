package com.runealytics;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors executor health to detect starvation (tasks not executing in timely fashion).
 *
 * <p>Periodically submits a probe task to the executor and measures how long it takes
 * to execute. If probe execution is significantly delayed, logs a warning indicating
 * the executor may be starved or overloaded.</p>
 *
 * <p>Separate monitoring instances are used for loot-sync executor and other executors
 * to identify which executor is experiencing problems.</p>
 */
@Slf4j
public class ExecutorHealthMonitor
{
    private static final long PROBE_INTERVAL_MS = 60_000; // Check every 60 seconds
    private static final long PROBE_TIMEOUT_MS = 5_000;   // Task should run within 5 seconds
    private static final long STARVATION_THRESHOLD_MS = 10_000; // Warn if > 10 seconds delay

    private final ScheduledExecutorService executor;
    private final String executorName;
    private volatile ScheduledFuture<?> healthCheckTask;
    private final AtomicLong lastProbeExecutionTimeMs = new AtomicLong(System.currentTimeMillis());

    public ExecutorHealthMonitor(ScheduledExecutorService executor, String executorName)
    {
        this.executor = executor;
        this.executorName = executorName;
    }

    /**
     * Starts periodic health checks. Should be called at plugin startup.
     */
    public void start()
    {
        if (executor == null || executor.isShutdown()) return;

        healthCheckTask = executor.scheduleAtFixedRate(
            this::runHealthProbe,
            PROBE_INTERVAL_MS,
            PROBE_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        log.debug("[health] Started health monitoring for executor: {}", executorName);
    }

    /**
     * Stops health checks. Should be called at plugin shutdown.
     */
    public void stop()
    {
        if (healthCheckTask != null)
        {
            healthCheckTask.cancel(false);
            healthCheckTask = null;
            log.debug("[health] Stopped health monitoring for executor: {}", executorName);
        }
    }

    /**
     * Runs a lightweight probe task and measures execution delay.
     */
    private void runHealthProbe()
    {
        long probeSubmitTimeMs = System.currentTimeMillis();

        try
        {
            executor.execute(() ->
            {
                long probeExecuteTimeMs = System.currentTimeMillis();
                long delayMs = probeExecuteTimeMs - probeSubmitTimeMs;

                if (delayMs > STARVATION_THRESHOLD_MS)
                {
                    log.warn("[health] {} executor is starved: probe delayed {}ms (> {}ms threshold)",
                        executorName, delayMs, STARVATION_THRESHOLD_MS);
                }
                else if (delayMs > PROBE_TIMEOUT_MS / 2)
                {
                    log.debug("[health] {} executor is slow: probe delayed {}ms",
                        executorName, delayMs);
                }

                lastProbeExecutionTimeMs.set(probeExecuteTimeMs);
            });
        }
        catch (Exception e)
        {
            log.warn("[health] Failed to submit health probe to {}: {}",
                executorName, e.getMessage());
        }
    }

    /**
     * @return the timestamp of the last successful probe execution
     */
    public long getLastProbeExecutionTimeMs()
    {
        return lastProbeExecutionTimeMs.get();
    }

    /**
     * @return true if no probe has executed for > 30 seconds (executor likely dead)
     */
    public boolean isLikelyDead()
    {
        long lastProbeMs = getLastProbeExecutionTimeMs();
        long timeSinceLastProbeMs = System.currentTimeMillis() - lastProbeMs;
        return timeSinceLastProbeMs > 30_000;
    }
}
