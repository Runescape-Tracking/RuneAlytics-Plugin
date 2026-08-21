package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.events.PluginLootReceived;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler for PluginLootReceived events — third-party plugin generated loot events.
 *
 * <p>PluginLootReceived allows other plugins to contribute loot events for encounters
 * that RuneLite's core does not yet support, or for specialized detection logic.
 * This is particularly useful for encounters like Gauntlet, Nightmare, and other
 * specialty bosses that might have custom detection plugins.</p>
 *
 * <p>Plugin loot has lower priority than ServerNpcLoot/NpcLootReceived but higher
 * than ground-item inference.</p>
 */
@Slf4j
@Singleton
public class PluginLootHandler
{
    /**
     * Window (ms) to keep plugin-generated loot events in buffer.
     * Longer than NpcLootReceived but shorter than ground-item windows
     * since plugin events should arrive relatively quickly.
     */
    private static final long PLUGIN_LOOT_WINDOW_MS = 3_000L;

    /**
     * Deduplication buffer: source name → list of recent events.
     * Prevents identical plugin loot from being recorded twice if a plugin
     * fires the event multiple times.
     */
    private final Map<String, Deque<PluginLootEvent>> perSourceBuffer = new ConcurrentHashMap<>();

    @Inject
    public PluginLootHandler() {}

    /**
     * Processes a PluginLootReceived event from the RuneLite bus.
     * Stores it in the per-source buffer for later consumption.
     */
    public void onPluginLootReceived(PluginLootReceived event)
    {
        if (event == null) return;

        String source = event.getSource();
        if (source == null || source.isEmpty())
        {
            log.debug("PluginLootReceived has empty/null source");
            return;
        }

        List<ItemStack> items = new ArrayList<>();
        Collection<net.runelite.client.game.ItemStack> rlItems = event.getItems();
        if (rlItems != null)
        {
            for (net.runelite.client.game.ItemStack i : rlItems)
            {
                if (i != null && i.getId() > 0 && i.getQuantity() > 0)
                    items.add(new ItemStack(i.getId(), i.getQuantity()));
            }
        }

        if (items.isEmpty())
        {
            log.debug("PluginLootReceived with no items from source '{}'", source);
            return;
        }

        long now = System.currentTimeMillis();

        Deque<PluginLootEvent> queue = perSourceBuffer.computeIfAbsent(source, k -> new ArrayDeque<>());
        queue.add(new PluginLootEvent(source, items, now));

        log.debug("PluginLootReceived buffered: source='{}' items={}", source, items.size());

        // Clean old entries (expire after window)
        queue.removeIf(e -> now - e.receivedAt > PLUGIN_LOOT_WINDOW_MS);
    }

    /**
     * Polls for a PluginLootReceived event matching the given source name.
     * Returns the oldest matching event, or null if none are in buffer.
     */
    public PluginLootEvent pollForSource(String source)
    {
        Deque<PluginLootEvent> queue = perSourceBuffer.get(source);
        return queue != null ? queue.pollFirst() : null;
    }

    /**
     * Returns whether any PluginLootReceived events are pending for the given source.
     */
    public boolean hasPendingLoot(String source)
    {
        Deque<PluginLootEvent> queue = perSourceBuffer.get(source);
        return queue != null && !queue.isEmpty();
    }

    /** Clears all buffered events (logout/account switch). */
    public void reset()
    {
        perSourceBuffer.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Lightweight holder for a PluginLootReceived event. */
    public static class PluginLootEvent
    {
        public final String source;
        public final List<ItemStack> items;
        public final long receivedAt;

        public PluginLootEvent(String source, List<ItemStack> items, long receivedAt)
        {
            this.source = source;
            this.items = items;
            this.receivedAt = receivedAt;
        }
    }
}
