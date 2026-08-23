package com.runealytics;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches Grand Exchange prices with time-to-live (TTL) expiration.
 *
 * <p>GE prices change frequently (every few seconds), so cached prices are
 * invalidated after a configurable TTL. This reduces API calls to the GE
 * service while remaining reasonably fresh.</p>
 *
 * <p>Thread-safe via ConcurrentHashMap. Expired entries are lazily purged
 * on access, not by a background thread.</p>
 */
@Slf4j
public class GePriceCache
{
	/** Default TTL: 5 minutes for GE prices */
	private static final long DEFAULT_TTL_MS = 5 * 60 * 1000;

	private static class CacheEntry
	{
		final int price;
		final long expiresAtMs;

		CacheEntry(int price, long expiresAtMs)
		{
			this.price = price;
			this.expiresAtMs = expiresAtMs;
		}

		boolean isExpired()
		{
			return System.currentTimeMillis() > expiresAtMs;
		}
	}

	private final ConcurrentHashMap<Integer, CacheEntry> cache = new ConcurrentHashMap<>();
	private final long ttlMs;
	private volatile long cacheHits = 0;
	private volatile long cacheMisses = 0;

	public GePriceCache()
	{
		this(DEFAULT_TTL_MS);
	}

	public GePriceCache(long ttlMs)
	{
		this.ttlMs = ttlMs;
	}

	/**
	 * Attempts to retrieve a cached GE price for the given item ID.
	 *
	 * @param itemId the item ID to look up
	 * @return cached price if available and not expired, or -1 if cache miss
	 */
	public int getPrice(int itemId)
	{
		CacheEntry entry = cache.get(itemId);
		if (entry != null && !entry.isExpired())
		{
			cacheHits++;
			log.debug("[cache] GE price cache HIT: id={} price={}", itemId, entry.price);
			return entry.price;
		}

		// Remove expired entry
		if (entry != null)
		{
			cache.remove(itemId);
		}

		cacheMisses++;
		return -1;
	}

	/**
	 * Stores a GE price in the cache for the given item ID.
	 *
	 * @param itemId the item ID
	 * @param price the Grand Exchange price
	 */
	public void putPrice(int itemId, int price)
	{
		if (price < 0) return;

		long expiresAtMs = System.currentTimeMillis() + ttlMs;
		cache.put(itemId, new CacheEntry(price, expiresAtMs));
		log.debug("[cache] GE price cached: id={} price={} ttl={}ms", itemId, price, ttlMs);
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
		log.debug("[cache] GE price cache cleared ({} entries)", sizeBefore);
	}

	/**
	 * Purges all expired entries. Useful for periodic cleanup if cache
	 * is not accessed frequently.
	 *
	 * @return number of entries purged
	 */
	public int purgeExpired()
	{
		int purged = 0;
		for (Integer itemId : cache.keySet())
		{
			CacheEntry entry = cache.get(itemId);
			if (entry != null && entry.isExpired())
			{
				if (cache.remove(itemId) != null)
				{
					purged++;
				}
			}
		}
		if (purged > 0)
		{
			log.debug("[cache] GE price cache purged {} expired entries", purged);
		}
		return purged;
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
	 * @return current cache size (number of cached entries, including expired ones)
	 */
	public int size()
	{
		return cache.size();
	}

	/**
	 * @return TTL in milliseconds
	 */
	public long getTtlMs()
	{
		return ttlMs;
	}
}
