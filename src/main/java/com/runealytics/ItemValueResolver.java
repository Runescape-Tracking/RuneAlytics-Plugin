package com.runealytics;

import com.google.common.collect.ImmutableMap;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;

import java.util.Map;

/**
 * Resolves the GE value of any item — including untradeable / charged variants
 * — by canonicalising and (where necessary) decomposing into the tradeable
 * components a player would have to spend to re-create it.
 *
 * <p>Issue #5: items like Scythe of Vitur, Tumeken's Shadow, Sanguinesti
 * staff, and gear like Ferocious Gloves are <em>untradeable in their charged
 * / final form</em>. {@link ItemManager#getItemPrice} returns 0 for them, so
 * bank totals were silently undercounting tens of thousands of GP per stack.
 * This resolver looks each one up in a hand-curated decomposition table and
 * falls back to the canonical (uncharged / unnoted) form.</p>
 *
 * <p>Adding new items: append to {@link #DECOMPOSITION}. The "value" is a
 * {@code Map<itemId, quantity>} naming the tradeable components that compose
 * the untradeable item. Charges are intentionally not modelled — we report
 * the value of an <em>empty</em> charged item rather than guessing how full
 * the player has it.</p>
 */
public final class ItemValueResolver
{
    private ItemValueResolver() {}

    /**
     * Maps an untradeable / charged item ID → its tradeable components.
     *
     * <p>Each entry says "to make this item the player burned the listed
     * tradeable ingredients". Values are summed at runtime against live GE
     * prices, so the totals stay accurate even as prices drift.</p>
     */
    private static final Map<Integer, Map<Integer, Integer>> DECOMPOSITION =
            ImmutableMap.<Integer, Map<Integer, Integer>>builder()
                    // ── ToB megas (charged variants are untradeable; report base) ──
                    .put(ItemID.SCYTHE_OF_VITUR,            componentMap(ItemID.SCYTHE_OF_VITUR_UNCHARGED, 1))
                    .put(ItemID.SANGUINESTI_STAFF,          componentMap(ItemID.SANGUINESTI_STAFF_UNCHARGED, 1))
                    .put(ItemID.GHRAZI_RAPIER,              componentMap(ItemID.GHRAZI_RAPIER, 1))
                    .put(ItemID.JUSTICIAR_FACEGUARD,        componentMap(ItemID.JUSTICIAR_FACEGUARD, 1))
                    .put(ItemID.JUSTICIAR_CHESTGUARD,       componentMap(ItemID.JUSTICIAR_CHESTGUARD, 1))
                    .put(ItemID.JUSTICIAR_LEGGUARDS,        componentMap(ItemID.JUSTICIAR_LEGGUARDS, 1))

                    // ── ToA megas ──────────────────────────────────────────────
                    .put(ItemID.TUMEKENS_SHADOW,            componentMap(ItemID.TUMEKENS_SHADOW_UNCHARGED, 1))

                    // ── CoX (already tradeable but kept for completeness) ──────
                    // (Twisted bow / Kodai / etc. trade as-is — no decomposition needed.)

                    // ── Hydra / Slayer untradeables ────────────────────────────
                    .put(ItemID.FEROCIOUS_GLOVES,           componentMap(ItemID.HYDRA_LEATHER, 1))

                    // ── Crystal armour / weapons (charged; report blueprint cost) ──
                    .put(ItemID.BOW_OF_FAERDHINEN,          componentMap(ItemID.BOW_OF_FAERDHINEN_INACTIVE, 1))
                    .put(ItemID.BLADE_OF_SAELDOR,           componentMap(ItemID.BLADE_OF_SAELDOR_INACTIVE, 1))

                    // ── Misc charged / inactive forms ──────────────────────────
                    .put(ItemID.RING_OF_SUFFERING_R,        componentMap(ItemID.RING_OF_SUFFERING, 1))
                    .put(ItemID.AMULET_OF_BLOOD_FURY,       componentMap(ItemID.AMULET_OF_FURY, 1, ItemID.BLOOD_SHARD, 1))
                    .put(ItemID.AMULET_OF_THE_DAMNED_FULL,  componentMap(ItemID.AMULET_OF_THE_DAMNED, 1))
                    .build();

    private static Map<Integer, Integer> componentMap(int... idQtyPairs)
    {
        if (idQtyPairs.length % 2 != 0)
            throw new IllegalArgumentException("componentMap requires id,qty pairs");
        ImmutableMap.Builder<Integer, Integer> b = ImmutableMap.builder();
        for (int i = 0; i < idQtyPairs.length; i += 2)
            b.put(idQtyPairs[i], idQtyPairs[i + 1]);
        return b.build();
    }

    /**
     * Returns the live GE value of a single item, resolving untradeable /
     * charged / noted forms transparently.
     *
     * <ol>
     *   <li>If the item has an explicit decomposition entry, sum component
     *       GE values.</li>
     *   <li>Otherwise fall back to {@link ItemManager#canonicalize} and look
     *       up the canonical form's price — this handles noted items, broken
     *       weapons, and any item whose linked-tradeable RuneLite knows
     *       about.</li>
     *   <li>If both lookups return 0 (truly untradeable, e.g. quest items),
     *       returns 0.</li>
     * </ol>
     */
    public static int perItemGeValue(ItemManager itemManager, int itemId)
    {
        if (itemManager == null || itemId <= 0) return 0;

        Map<Integer, Integer> components = DECOMPOSITION.get(itemId);
        if (components != null)
        {
            long total = 0;
            for (Map.Entry<Integer, Integer> e : components.entrySet())
                total += (long) itemManager.getItemPrice(e.getKey()) * e.getValue();
            if (total > 0) return (int) Math.min(total, Integer.MAX_VALUE);
        }

        int canonical = itemManager.canonicalize(itemId);
        int price = itemManager.getItemPrice(canonical);
        if (price > 0) return price;

        // Last-ditch fallback: high-alch value (so the bank total includes at
        // least *something* for purely untradeable items).
        ItemComposition comp = itemManager.getItemComposition(itemId);
        if (comp != null) return Math.max(0, comp.getHaPrice());
        return 0;
    }
}
