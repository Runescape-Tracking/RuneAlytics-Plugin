package com.runealytics;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches the results of loot tracker merges, invalidating when RuneLite
 * tracker files are detected to have changed (via lastModifiedTime).
 *
 * <p>When syncing, we merge data from three sources (local, RuneLite, website).
 * If the RuneLite tracker file hasn't been modified since the last merge,
 * we can skip the expensive file read and merge operations and return the
 * cached result instead.</p>
 *
 * <p>Cache is invalidated when:</p>
 * <ul>
 *   <li>RuneLite tracker files are modified (detected via lastModifiedTime)</li>
 *   <li>Local storage revision changes (new loot added)</li>
 *   <li>Time-to-live (60 seconds) expires</li>
 * </ul>
 */
@Slf4j
public class TrackerResultsCache
{
	/** Cache TTL: 60 seconds. Merge results older than this are discarded. */
	private static final long CACHE_TTL_MS = 60 * 1000;

	private static class CacheEntry
	{
		final long runeLiteFileModified;
		final long localStorageRevision;
		final LootSyncMergeService.MergeResult result;
		final long createdAtMs;

		CacheEntry(long runeLiteFileModified, long localStorageRevision,
				   LootSyncMergeService.MergeResult result)
		{
			this.runeLiteFileModified = runeLiteFileModified;
			this.localStorageRevision = localStorageRevision;
			this.result = result;
			this.createdAtMs = System.currentTimeMillis();
		}

		boolean isExpired()
		{
			return System.currentTimeMillis() - createdAtMs > CACHE_TTL_MS;
		}

		boolean isStale(long newRuneLiteFileModified, long newLocalStorageRevision)
		{
			return runeLiteFileModified != newRuneLiteFileModified
					|| localStorageRevision != newLocalStorageRevision;
		}
	}

	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
	private volatile long cacheHits = 0;
	private volatile long cacheMisses = 0;

	/**
	 * Attempts to retrieve a cached merge result for the given account.
	 *
	 * @param accountKey normalized account name
	 * @param runeLiteFileModified timestamp of RuneLite tracker file last modification
	 * @param localStorageRevision local storage revision number
	 * @return cached merge result if available, valid, and not expired; null otherwise
	 */
	public LootSyncMergeService.MergeResult getIfValid(String accountKey,
													   long runeLiteFileModified,
													   long localStorageRevision)
	{
		CacheEntry entry = cache.get(accountKey);
		if (entry == null)
		{
			cacheMisses++;
			return null;
		}

		if (entry.isExpired())
		{
			cache.remove(accountKey);
			cacheMisses++;
			log.debug("[cache] Merge result cache MISS (expired): account={}", accountKey);
			return null;
		}

		if (entry.isStale(runeLiteFileModified, localStorageRevision))
		{
			cache.remove(accountKey);
			cacheMisses++;
			log.debug("[cache] Merge result cache MISS (stale): account={}", accountKey);
			return null;
		}

		cacheHits++;
		log.debug("[cache] Merge result cache HIT: account={}", accountKey);
		return entry.result;
	}

	/**
	 * Stores a merge result in the cache for the given account.
	 *
	 * @param accountKey normalized account name
	 * @param runeLiteFileModified timestamp of RuneLite tracker file last modification
	 * @param localStorageRevision local storage revision number
	 * @param result the merge result to cache
	 */
	public void put(String accountKey,
					long runeLiteFileModified,
					long localStorageRevision,
					LootSyncMergeService.MergeResult result)
	{
		if (accountKey == null || result == null) return;

		cache.put(accountKey, new CacheEntry(runeLiteFileModified, localStorageRevision, result));
		log.debug("[cache] Merge result cached: account={} rlMod={} revision={}",
				accountKey, runeLiteFileModified, localStorageRevision);
	}

	/**
	 * Invalidates the cached merge result for the given account.
	 * Called when storage is modified or account switches.
	 *
	 * @param accountKey the account to invalidate
	 */
	public void invalidate(String accountKey)
	{
		if (cache.remove(accountKey) != null)
		{
			log.debug("[cache] Merge result cache invalidated: account={}", accountKey);
		}
	}

	/**
	 * Clears the entire cache.
	 */
	public void clear()
	{
		int sizeBefore = cache.size();
		cache.clear();
		cacheHits = 0;
		cacheMisses = 0;
		log.debug("[cache] Merge result cache cleared ({} entries)", sizeBefore);
	}

	/**
	 * @return cache hit rate as percentage (0-100), or 0 if no queries yet
	 */
	public double getHitRate()
	{
		long total = cacheHits + cacheMisses;
		return total > 0 ? (100.0 * cacheHits / total) : 0.0;
	}

	/**
	 * @return total number of cache hits
	 */
	public long getHits()
	{
		return cacheHits;
	}

	/**
	 * @return total number of cache misses
	 */
	public long getMisses()
	{
		return cacheMisses;
	}

	/**
	 * @return current cache size
	 */
	public int size()
	{
		return cache.size();
	}
}
