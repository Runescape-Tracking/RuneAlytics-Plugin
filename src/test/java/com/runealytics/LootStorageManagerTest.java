package com.runealytics;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-memory logic coverage for loot persistence. The verified username is
 * mocked to {@code null}, which short-circuits all disk I/O in save/load while
 * leaving the aggregation, dedup and merge logic fully exercised.
 */
public class LootStorageManagerTest
{
    private LootStorageManager manager;

    @Before
    public void setUp()
    {
        RuneAlyticsState state = mock(RuneAlyticsState.class);
        when(state.getVerifiedUsername()).thenReturn(null);
        manager = new LootStorageManager(state, new Gson());
    }

    private static LootStorageData.DropRecord drop(int id, int qty, long total, int ge, int alch)
    {
        LootStorageData.DropRecord d = new LootStorageData.DropRecord();
        d.setItemId(id);
        d.setItemName("item" + id);
        d.setQuantity(qty);
        d.setTotalValue(total);
        d.setGePrice(ge);
        d.setHighAlch(alch);
        return d;
    }

    private static LootStorageData.KillRecord kill(long ts, int killNumber, boolean synced,
                                                   LootStorageData.DropRecord... drops)
    {
        LootStorageData.KillRecord k = new LootStorageData.KillRecord();
        k.setTimestamp(ts);
        k.setKillNumber(killNumber);
        k.setSyncedToServer(synced);
        k.setDrops(new ArrayList<>(Arrays.asList(drops)));
        return k;
    }

    private static LootStorageData.BossKillData boss(String name, int killCount, int prestige,
                                                     LootStorageData.KillRecord... kills)
    {
        LootStorageData.BossKillData b = new LootStorageData.BossKillData();
        b.setNpcName(name);
        b.setKillCount(killCount);
        b.setPrestige(prestige);
        b.setKills(new ArrayList<>(Arrays.asList(kills)));
        return b;
    }

    // ── addKill ────────────────────────────────────────────────────────────────

    @Test
    public void addKill_aggregatesQuantitiesAndKillCount()
    {
        manager.addKill("Zulrah", 2042, 100, 1, 330, 0,
                Arrays.asList(drop(4151, 2, 100L, 50, 10)));
        manager.addKill("Zulrah", 2042, 100, 2, 330, 0,
                Arrays.asList(drop(4151, 3, 150L, 50, 10)));

        LootStorageData.BossKillData b = manager.getCurrentData().getBossKills().get("Zulrah");
        assertEquals(2, b.getKillCount());
        assertEquals(2, b.getKills().size());
        assertEquals(250L, b.getTotalLootValue());

        LootStorageData.AggregatedDrop agg = b.getAggregatedDrops().get(4151);
        assertEquals(5, agg.getTotalQuantity());
        assertEquals(2, agg.getDropCount());
        assertEquals(250L, agg.getTotalValue());
    }

    @Test
    public void addKill_refreshesZeroGePriceFromLaterDrop()
    {
        manager.addKill("Zulrah", 2042, 100, 1, 330, 0,
                Arrays.asList(drop(4151, 1, 0L, 0, 0)));
        manager.addKill("Zulrah", 2042, 100, 2, 330, 0,
                Arrays.asList(drop(4151, 1, 100L, 75, 40)));

        LootStorageData.AggregatedDrop agg =
                manager.getCurrentData().getBossKills().get("Zulrah").getAggregatedDrops().get(4151);
        assertEquals(75, agg.getGePrice());
        assertEquals(40, agg.getHighAlch());
    }

    @Test
    public void addKill_copiesDropsListDefensively()
    {
        List<LootStorageData.DropRecord> drops = new ArrayList<>();
        drops.add(drop(4151, 1, 100L, 50, 10));
        manager.addKill("Zulrah", 2042, 100, 1, 330, 0, drops);

        drops.clear(); // must not affect the stored kill record

        assertEquals(1, manager.getCurrentData().getBossKills()
                .get("Zulrah").getKills().get(0).getDrops().size());
    }

    // ── appendDropsToLastKill ────────────────────────────────────────────────

    @Test
    public void appendDropsToLastKill_mergesIntoLastKillAndMarksUnsynced()
    {
        manager.addKill("Zulrah", 2042, 100, 1, 330, 0,
                Arrays.asList(drop(4151, 1, 100L, 50, 10)));
        // mark synced so we can assert the append flips it back
        manager.getCurrentData().getBossKills().get("Zulrah").getKills().get(0).setSyncedToServer(true);

        manager.appendDropsToLastKill("Zulrah", Arrays.asList(drop(995, 50, 50L, 1, 0)));

        LootStorageData.KillRecord last =
                manager.getCurrentData().getBossKills().get("Zulrah").getKills().get(0);
        assertEquals(2, last.getDrops().size());
        assertFalse(last.isSyncedToServer());
        assertEquals(150L, manager.getCurrentData().getBossKills().get("Zulrah").getTotalLootValue());
    }

    @Test
    public void appendDropsToLastKill_noOpForNullEmptyOrMissingBoss()
    {
        // No currentData yet, nothing loaded — must not throw.
        manager.appendDropsToLastKill("Zulrah", Arrays.asList(drop(1, 1, 1L, 1, 1)));

        manager.addKill("Zulrah", 2042, 100, 1, 330, 0, Arrays.asList(drop(1, 1, 1L, 1, 1)));
        manager.appendDropsToLastKill("Zulrah", null);
        manager.appendDropsToLastKill("Zulrah", new ArrayList<>());
        manager.appendDropsToLastKill("Nonexistent", Arrays.asList(drop(1, 1, 1L, 1, 1)));

        assertEquals(1, manager.getCurrentData().getBossKills().get("Zulrah").getKills().get(0).getDrops().size());
    }

    // ── sync queries ─────────────────────────────────────────────────────────

    @Test
    public void unsyncedKills_reflectSyncState()
    {
        manager.addKill("Zulrah", 2042, 100, 1, 330, 0, Arrays.asList(drop(1, 1, 1L, 1, 1)));
        assertEquals(1, manager.getUnsyncedKills("Zulrah").size());
        assertTrue(manager.getAllUnsyncedKills().containsKey("Zulrah"));

        manager.markKillsSynced("Zulrah", 0L, Long.MAX_VALUE);
        assertTrue(manager.getUnsyncedKills("Zulrah").isEmpty());
        assertTrue(manager.getAllUnsyncedKills().isEmpty());
    }

    @Test
    public void markKillsSynced_onlyWithinRange()
    {
        LootStorageData data = manager.getCurrentData();
        data.getBossKills().put("Zulrah", boss("Zulrah", 2, 0,
                kill(1000L, 1, false, drop(1, 1, 1L, 1, 1)),
                kill(5000L, 2, false, drop(1, 1, 1L, 1, 1))));

        manager.markKillsSynced("Zulrah", 0L, 2000L);

        List<LootStorageData.KillRecord> unsynced = manager.getUnsyncedKills("Zulrah");
        assertEquals(1, unsynced.size());
        assertEquals(5000L, unsynced.get(0).getTimestamp());
    }

    @Test
    public void unsyncedKills_unknownBoss_isEmpty()
    {
        assertTrue(manager.getUnsyncedKills("Nobody").isEmpty());
    }

    // ── mergeServerData ──────────────────────────────────────────────────────

    @Test
    public void mergeServerData_skipsWhenClientHasEqualOrMoreKills()
    {
        LootStorageData data = manager.getCurrentData();
        data.getBossKills().put("Zulrah", boss("Zulrah", 5, 0));

        Map<String, LootStorageData.BossKillData> server = new HashMap<>();
        server.put("Zulrah", boss("Zulrah", 3, 0, kill(9999L, 3, false, drop(1, 1, 1L, 1, 1))));
        manager.mergeServerData(server);

        assertEquals(5, manager.getCurrentData().getBossKills().get("Zulrah").getKillCount());
        assertTrue(manager.getCurrentData().getBossKills().get("Zulrah").getKills().isEmpty());
    }

    @Test
    public void mergeServerData_addsNewBossWithRealData()
    {
        Map<String, LootStorageData.BossKillData> server = new HashMap<>();
        server.put("Vorkath", boss("Vorkath", 2, 1,
                kill(1000L, 1, false, drop(4151, 1, 100L, 50, 10))));
        manager.mergeServerData(server);

        LootStorageData.BossKillData merged = manager.getCurrentData().getBossKills().get("Vorkath");
        assertEquals(2, merged.getKillCount());
        assertEquals(1, merged.getPrestige());
        assertEquals(1, merged.getKills().size());
        assertTrue(merged.getKills().get(0).isSyncedToServer());
        assertEquals(100L, merged.getTotalLootValue());
    }

    @Test
    public void mergeServerData_skipsEmptyPlaceholderBoss()
    {
        Map<String, LootStorageData.BossKillData> server = new HashMap<>();
        server.put("Ghost", boss("Ghost", 0, 0)); // no kills, no drops, 0 KC
        manager.mergeServerData(server);

        assertNull(manager.getCurrentData().getBossKills().get("Ghost"));
    }

    @Test
    public void mergeServerData_dedupsByTimestampWithinOneSecond()
    {
        LootStorageData data = manager.getCurrentData();
        data.getBossKills().put("Zulrah", boss("Zulrah", 1, 0,
                kill(10_000L, 1, true, drop(1, 1, 1L, 1, 1))));

        Map<String, LootStorageData.BossKillData> server = new HashMap<>();
        // Server kill within 1000ms of the existing one → treated as duplicate.
        server.put("Zulrah", boss("Zulrah", 2, 0,
                kill(10_500L, 0, false, drop(1, 1, 1L, 1, 1))));
        manager.mergeServerData(server);

        // No new kill added, but kill count still bumped to server's higher value.
        assertEquals(1, manager.getCurrentData().getBossKills().get("Zulrah").getKills().size());
        assertEquals(2, manager.getCurrentData().getBossKills().get("Zulrah").getKillCount());
    }

    @Test
    public void mergeServerData_dedupsByPositiveKillNumber()
    {
        LootStorageData data = manager.getCurrentData();
        data.getBossKills().put("Zulrah", boss("Zulrah", 1, 0,
                kill(1000L, 7, true, drop(1, 1, 1L, 1, 1))));

        Map<String, LootStorageData.BossKillData> server = new HashMap<>();
        // Far-away timestamp but same positive kill number → duplicate.
        server.put("Zulrah", boss("Zulrah", 2, 0,
                kill(999_999L, 7, false, drop(1, 1, 1L, 1, 1))));
        manager.mergeServerData(server);

        assertEquals(1, manager.getCurrentData().getBossKills().get("Zulrah").getKills().size());
    }

    @Test
    public void mergeServerData_killCountOnlyUpdateTakesPrestigeMaxAndRecalcsValue()
    {
        LootStorageData data = manager.getCurrentData();
        data.getBossKills().put("Zulrah", boss("Zulrah", 1, 3,
                kill(500L, 1, true, drop(4151, 1, 100L, 50, 10))));

        Map<String, LootStorageData.BossKillData> server = new HashMap<>();
        // Higher KC, lower prestige, but the only kill is a timestamp duplicate.
        server.put("Zulrah", boss("Zulrah", 9, 1,
                kill(500L, 0, false, drop(4151, 1, 100L, 50, 10))));
        manager.mergeServerData(server);

        LootStorageData.BossKillData b = manager.getCurrentData().getBossKills().get("Zulrah");
        assertEquals(9, b.getKillCount());
        assertEquals(3, b.getPrestige()); // max(3, 1)
        assertEquals(100L, b.getTotalLootValue()); // recalculated from the single existing kill
    }
}
