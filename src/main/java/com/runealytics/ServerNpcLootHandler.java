package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.client.events.ServerNpcLoot;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Handler for ServerNpcLoot events — RuneLite's authoritative server-provided
 * NPC loot detection (available since RuneLite ~2020).
 *
 * <p>ServerNpcLoot has higher priority than NpcLootReceived because it is
 * server-confirmed and unambiguous.</p>
 *
 * <p>This is a bridge class to avoid coupling the main plugin to RuneLite's
 * event bus. It stores incoming events and allows LootTrackerManager to
 * consume them in priority order.</p>
 */
@Slf4j
@Singleton
public class ServerNpcLootHandler
{
    /**
     * Window (ms) to match ServerNpcLoot events with their source NPC.
     * ServerNpcLoot typically arrives a few ticks after the NPC dies, so
     * this is significantly longer than NpcLootReceived windows.
     */
    private static final long SERVER_LOOT_WINDOW_MS = 5_000L;

    /**
     * Per-NPC index buffer of recent ServerNpcLoot events, allowing matching
     * to in-flight kills or pending state.
     */
    private final ConcurrentHashMap<Integer, ConcurrentLinkedDeque<ServerNpcLootEvent>> perNpcBuffer =
            new ConcurrentHashMap<>();

    @Inject
    public ServerNpcLootHandler() {}

    /**
     * Processes a ServerNpcLoot event from the RuneLite bus.
     * Stores it in the per-NPC buffer for later consumption.
     */
    public void onServerNpcLoot(ServerNpcLoot event)
    {
        if (event == null) return;

        NPC npc = event.getNpc();
        if (npc == null)
        {
            log.debug("ServerNpcLoot event has null NPC");
            return;
        }

        int npcIndex = npc.getIndex();
        int npcId = npc.getId();

        List<ItemStack> items = new ArrayList<>();
        for (net.runelite.client.game.ItemStack i : event.getItems())
        {
            if (i != null && i.getId() > 0 && i.getQuantity() > 0)
                items.add(new ItemStack(i.getId(), i.getQuantity()));
        }

        long now = System.currentTimeMillis();

        ConcurrentLinkedDeque<ServerNpcLootEvent> queue = perNpcBuffer.computeIfAbsent(npcIndex, k -> new ConcurrentLinkedDeque<>());
        queue.add(new ServerNpcLootEvent(npc, npcId, items, now));

        log.debug("ServerNpcLoot buffered: npc={} (id={}) items={}", npc.getName(), npcId, items.size());

        // Clean old entries (expire after window)
        queue.removeIf(e -> now - e.receivedAt > SERVER_LOOT_WINDOW_MS);
    }

    /**
     * Polls for a ServerNpcLoot event matching the given NPC index.
     * Returns the oldest matching event, or null if none are in buffer.
     */
    public ServerNpcLootEvent pollForNpc(int npcIndex)
    {
        ConcurrentLinkedDeque<ServerNpcLootEvent> queue = perNpcBuffer.get(npcIndex);
        return queue != null ? queue.pollFirst() : null;
    }

    /**
     * Returns whether any ServerNpcLoot events are pending for the given NPC index.
     */
    public boolean hasPendingLoot(int npcIndex)
    {
        ConcurrentLinkedDeque<ServerNpcLootEvent> queue = perNpcBuffer.get(npcIndex);
        return queue != null && !queue.isEmpty();
    }

    /** Clears all buffered events (logout/account switch). */
    public void reset()
    {
        perNpcBuffer.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Lightweight holder for a ServerNpcLoot event. */
    public static class ServerNpcLootEvent
    {
        public final NPC npc;
        public final int npcId;
        public final List<ItemStack> items;
        public final long receivedAt;

        public ServerNpcLootEvent(NPC npc, int npcId, List<ItemStack> items, long receivedAt)
        {
            this.npc = npc;
            this.npcId = npcId;
            this.items = items;
            this.receivedAt = receivedAt;
        }
    }
}
