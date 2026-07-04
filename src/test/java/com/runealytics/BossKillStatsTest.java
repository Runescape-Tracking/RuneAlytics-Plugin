package com.runealytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Edge-case coverage for {@link BossKillStats}: kill accumulation, prestige
 * reset, drop aggregation, and the pet-first sort order.
 */
public class BossKillStatsTest
{
    private static LootStorageData.DropRecord drop(int id, String name, int qty, long value)
    {
        LootStorageData.DropRecord d = new LootStorageData.DropRecord();
        d.setItemId(id);
        d.setItemName(name);
        d.setQuantity(qty);
        d.setTotalValue(value);
        return d;
    }

    private static LootStorageData.KillRecord kill(long timestamp, LootStorageData.DropRecord... drops)
    {
        LootStorageData.KillRecord k = new LootStorageData.KillRecord();
        k.setTimestamp(timestamp);
        k.setDrops(new ArrayList<>(Arrays.asList(drops)));
        return k;
    }

    @Test
    public void newStats_haveZeroedCounters()
    {
        BossKillStats stats = new BossKillStats("Zulrah", 2042);
        assertEquals(0, stats.getKillCount());
        assertEquals(0, stats.getPrestige());
        assertEquals(0L, stats.getLastKillTimestamp());
    }

    @Test
    public void addKill_null_isIgnored()
    {
        BossKillStats stats = new BossKillStats("Zulrah", 2042);
        stats.addKill(null);
        assertEquals(0, stats.getKillCount());
    }

    @Test
    public void addKill_accumulatesCountValueAndHighestDrop()
    {
        BossKillStats stats = new BossKillStats("Vorkath", 8061);
        stats.addKill(kill(100L, drop(1, "a", 1, 500L), drop(2, "b", 1, 2_000L)));

        assertEquals(1, stats.getKillCount());
        assertEquals(2_500L, stats.getTotalLootValue());
        assertEquals(2_000L, stats.getHighestDrop());
    }

    @Test
    public void getLastKillTimestamp_returnsMostRecent()
    {
        BossKillStats stats = new BossKillStats("Vorkath", 8061);
        stats.addKill(kill(100L));
        stats.addKill(kill(250L));
        assertEquals(250L, stats.getLastKillTimestamp());
    }

    @Test
    public void prestige_resetsCountersAndHistoryButIncrementsPrestige()
    {
        BossKillStats stats = new BossKillStats("Zulrah", 2042);
        stats.addKill(kill(100L, drop(1, "a", 1, 500L)));

        stats.prestige();

        assertEquals(1, stats.getPrestige());
        assertEquals(0, stats.getKillCount());
        assertEquals(0L, stats.getTotalLootValue());
        assertEquals(0L, stats.getHighestDrop());
        assertTrue(stats.getAggregatedDrops().isEmpty());
    }

    @Test
    public void getAggregatedDrops_mergesSameItemAcrossKills()
    {
        BossKillStats stats = new BossKillStats("Zulrah", 2042);
        stats.addKill(kill(1L, drop(1, "Scale", 100, 1_000L)));
        stats.addKill(kill(2L, drop(1, "Scale", 50, 500L)));

        List<BossKillStats.AggregatedDrop> drops = stats.getAggregatedDrops();
        assertEquals(1, drops.size());
        assertEquals(150, drops.get(0).getTotalQuantity());
        assertEquals(1_500L, drops.get(0).getTotalValue());
        assertEquals(2, drops.get(0).getDropCount());
    }

    @Test
    public void getAggregatedDrops_marksPetWhenAnyDropIsPet()
    {
        LootStorageData.DropRecord pet = drop(13_247, "Pet snakeling", 1, 0L);
        pet.setPet(true);

        BossKillStats stats = new BossKillStats("Zulrah", 2042);
        stats.addKill(kill(1L, pet));

        assertTrue(stats.getAggregatedDrops().get(0).isPet());
    }

    @Test
    public void getAggregatedDrops_emptyHistory_fallsBackToPreloaded()
    {
        BossKillStats stats = new BossKillStats("Zulrah", 2042);
        BossKillStats.AggregatedDrop preloaded =
                new BossKillStats.AggregatedDrop(1, "Scale", 10, 100L, 1, 10L, 5L);
        stats.setPreloadedDrops(Collections.singletonList(preloaded));

        List<BossKillStats.AggregatedDrop> drops = stats.getAggregatedDrops();
        assertEquals(1, drops.size());
        assertEquals("Scale", drops.get(0).getItemName());
    }

    @Test
    public void getAggregatedDropsSorted_placesPetsFirstThenByValueDesc()
    {
        LootStorageData.DropRecord cheapPet = drop(13_247, "Pet", 1, 0L);
        cheapPet.setPet(true);
        LootStorageData.DropRecord bigItem = drop(20_997, "Twisted bow", 1, 1_000_000L);
        LootStorageData.DropRecord midItem = drop(11_235, "Dark bow", 1, 100_000L);

        BossKillStats stats = new BossKillStats("boss", 1);
        stats.addKill(kill(1L, bigItem, cheapPet, midItem));

        List<BossKillStats.AggregatedDrop> sorted = stats.getAggregatedDropsSorted();
        assertTrue(sorted.get(0).isPet());
        assertEquals(1_000_000L, sorted.get(1).getTotalValue());
        assertEquals(100_000L, sorted.get(2).getTotalValue());
        assertFalse(sorted.get(1).isPet());
    }
}
