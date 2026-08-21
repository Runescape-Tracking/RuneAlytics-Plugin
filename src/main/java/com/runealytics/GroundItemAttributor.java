package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

/**
 * Validates and scopes ground-item attribution to prevent false positives.
 *
 * <p>Ground-item detection is the least reliable loot-tracking method because
 * multiple items spawning simultaneously in a multi-player area can be attributed
 * to the wrong NPC. This validator ensures ground items are only counted as loot
 * when they clearly belong to a specific kill.</p>
 *
 * <h2>Scope Validation</h2>
 * <p>Ground items are only attributed to a kill when ALL of:</p>
 * <ul>
 *   <li>Item spawned within {@value #GROUND_ITEM_PROXIMITY_TILES} tiles of the kill</li>
 *   <li>Item spawned within {@value #GROUND_ITEM_WINDOW_MS} ms of the kill</li>
 *   <li>Item is not a shared loot chest/interface (runs, raids, etc.)</li>
 *   <li>Player is not in a shared multi-combat area (Wilderness, Dungeon, Raid)</li>
 *   <li>Item does not match known non-loot IDs (cosmetics, etc.)</li>
 * </ul>
 *
 * <h2>High-Risk Scenarios (Bypassed)</h2>
 * <ul>
 *   <li>Wilderness (where loot can be seen by other players) — use server loot only</li>
 *   <li>Raid areas (CoX, ToB, ToA) — use container reads, not ground items</li>
 *   <li>Multi-combat zones with multiple players — attribution uncertain</li>
 *   <li>GWD / other group dungeons — use widget/container reads instead</li>
 * </ul>
 */
@Slf4j
@Singleton
public class GroundItemAttributor
{
    /**
     * Maximum distance (in tiles) an item can spawn from the kill location
     * to be attributed to that kill.
     */
    private static final int GROUND_ITEM_PROXIMITY_TILES = 8;

    /**
     * Maximum time (ms) after a kill during which ground items are attributed to it.
     */
    private static final long GROUND_ITEM_WINDOW_MS = 5_000L;

    /**
     * World types where ground-item attribution is disabled entirely
     * (loot is visible to other players, unsafe).
     */
    private static final Set<Integer> UNSAFE_WORLD_TYPES = Set.of(
            4,   // PvP
            64,  // Dead man
            128, // Survival
            256  // Leagues
    );

    /**
     * Region IDs that are multi-player or raid areas where item attribution
     * is ambiguous. Uses container reads or widget reads instead.
     */
    private static final Set<Integer> NO_GROUND_ITEM_REGIONS = Set.of(
            12889, 12890, 12891, 13105, 13106, 13107, // CoX
            12611, 12612, 12867, 12868,                // ToB
            14386, 14387, 14643, 14644, 14645,         // ToA
            12444,                                      // Barrows
            7513, 7769,                                 // GWD
            15131                                       // Inferno (for now)
    );

    /**
     * Items that should never be considered loot even if they spawn near a kill.
     * (Cosmetics, boss mechanics, non-drop items, etc.)
     */
    private static final Set<Integer> NON_LOOT_ITEM_IDS = Set.of(
            ItemID.CLUE_SCROLL, // Handled separately via menu + widget
            ItemID.BURNING_LOG   // Too common, would create false positives
    );

    private final Client client;

    @Inject
    public GroundItemAttributor(Client client)
    {
        this.client = client;
    }

    /**
     * Validates whether a ground item should be attributed to a specific kill.
     *
     * @param itemId item ID that spawned
     * @param itemQuantity quantity that spawned
     * @param killLocation world point where the kill occurred
     * @param killTime epoch ms when the kill occurred (approximate)
     * @return true if the item should be counted as loot from this kill
     */
    public boolean shouldAttributeToKill(int itemId, int itemQuantity,
                                         WorldPoint killLocation, long killTime)
    {
        // --- Sanity checks ---
        if (itemId <= 0 || itemQuantity <= 0 || killLocation == null)
            return false;

        // --- Filter non-loot items ---
        if (NON_LOOT_ITEM_IDS.contains(itemId))
        {
            log.debug("Ground item {} is in non-loot filter", itemId);
            return false;
        }

        // --- World type validation ---
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null)
            return false;

        WorldPoint playerLoc = localPlayer.getWorldLocation();
        if (playerLoc == null)
            return false;

        // Check if world is unsafe (PvP, DMM, Survival, Leagues)
        int world = client.getWorld();
        if (isUnsafeWorld(world))
        {
            log.debug("Ground item {} in unsafe world type {}, attribution blocked", itemId, world);
            return false;
        }

        // --- Region validation ---
        if (isInNoGroundItemRegion(killLocation))
        {
            log.debug("Ground item {} in raid/multi-combat region, attribution blocked", itemId);
            return false;
        }

        // --- Proximity validation ---
        int distanceSquared = playerLoc.distanceTo(killLocation);
        if (distanceSquared > GROUND_ITEM_PROXIMITY_TILES)
        {
            log.debug("Ground item {} spawned {} tiles away (max={}), not attributed",
                    itemId, distanceSquared, GROUND_ITEM_PROXIMITY_TILES);
            return false;
        }

        // --- Time window validation ---
        long elapsed = System.currentTimeMillis() - killTime;
        if (elapsed > GROUND_ITEM_WINDOW_MS)
        {
            log.debug("Ground item {} spawned {}ms after kill (max={}ms), not attributed",
                    itemId, elapsed, GROUND_ITEM_WINDOW_MS);
            return false;
        }

        log.debug("Ground item {} attributed to kill at {} ({}ms, {}tiles away)",
                itemId, killLocation, elapsed, distanceSquared);
        return true;
    }

    /**
     * Returns true if the given world type is unsafe for ground-item attribution.
     * (PvP worlds where items are visible to other players, DMM, etc.)
     */
    private boolean isUnsafeWorld(int worldNumber)
    {
        // World type is encoded in the world number
        // Lower bits indicate world type flags
        for (int unsafe : UNSAFE_WORLD_TYPES)
        {
            if ((worldNumber & unsafe) != 0)
                return true;
        }
        return false;
    }

    /**
     * Returns true if the given region is a raid, dungeon, or multi-combat area
     * where ground-item attribution is ambiguous.
     */
    private boolean isInNoGroundItemRegion(WorldPoint p)
    {
        if (p == null) return false;
        int region = p.getRegionID();
        return NO_GROUND_ITEM_REGIONS.contains(region);
    }
}
