package com.runealytics;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for the deferred NPC loot path added to keep ItemManager work on
 * the client thread without racing {@code ClientThread.invoke} when the caller
 * is already on a background executor.
 */
public class LootTrackerManagerLootPathTest
{
    private Client client;
    private ClientThread clientThread;
    private ItemManager itemManager;
    private RunealyticsConfig config;
    private ScheduledExecutorService executor;
    private LootStorageManager storage;
    private LootTrackerManager manager;

    @Before
    public void setUp()
    {
        client = mock(Client.class);
        clientThread = mock(ClientThread.class);
        itemManager = mock(ItemManager.class);
        config = mock(RunealyticsConfig.class);
        executor = mock(ScheduledExecutorService.class);

        RuneAlyticsState state = mock(RuneAlyticsState.class);
        when(state.getVerifiedUsername()).thenReturn(null);
        storage = new LootStorageManager(state, new Gson());

        when(config.enableLootTracking()).thenReturn(true);
        when(config.trackAllNpcs()).thenReturn(false);
        when(config.syncLootToServer()).thenReturn(false);
        when(config.minimumLootValue()).thenReturn(0);

        when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(itemManager.getItemPrice(anyInt())).thenReturn(50);
        ItemComposition comp = mock(ItemComposition.class);
        when(comp.getName()).thenReturn("Abyssal whip");
        when(comp.getHaPrice()).thenReturn(10);
        when(itemManager.getItemComposition(anyInt())).thenReturn(comp);

        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).schedule(any(Runnable.class), anyLong(), any());

        runInvokeInline();

        manager = new LootTrackerManager(
                client,
                clientThread,
                itemManager,
                config,
                state,
                storage,
                mock(LootTrackerApiClient.class),
                mock(ConfigManager.class),
                executor,
                new Gson(),
                mock(DoomEncounterTracker.class),
                mock(GroundItemAttributor.class));
    }

    private void runInvokeInline()
    {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return true;
        }).when(clientThread).invoke(any(Runnable.class));
    }

    private static List<ItemStack> whip()
    {
        return Collections.singletonList(new ItemStack(4151, 1));
    }

    // ── processNpcLootDeferred ──────────────────────────────────────────────

    @Test
    public void processNpcLootDeferred_recordsBossKillWhenInvokeRunsInline()
    {
        manager.processNpcLootDeferred("Zulrah", 2042, 725, 330, whip(), null);

        LootStorageData.BossKillData b = storage.getCurrentData().getBossKills().get("Zulrah");
        assertEquals(1, b.getKills().size());
        assertEquals(1, b.getKills().get(0).getDrops().size());
        assertEquals(4151, b.getKills().get(0).getDrops().get(0).getItemId());
        assertEquals(50L, b.getKills().get(0).getDrops().get(0).getTotalValue());
    }

    @Test
    public void processNpcLootDeferred_queuedInvoke_doesNotRecordUntilCallbackRuns()
    {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        doAnswer(inv -> {
            queued.set(inv.getArgument(0));
            return false;
        }).when(clientThread).invoke(any(Runnable.class));

        manager.processNpcLootDeferred("Zulrah", 2042, 725, 330, whip(), null);

        assertNull(storage.getCurrentData().getBossKills().get("Zulrah"));
        assertTrue(queued.get() != null);

        queued.get().run();

        assertEquals(1, storage.getCurrentData().getBossKills().get("Zulrah").getKills().size());
    }

    @Test
    public void processNpcLootDeferred_filtersNonBossWhenTrackAllNpcsOff()
    {
        manager.processNpcLootDeferred("Goblin", 1, 2, 330, whip(), null);

        assertNull(storage.getCurrentData().getBossKills().get("Goblin"));
        verify(clientThread, never()).invoke(any(Runnable.class));
    }

    @Test
    public void processNpcLootDeferred_disabledTracking_isNoOp()
    {
        when(config.enableLootTracking()).thenReturn(false);

        manager.processNpcLootDeferred("Zulrah", 2042, 725, 330, whip(), null);

        assertNull(storage.getCurrentData().getBossKills().get("Zulrah"));
        verify(clientThread, never()).invoke(any(Runnable.class));
    }

    @Test
    public void processNpcLootDeferred_nullItems_recordsEmptyKill()
    {
        manager.processNpcLootDeferred("Zulrah", 2042, 725, 330, null, null);

        LootStorageData.BossKillData b = storage.getCurrentData().getBossKills().get("Zulrah");
        assertEquals(1, b.getKills().size());
        assertTrue(b.getKills().get(0).getDrops().isEmpty());
    }

    // ── upgradeRecentZeroLootKillDeferred ───────────────────────────────────

    @Test
    public void upgradeRecentZeroLootKill_attachesDropsToEmptyLastKill()
    {
        manager.processNpcLootDeferred("Zulrah", 2042, 725, 330, Collections.emptyList(), null);

        assertTrue(manager.upgradeRecentZeroLootKillDeferred("Zulrah", whip()));

        List<LootStorageData.DropRecord> drops =
                storage.getCurrentData().getBossKills().get("Zulrah").getKills().get(0).getDrops();
        assertEquals(1, drops.size());
        assertEquals(4151, drops.get(0).getItemId());
    }

    @Test
    public void upgradeRecentZeroLootKill_rejectsWhenLastKillAlreadyHasDrops()
    {
        manager.processNpcLootDeferred("Zulrah", 2042, 725, 330, whip(), null);

        assertFalse(manager.upgradeRecentZeroLootKillDeferred("Zulrah", whip()));
        assertEquals(1, storage.getCurrentData().getBossKills()
                .get("Zulrah").getKills().get(0).getDrops().size());
    }

    @Test
    public void upgradeRecentZeroLootKill_normalizesRawNpcName()
    {
        manager.processNpcLootDeferred("Duke Sucellus", 12191, 1207, 330,
                Collections.emptyList(), null);

        assertTrue(manager.upgradeRecentZeroLootKillDeferred("duke", whip()));

        assertEquals(1, storage.getCurrentData().getBossKills()
                .get("Duke Sucellus").getKills().get(0).getDrops().size());
    }
}
