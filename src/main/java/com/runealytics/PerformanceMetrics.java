package com.runealytics;

import lombok.extern.slf4j.Slf4j;

/**
 * Lightweight performance metrics collection for sync pipeline.
 * Tracks timing of each phase and logs summary on completion.
 */
@Slf4j
public class PerformanceMetrics
{
	private long startMs;
	private long legacySyncEndMs;
	private long mergeEndMs;
	private long uiRefreshEndMs;

	private int recordsProcessed;
	private int uniqueItems;

	public PerformanceMetrics()
	{
		this.startMs = System.currentTimeMillis();
	}

	public void markLegacySyncComplete()
	{
		this.legacySyncEndMs = System.currentTimeMillis();
	}

	public void markMergeComplete(int records, int items)
	{
		this.mergeEndMs = System.currentTimeMillis();
		this.recordsProcessed = records;
		this.uniqueItems = items;
	}

	public void markUiRefreshComplete()
	{
		this.uiRefreshEndMs = System.currentTimeMillis();
	}

	public long getTotalMs()
	{
		return System.currentTimeMillis() - startMs;
	}

	public long getLegacySyncDurationMs()
	{
		return legacySyncEndMs > 0 ? legacySyncEndMs - startMs : 0;
	}

	public long getMergeDurationMs()
	{
		return mergeEndMs > legacySyncEndMs && legacySyncEndMs > 0
			? mergeEndMs - legacySyncEndMs : 0;
	}

	public long getUiRefreshDurationMs()
	{
		return uiRefreshEndMs > mergeEndMs && mergeEndMs > 0
			? uiRefreshEndMs - mergeEndMs : 0;
	}

	public void logSummary(String accountKey, String syncReason)
	{
		long total = getTotalMs();
		long legacy = getLegacySyncDurationMs();
		long merge = getMergeDurationMs();
		long ui = getUiRefreshDurationMs();

		log.debug(
			"[PERF] Sync complete: account='{}' reason='{}' total={}ms " +
			"(legacy={}ms merge={}ms ui={}ms) records={} items={}",
			accountKey, syncReason, total, legacy, merge, ui,
			recordsProcessed, uniqueItems
		);
	}
}
