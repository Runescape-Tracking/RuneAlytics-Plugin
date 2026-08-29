package com.runealytics;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for hide / unhide / delete and the storage filters that keep
 * deleted items from coming back after a sync or refresh. Uses a real
 * {@link LootStorageManager} (null verified username short-circuits disk)
 * so persist + rehydrate paths are exercised.
 */
public class LootTrackerVisibilityTest
{
    private LootStorageManager storage;
    private LootTrackerManager manager;

    @Before
    public void setUp()
    {
        RuneAlyticsState state = mock(RuneAlyticsState.class);
        when(state.getVerifiedUsername()).thenReturn(null);
        storage = new LootStorageManager(state, new Gson());
        manager = newManager(storage);
    }

    private static LootTrackerManager newManager(LootStorageManager storageManager)
    {
        return new LootTrackerManager(
                mock(Client.class),
                mock(ClientThread.class),
                mock(ItemManager.class),
                mock(RunealyticsConfig.class),
                mock(RuneAlyticsState.class),
                storageManager,
                mock(LootTrackerApiClient.class),
                mock(ConfigManager.class),
                mock(ScheduledExecutorService.class),
                new Gson(),
                mock(DoomEncounterTracker.class),
                mock(GroundItemAttributor.class));
    }

    private static LootStorageData.DropRecord drop(int id, String name, int qty, long total, int ge, int alch)
    {
        LootStorageData.DropRecord d = new LootStorageData.DropRecord();
        d.setItemId(id);
        d.setItemName(name);
        d.setQuantity(qty);
        d.setTotalValue(total);
        d.setGePrice(ge);
        d.setHighAlch(alch);
        return d;
    }

    private void addZulrahKill(LootStorageData.DropRecord... drops)
    {
        storage.addKill("Zulrah", 2042, 100, 1, 330, 0, Arrays.asList(drops));
        manager.refreshFromStorage();
    }

    // ── hide / unhide drops ──────────────────────────────────────────────────

    @Test
    public void hideAndUnhideDrop_roundTripsThroughStorage()
    {
        addZulrahKill(drop(4151, "Abyssal whip", 1, 100L, 50, 10));

        assertFalse(manager.isDropHidden("Zulrah", 4151));
        manager.hideDropForNpc("Zulrah", 4151);
        assertTrue(manager.isDropHidden("Zulrah", 4151));
        assertTrue(storage.getCurrentData().getHiddenDropsByBoss().get("Zulrah").contains(4151));

        LootTrackerManager reloaded = newManager(storage);
        reloaded.refreshFromStorage();
        assertTrue(reloaded.isDropHidden("Zulrah", 4151));

        reloaded.unhideDropForNpc("Zulrah", 4151);
        assertFalse(reloaded.isDropHidden("Zulrah", 4151));
        assertTrue(storage.getCurrentData().getHiddenDropsByBoss().isEmpty()
                || !storage.getCurrentData().getHiddenDropsByBoss().containsKey("Zulrah"));
    }

    @Test
    public void hideAndUnhideBoss_roundTripsThroughStorage()
    {
        addZulrahKill(drop(4151, "Abyssal whip", 1, 100L, 50, 10));

        assertFalse(manager.isBossHidden("Zulrah"));
        manager.hideBoss("Zulrah");
        assertTrue(manager.isBossHidden("Zulrah"));
        assertTrue(storage.getCurrentData().getHiddenBosses().contains("Zulrah"));

        LootTrackerManager reloaded = newManager(storage);
        reloaded.refreshFromStorage();
        assertTrue(reloaded.isBossHidden("Zulrah"));

        reloaded.unhideBoss("Zulrah");
        assertFalse(reloaded.isBossHidden("Zulrah"));
        assertFalse(storage.getCurrentData().getHiddenBosses().contains("Zulrah"));
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    public void deleteDropForNpc_removesFromKillsAggregatesAndTracksDeletion()
    {
        addZulrahKill(
                drop(4151, "Abyssal whip", 1, 100L, 50, 10),
                drop(995, "Coins", 50, 50L, 1, 0));

        manager.deleteDropForNpc("Zulrah", 4151);

        LootStorageData.BossKillData boss = storage.getCurrentData().getBossKills().get("Zulrah");
        assertEquals(1, boss.getKills().get(0).getDrops().size());
        assertEquals(995, boss.getKills().get(0).getDrops().get(0).getItemId());
        assertFalse(boss.getAggregatedDrops().containsKey(4151));
        assertTrue(boss.getAggregatedDrops().containsKey(995));
        assertEquals(50L, boss.getTotalLootValue());
        assertTrue(storage.getCurrentData().getDeletedDropsByBoss().get("Zulrah").contains(4151));

        BossKillStats stats = manager.getBossKillStats("Zulrah");
        assertEquals(50L, stats.getTotalLootValue());
        assertEquals(1, stats.getAggregatedDrops().size());
        assertEquals(995, stats.getAggregatedDrops().get(0).getItemId());
    }

    @Test
    public void deleteDropForNpc_clearsHideFlagAndPreloadedCache()
    {
        LootStorageData.BossKillData boss = new LootStorageData.BossKillData();
        boss.setNpcName("Zulrah");
        boss.setNpcId(2042);
        boss.setKillCount(3);
        boss.setTotalLootValue(150L);
        LootStorageData.AggregatedDrop whip = new LootStorageData.AggregatedDrop();
        whip.setItemId(4151);
        whip.setItemName("Abyssal whip");
        whip.setTotalQuantity(1);
        whip.setTotalValue(100L);
        whip.setDropCount(1);
        LootStorageData.AggregatedDrop coins = new LootStorageData.AggregatedDrop();
        coins.setItemId(995);
        coins.setItemName("Coins");
        coins.setTotalQuantity(50);
        coins.setTotalValue(50L);
        coins.setDropCount(1);
        boss.getAggregatedDrops().put(4151, whip);
        boss.getAggregatedDrops().put(995, coins);
        storage.getCurrentData().getBossKills().put("Zulrah", boss);

        manager.refreshFromStorage();
        manager.hideDropForNpc("Zulrah", 4151);
        assertTrue(manager.isDropHidden("Zulrah", 4151));
        assertEquals(1, manager.getBossKillStats("Zulrah").getPreloadedDrops().stream()
                .filter(d -> d.getItemId() == 4151).count());

        manager.deleteDropForNpc("Zulrah", 4151);

        assertFalse(manager.isDropHidden("Zulrah", 4151));
        assertEquals(0, manager.getBossKillStats("Zulrah").getPreloadedDrops().stream()
                .filter(d -> d.getItemId() == 4151).count());
        assertEquals(50L, manager.getBossKillStats("Zulrah").getTotalLootValue());
        assertEquals(50L, storage.getCurrentData().getBossKills().get("Zulrah").getTotalLootValue());
        assertFalse(storage.getCurrentData().getHiddenDropsByBoss().containsKey("Zulrah"));
    }

    @Test
    public void deleteDropForNpc_unknownBoss_isNoOp()
    {
        manager.deleteDropForNpc("Nobody", 1);
        manager.deleteDropForNpc("Zulrah", 4151);
        assertTrue(storage.getCurrentData().getDeletedDropsByBoss().isEmpty());
    }

    @Test
    public void getStorageDropsForBoss_skipsDeletedItemsLeftInAggregates()
    {
        addZulrahKill(
                drop(4151, "Abyssal whip", 1, 100L, 50, 10),
                drop(995, "Coins", 50, 50L, 1, 0));
        storage.getCurrentData().getDeletedDropsByBoss()
                .computeIfAbsent("Zulrah", k -> new HashSet<>()).add(4151);

        List<BossKillStats.AggregatedDrop> drops = manager.getStorageDropsForBoss("Zulrah");
        assertEquals(1, drops.size());
        assertEquals(995, drops.get(0).getItemId());
    }

    @Test
    public void refreshFromStorage_purgesDeletedItemsStillInKillHistory()
    {
        addZulrahKill(
                drop(4151, "Abyssal whip", 1, 100L, 50, 10),
                drop(995, "Coins", 50, 50L, 1, 0));
        storage.getCurrentData().getDeletedDropsByBoss()
                .computeIfAbsent("Zulrah", k -> new HashSet<>()).add(4151);

        manager.refreshFromStorage();

        LootStorageData.BossKillData boss = storage.getCurrentData().getBossKills().get("Zulrah");
        assertEquals(1, boss.getKills().get(0).getDrops().size());
        assertEquals(995, boss.getKills().get(0).getDrops().get(0).getItemId());
        assertFalse(boss.getAggregatedDrops().containsKey(4151));
        assertEquals(50L, boss.getTotalLootValue());
    }

    // ── zero-value auto-hide ─────────────────────────────────────────────────

    @Test
    public void cleanupZeroValueDrops_hidesWorthlessNonPetDrops()
    {
        addZulrahKill(drop(1, "Junk", 1, 0L, 0, 0));
        manager.cleanupZeroValueDrops();
        assertTrue(manager.isDropHidden("Zulrah", 1));
    }

    @Test
    public void cleanupZeroValueDrops_neverHidesPets()
    {
        LootStorageData.DropRecord pet = drop(13247, "Pet snakeling", 1, 0L, 0, 0);
        pet.setPet(true);
        addZulrahKill(pet);
        manager.cleanupZeroValueDrops();
        assertFalse(manager.isDropHidden("Zulrah", 13247));
    }

    @Test
    public void cleanupZeroValueDrops_unhidesSkillingAndPickpocketLoot()
    {
        storage.addKill("Skilling: Farming", 0, 1, 1, 330, 0,
                Collections.singletonList(drop(1947, "Grain", 1, 0L, 0, 0)));
        storage.addKill("Pickpocket: Guard", 0, 1, 1, 330, 0,
                Collections.singletonList(drop(995, "Coins", 20, 0L, 0, 0)));
        manager.refreshFromStorage();

        manager.hideDropForNpc("Skilling: Farming", 1947);
        manager.hideDropForNpc("Pickpocket: Guard", 995);
        manager.cleanupZeroValueDrops();

        assertFalse(manager.isDropHidden("Skilling: Farming", 1947));
        assertFalse(manager.isDropHidden("Pickpocket: Guard", 995));
    }

    @Test
    public void getStorageDropsForBoss_unknownBoss_isEmpty()
    {
        assertTrue(manager.getStorageDropsForBoss("Nobody").isEmpty());
        assertNull(manager.getBossKillStats("Nobody"));
    }

    @Test
    public void getStorageDropsForBoss_sortsPetsThenUntradeablesThenValue()
    {
        LootStorageData.DropRecord pet = drop(13247, "Pet", 1, 0L, 0, 0);
        pet.setPet(true);
        addZulrahKill(
                drop(20997, "Twisted bow", 1, 1_000_000L, 1_000_000, 100),
                drop(1, "Untradeable", 1, 0L, 0, 0),
                pet);

        List<BossKillStats.AggregatedDrop> drops = manager.getStorageDropsForBoss("Zulrah");
        assertTrue(drops.get(0).isPet());
        assertTrue(drops.get(1).isUntradeable());
        assertEquals(1_000_000L, drops.get(2).getTotalValue());
    }
}
