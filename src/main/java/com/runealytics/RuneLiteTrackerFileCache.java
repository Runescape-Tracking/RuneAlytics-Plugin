package com.runealytics;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches RuneLite loot tracker file reads using lastModifiedTime + file size as
 * a fingerprint. When the file hasn't changed (same lastModifiedTime + size),
 * the cached contents are returned without re-reading from disk.
 *
 * <p>This avoids repeated disk I/O on rapid consecutive syncs since the file
 * rarely changes between game ticks.</p>
 */
@Slf4j
public class RuneLiteTrackerFileCache
{
	private static class CacheEntry
	{
		final long lastModified;
		final long fileSize;
		final Map<String, ?> data;

		CacheEntry(long lastModified, long fileSize, Map<String, ?> data)
		{
			this.lastModified = lastModified;
			this.fileSize = fileSize;
			this.data = data;
		}
	}

	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

	/**
	 * Attempts to retrieve cached data for the given file.
	 *
	 * @param file the file to check
	 * @return cached data if file hasn't changed, or null if cache miss or file doesn't exist
	 */
	public Map<String, ?> getCachedIfUnchanged(File file)
	{
		if (file == null || !file.exists()) return null;

		long lastModified = file.lastModified();
		long fileSize = file.length();
		String filePath = file.getAbsolutePath();

		CacheEntry entry = cache.get(filePath);
		if (entry != null && entry.lastModified == lastModified && entry.fileSize == fileSize)
		{
			log.debug("[cache] RuneLite tracker cache HIT: {} (lastMod={} size={})",
					filePath, lastModified, fileSize);
			return entry.data;
		}

		return null;
	}

	/**
	 * Stores data in the cache for the given file.
	 *
	 * @param file the file that was read
	 * @param data the parsed data to cache
	 */
	public void put(File file, Map<String, ?> data)
	{
		if (file == null || !file.exists()) return;

		long lastModified = file.lastModified();
		long fileSize = file.length();
		String filePath = file.getAbsolutePath();

		cache.put(filePath, new CacheEntry(lastModified, fileSize, data));
		log.debug("[cache] RuneLite tracker cached: {} (lastMod={} size={})",
				filePath, lastModified, fileSize);
	}

	/**
	 * Clears the entire cache (rarely needed, but useful for testing).
	 */
	public void clear()
	{
		cache.clear();
		log.debug("[cache] RuneLite tracker cache cleared");
	}
}
