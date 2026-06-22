package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;

import java.util.List;

/**
 * Shared helpers for converting RuneLite {@link ItemContainer}s and
 * {@link ItemStack} lists into the {@code [{id, qty}, ...]} JSON shape used
 * everywhere the plugin POSTs item data (bank sync, matchmaking item report,
 * wealth snapshots, etc).
 *
 * <p>Consolidated here so {@link BankDataManager} and {@link MatchmakingManager}
 * do not each carry their own near-identical copy of the conversion code.</p>
 *
 * <p>The "valued" variants additionally embed per-item GE value resolved via
 * {@link ItemValueResolver} so the server doesn't have to lookup prices
 * itself, and so untradeable / charged variants (Scythe, Sanguinesti, etc.)
 * count toward the player's reported wealth (issue #5).</p>
 */
public final class RuneAlyticsItemJson
{
    private RuneAlyticsItemJson() {}

    /**
     * Returns a JSON array of {@code {id, qty}} objects describing every
     * non-empty slot in {@code container}. Returns an empty array if the
     * container is null/empty.
     */
    public static JsonArray fromContainer(ItemContainer container)
    {
        JsonArray arr = new JsonArray();
        if (container == null) return arr;

        Item[] items = container.getItems();
        if (items == null) return arr;

        for (Item item : items)
        {
            if (item == null) continue;
            if (item.getId() <= 0 || item.getQuantity() <= 0) continue;

            JsonObject entry = new JsonObject();
            entry.addProperty("id",  item.getId());
            entry.addProperty("qty", item.getQuantity());
            arr.add(entry);
        }
        return arr;
    }

    /**
     * Serialises an <em>equipment</em> container into {@code [{slot, id, qty}, ...]}
     * (no GE values).  Slot index lets the server identify the weapon slot (3)
     * for gear-rule validation and the website prices every item itself.
     *
     * <p>Used by the matchmaking path so the plugin sends lean item data and
     * the website owns all valuation.</p>
     */
    public static JsonArray fromEquipment(ItemContainer container)
    {
        JsonArray arr = new JsonArray();
        if (container == null) return arr;

        Item[] items = container.getItems();
        if (items == null) return arr;

        for (int slot = 0; slot < items.length; slot++)
        {
            Item item = items[slot];
            if (item == null) continue;
            if (item.getId() <= 0 || item.getQuantity() <= 0) continue;

            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("id",   item.getId());
            entry.addProperty("qty",  item.getQuantity());
            arr.add(entry);
        }
        return arr;
    }

    /** Same as {@link #fromContainer} but for a list of {@link ItemStack}. */
    public static JsonArray fromStacks(List<ItemStack> stacks)
    {
        JsonArray arr = new JsonArray();
        if (stacks == null) return arr;

        for (ItemStack s : stacks)
        {
            if (s == null) continue;
            if (s.getId() <= 0 || s.getQuantity() <= 0) continue;

            JsonObject entry = new JsonObject();
            entry.addProperty("id",  s.getId());
            entry.addProperty("qty", s.getQuantity());
            arr.add(entry);
        }
        return arr;
    }

    /**
     * Same as {@link #fromContainer} but also writes {@code ge_per},
     * {@code total} for every item — including untradeable / charged variants
     * resolved via {@link ItemValueResolver} (issue #5).
     */
    public static JsonArray fromContainerWithValues(ItemContainer container, ItemManager itemManager)
    {
        JsonArray arr = new JsonArray();
        if (container == null) return arr;

        Item[] items = container.getItems();
        if (items == null) return arr;

        for (Item item : items)
        {
            if (item == null) continue;
            if (item.getId() <= 0 || item.getQuantity() <= 0) continue;

            int gePer = ItemValueResolver.perItemGeValue(itemManager, item.getId());
            long total = (long) gePer * item.getQuantity();

            JsonObject entry = new JsonObject();
            entry.addProperty("id",     item.getId());
            entry.addProperty("qty",    item.getQuantity());
            entry.addProperty("ge_per", gePer);
            entry.addProperty("total",  total);
            arr.add(entry);
        }
        return arr;
    }

    /**
     * Sum total GE value (with decomposition) of every item in {@code container}.
     * Returns 0 for a null/empty container.
     */
    public static long containerTotalValue(ItemContainer container, ItemManager itemManager)
    {
        if (container == null) return 0L;
        Item[] items = container.getItems();
        if (items == null) return 0L;

        long total = 0L;
        for (Item item : items)
        {
            if (item == null) continue;
            if (item.getId() <= 0 || item.getQuantity() <= 0) continue;
            total += (long) ItemValueResolver.perItemGeValue(itemManager, item.getId()) * item.getQuantity();
        }
        return total;
    }
}
