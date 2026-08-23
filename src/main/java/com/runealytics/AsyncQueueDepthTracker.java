package com.runealytics;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the depth (pending task count) of thread pool executor queues.
 *
 * <p>Monitors task submission rates and queue depth to detect executor
 * overload or thread starvation. High queue depth indicates tasks are
 * being submitted faster than they can execute.</p>
 *
 * <p>Only works with ThreadPoolExecutor (not ScheduledExecutorService directly),
 * so may not be available for all executor types.</p>
 */
@Slf4j
public class AsyncQueueDepthTracker
{
	private static final long QUEUE_DEPTH_CHECK_INTERVAL_MS = 30_000; // Check every 30 seconds
	private static final int QUEUE_DEPTH_WARNING_THRESHOLD = 50; // Warn if > 50 pending

	private final ThreadPoolExecutor executor;
	private final String executorName;
	private volatile long lastCheckMs = System.currentTimeMillis();
	private final AtomicLong peakQueueDepth = new AtomicLong(0);
	private final AtomicLong totalTasksSubmitted = new AtomicLong(0);
	private final AtomicLong totalTasksCompleted = new AtomicLong(0);

	public AsyncQueueDepthTracker(ThreadPoolExecutor executor, String executorName)
	{
		this.executor = executor;
		this.executorName = executorName;
	}

	/**
	 * Records a task submission. Call this after submitting a task to the executor.
	 */
	public void recordSubmission()
	{
		totalTasksSubmitted.incrementAndGet();
		checkQueueDepth();
	}

	/**
	 * Records task completion. Call this in task finally blocks.
	 */
	public void recordCompletion()
	{
		totalTasksCompleted.incrementAndGet();
	}

	/**
	 * Checks current queue depth and logs warning if threshold exceeded.
	 */
	private void checkQueueDepth()
	{
		if (executor == null || executor.isShutdown()) return;

		int queueSize = executor.getQueue().size();
		long now = System.currentTimeMillis();

		// Update peak
		long peak = peakQueueDepth.get();
		while (queueSize > peak && !peakQueueDepth.compareAndSet(peak, queueSize))
		{
			peak = peakQueueDepth.get();
		}

		// Check interval - only log periodically
		if (now - lastCheckMs > QUEUE_DEPTH_CHECK_INTERVAL_MS)
		{
			lastCheckMs = now;

			long submitted = totalTasksSubmitted.get();
			long completed = totalTasksCompleted.get();
			long pending = submitted - completed;

			log.debug("[queue] {} executor depth: queue={} active={} peak={} submitted={} completed={} pending={}",
				executorName, queueSize, executor.getActiveCount(), peakQueueDepth.get(),
				submitted, completed, pending);

			if (queueSize > QUEUE_DEPTH_WARNING_THRESHOLD)
			{
				log.warn("[queue] {} executor queue is building up: {} pending tasks " +
					"(> {} threshold). Check for executor starvation or slow tasks.",
					executorName, queueSize, QUEUE_DEPTH_WARNING_THRESHOLD);
			}
		}
	}

	/**
	 * @return current queue depth (number of pending tasks)
	 */
	public int getQueueDepth()
	{
		return executor != null ? executor.getQueue().size() : 0;
	}

	/**
	 * @return current number of active (executing) threads
	 */
	public int getActiveThreadCount()
	{
		return executor != null ? executor.getActiveCount() : 0;
	}

	/**
	 * @return peak queue depth observed since tracking started
	 */
	public long getPeakQueueDepth()
	{
		return peakQueueDepth.get();
	}

	/**
	 * @return total tasks submitted to executor
	 */
	public long getTotalSubmitted()
	{
		return totalTasksSubmitted.get();
	}

	/**
	 * @return total tasks completed by executor
	 */
	public long getTotalCompleted()
	{
		return totalTasksCompleted.get();
	}

	/**
	 * @return pending tasks (submitted - completed)
	 */
	public long getPendingTasks()
	{
		return totalTasksSubmitted.get() - totalTasksCompleted.get();
	}

	/**
	 * Resets all statistics. Useful for testing or after diagnostic output.
	 */
	public void reset()
	{
		peakQueueDepth.set(0);
		totalTasksSubmitted.set(0);
		totalTasksCompleted.set(0);
		lastCheckMs = System.currentTimeMillis();
		log.debug("[queue] {} executor tracking reset", executorName);
	}
}
