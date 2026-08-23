package com.runealytics;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches static item metadata (name, ID, alch value, etc.) that rarely changes.
 *
 * <p>Item composition data from ItemManager is fetched on the client thread and
 * cached here to avoid repeated client-thread calls for the same item ID.
 * This is particularly useful during merge operations where many items may be
 * processed but few are unique.</p>
 *
 * <p>Cache is never invalidated during a session since item metadata is static
 * and doesn't change at runtime. If RuneLite patches items, a plugin reload
 * clears all caches automatically.</p>
 */
@Slf4j
public class ItemMetadataCache
{
	public static class ItemMetadata
	{
		public final int itemId;
		public final String name;
		public final int highAlch;
		public final long gecPrice;

		public ItemMetadata(int itemId, String name, int highAlch, long gecPrice)
		{
			this.itemId = itemId;
			this.name = name;
			this.highAlch = highAlch;
			this.gecPrice = gecPrice;
		}
	}

	private final ConcurrentHashMap<Integer, ItemMetadata> cache = new ConcurrentHashMap<>();
	private volatile long cacheHits = 0;
	private volatile long cacheMisses = 0;

	/**
	 * Attempts to retrieve cached metadata for the given item ID.
	 *
	 * @param itemId the item ID to look up
	 * @return cached metadata if available, or null if cache miss
	 */
	public ItemMetadata get(int itemId)
	{
		ItemMetadata metadata = cache.get(itemId);
		if (metadata != null)
		{
			cacheHits++;
			log.debug("[cache] Item metadata cache HIT: id={} name={}", itemId, metadata.name);
		}
		else
		{
			cacheMisses++;
		}
		return metadata;
	}

	/**
	 * Stores metadata in the cache for the given item ID.
	 *
	 * @param metadata the item metadata to cache
	 */
	public void put(ItemMetadata metadata)
	{
		if (metadata == null) return;
		cache.put(metadata.itemId, metadata);
		log.debug("[cache] Item metadata cached: id={} name={}", metadata.itemId, metadata.name);
	}

	/**
	 * Clears the entire cache. Called on plugin reload.
	 */
	public void clear()
	{
		int sizeBefore = cache.size();
		cache.clear();
		cacheHits = 0;
		cacheMisses = 0;
		log.debug("[cache] Item metadata cache cleared ({} entries)", sizeBefore);
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
	 * @return current cache size (number of cached items)
	 */
	public int size()
	{
		return cache.size();
	}
}
