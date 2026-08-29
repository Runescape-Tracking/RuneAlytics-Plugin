package com.runealytics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * TTL lookup contract for {@link GePriceCache}. A 1ms TTL plus a short sleep
 * covers expiry without depending on a clock hook.
 */
public class GePriceCacheTest
{
    @Test
    public void getPrice_missIsNegativeOne()
    {
        GePriceCache cache = new GePriceCache();
        assertEquals(-1, cache.getPrice(4151));
        assertEquals(1, cache.getMisses());
    }

    @Test
    public void putAndGet_returnsCachedPrice()
    {
        GePriceCache cache = new GePriceCache();
        cache.putPrice(4151, 1_200_000);
        assertEquals(1_200_000, cache.getPrice(4151));
        assertEquals(1, cache.getHits());
        assertEquals(1, cache.size());
    }

    @Test
    public void putPrice_rejectsNegative()
    {
        GePriceCache cache = new GePriceCache();
        cache.putPrice(4151, -1);
        assertEquals(0, cache.size());
        assertEquals(-1, cache.getPrice(4151));
    }

    @Test
    public void expiredEntry_isTreatedAsMiss() throws InterruptedException
    {
        GePriceCache cache = new GePriceCache(1L);
        cache.putPrice(4151, 50);
        Thread.sleep(5L);
        assertEquals(-1, cache.getPrice(4151));
        assertEquals(0, cache.size());
        assertEquals(1, cache.getMisses());
    }

    @Test
    public void clear_resetsSizeAndCounters()
    {
        GePriceCache cache = new GePriceCache();
        cache.putPrice(4151, 10);
        cache.getPrice(4151);
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.getHits());
        assertEquals(0, cache.getMisses());
        assertEquals(0.0, cache.getHitRate(), 0.0);
    }
}
