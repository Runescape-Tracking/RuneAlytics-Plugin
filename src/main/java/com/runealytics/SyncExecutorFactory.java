package com.runealytics;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;

/**
 * Factory for creating a dedicated executor for loot sync operations.
 *
 * <p>The sync executor is separate from RuneLite's shared executor pool,
 * ensuring that long-running sync I/O (file reads, network requests) cannot
 * starve other plugin tasks. Uses a single background thread to serialize
 * syncs (they already serialize via SyncCoordinator, so a single-thread pool
 * is sufficient and more efficient).</p>
 *
 * <p>Thread name is "RuneAlytics-Sync" for easy identification in logs and
 * thread dumps.</p>
 */
@Slf4j
public class SyncExecutorFactory
{
    /**
     * Creates a dedicated single-thread scheduled executor for loot syncs.
     *
     * @return a ScheduledExecutorService with one worker thread
     */
    public static ScheduledExecutorService createSyncExecutor()
    {
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r);
            t.setName("RuneAlytics-Sync");
            t.setDaemon(true);
            return t;
        };

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1, threadFactory);
        log.debug("[sync-executor] Created dedicated sync executor");
        return executor;
    }

    /**
     * Shuts down the given sync executor gracefully.
     * Waits up to 5 seconds for pending tasks to complete.
     *
     * @param executor the executor to shut down
     */
    public static void shutdown(ScheduledExecutorService executor)
    {
        if (executor == null) return;

        executor.shutdown();
        try
        {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS))
            {
                log.debug("[sync-executor] Sync executor did not terminate in 5s, forcing shutdown");
                executor.shutdownNow();
            }
            log.debug("[sync-executor] Sync executor shut down");
        }
        catch (InterruptedException e)
        {
            log.debug("[sync-executor] Interrupted while shutting down sync executor: {}", e.getMessage());
            executor.shutdownNow();
        }
    }
}
