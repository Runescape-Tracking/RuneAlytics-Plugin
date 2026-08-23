package com.runealytics;

/**
 * Enumeration of log categories for categorized debug logging.
 *
 * <p>Allows filtering logs by component and concern (sync, cache, executor, etc.)
 * for easier debugging and performance analysis. Each log message includes its
 * category in the debug output, e.g., {@code [sync:merge] Merging 5 sources}</p>
 */
public enum LogCategory
{
	/** Loot sync pipeline operations */
	SYNC("sync"),
	/** Sync merge operations (three-source merge) */
	SYNC_MERGE("sync:merge"),
	/** Sync coordination and coalescing */
	SYNC_COORD("sync:coord"),
	/** Executor health and starvation */
	EXECUTOR("executor"),
	/** Cache hits, misses, and operations */
	CACHE("cache"),
	/** File I/O operations */
	FILE_IO("file-io"),
	/** Network I/O and API calls */
	NETWORK("network"),
	/** ItemManager queries and client thread operations */
	ITEM_MANAGER("item-mgr"),
	/** Memory pressure and GC events */
	MEMORY("memory"),
	/** Performance metrics and timing */
	PERF("perf"),
	/** Bank sync operations */
	BANK_SYNC("bank-sync"),
	/** Generic plugin events */
	PLUGIN("plugin");

	public final String label;

	LogCategory(String label)
	{
		this.label = label;
	}

	/**
	 * Formats a log message with this category prefix.
	 * Supports standard String.format() style formatting with %s, %d, %f, etc.
	 * Example: format("Memory: %d%% (%s)", 85, "HIGH")
	 * becomes "[perf] Memory: 85% (HIGH)"
	 *
	 * @param format the format string (supports %s, %d, %f, etc.)
	 * @param args variable arguments to substitute into format string
	 * @return formatted message with category prefix
	 */
	public String format(String format, Object... args)
	{
		String formattedMessage = String.format(format, args);
		return String.format("[%s] %s", label, formattedMessage);
	}
}
