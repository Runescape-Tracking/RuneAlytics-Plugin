package com.runealytics;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Hit / miss / invalidation rules for the three-source merge result cache.
 * TTL expiry is 60s and is not waited out here; stale keys and explicit
 * invalidate cover the paths that actually fire during a session.
 */
public class TrackerResultsCacheTest
{
    private TrackerResultsCache cache;
    private LootSyncMergeService.MergeResult result;

    @Before
    public void setUp()
    {
        cache = new TrackerResultsCache();
        result = LootSyncMergeService.MergeResult.success("zezima", 2, 4, true, false, null);
    }

    @Test
    public void getIfValid_missThenHit()
    {
        assertNull(cache.getIfValid("zezima", 100L, 1L));
        cache.put("zezima", 100L, 1L, result);
        assertSame(result, cache.getIfValid("zezima", 100L, 1L));
        assertEquals(1, cache.getHits());
        assertEquals(1, cache.getMisses());
        assertEquals(50.0, cache.getHitRate(), 0.01);
    }

    @Test
    public void getIfValid_staleOnRevisionOrFileMtime()
    {
        cache.put("zezima", 100L, 1L, result);
        assertNull(cache.getIfValid("zezima", 100L, 2L));
        cache.put("zezima", 100L, 1L, result);
        assertNull(cache.getIfValid("zezima", 200L, 1L));
        assertEquals(2, cache.getMisses());
        assertEquals(0, cache.size());
    }

    @Test
    public void invalidate_dropsOnlyThatAccount()
    {
        cache.put("zezima", 100L, 1L, result);
        cache.put("other", 100L, 1L,
                LootSyncMergeService.MergeResult.success("other", 1, 1, false, true, "x"));
        cache.invalidate("zezima");
        assertNull(cache.getIfValid("zezima", 100L, 1L));
        assertEquals(1, cache.size());
    }

    @Test
    public void put_ignoresNullKeyOrResult()
    {
        cache.put(null, 100L, 1L, result);
        cache.put("zezima", 100L, 1L, null);
        assertEquals(0, cache.size());
    }

    @Test
    public void clear_resetsEntriesAndCounters()
    {
        cache.put("zezima", 100L, 1L, result);
        cache.getIfValid("zezima", 100L, 1L);
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.getHits());
        assertEquals(0, cache.getMisses());
        assertEquals(0.0, cache.getHitRate(), 0.0);
    }
}
