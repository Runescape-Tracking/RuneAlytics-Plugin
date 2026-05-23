package com.runealytics;

import com.google.common.collect.ImmutableMap;
import net.runelite.api.InventoryID;

import java.util.Map;

/**
 * Single source of truth for every chest / reward interface the plugin reads.
 *
 * <p>Replaces the scattered {@code WIDGET_*} + {@code CONTAINER_*} constants
 * that used to live in both {@link RuneAlyticsPlugin} and
 * {@link LootTrackerManager}.  Several of the old container constants were
 * wrong (CoX read container {@code 122} instead of {@code 581}, ToA read
 * {@code 801} instead of {@code 811}), which silently dropped raid loot.
 * All known-good values are now defined here in one place.</p>
 *
 * <h2>How a reward gets read</h2>
 * <ol>
 *   <li>{@link RuneAlyticsPlugin#onWidgetLoaded} sees a widget load and looks
 *       up the {@link Source} by group id.</li>
 *   <li>If the source has a non-null {@link Source#containerId} the loot is
 *       read directly from the inventory container — the cleanest path.</li>
 *   <li>Otherwise the loot is collected by walking the widget tree
 *       ({@link LootTrackerManager#readWidgetLoot}).</li>
 * </ol>
 */
public final class RewardSources
{
    private RewardSources() {}

    // ── Widget group IDs ──────────────────────────────────────────────────────

    public static final int WIDGET_BARROWS            = 155;
    public static final int WIDGET_COX                = 539;
    public static final int WIDGET_TOB                = 23;
    public static final int WIDGET_TOA                = 773;
    public static final int WIDGET_CORRUPTED_GAUNTLET = 700;
    public static final int WIDGET_GAUNTLET           = 595;
    public static final int WIDGET_NIGHTMARE          = 600;
    public static final int WIDGET_ZALCANO            = 620;
    public static final int WIDGET_TEMPOROSS          = 229;
    public static final int WIDGET_WINTERTODT         = 634;
    public static final int WIDGET_CLUE               = 73;
    public static final int WIDGET_ROYAL_TITANS       = 174;
    public static final int WIDGET_YAMA               = 810;
    public static final int WIDGET_COLOSSEUM          = 867;
    public static final int WIDGET_HESPORI            = 897;
    public static final int WIDGET_WHISPERER          = 834;

    // ── Container IDs ─────────────────────────────────────────────────────────
    //
    // Pulled from the official RuneLite InventoryID enum where possible so
    // there's no chance of the wrong number sneaking in again.

    public static final int CONTAINER_BARROWS   = InventoryID.BARROWS_REWARD.getId();
    public static final int CONTAINER_COX       = InventoryID.CHAMBERS_OF_XERIC_CHEST.getId();
    public static final int CONTAINER_TOB       = InventoryID.THEATRE_OF_BLOOD_CHEST.getId();
    public static final int CONTAINER_TOA       = InventoryID.TOA_REWARD_CHEST.getId();
    public static final int CONTAINER_GAUNTLET  = 179;
    public static final int CONTAINER_NIGHTMARE = 646;
    public static final int CONTAINER_ZALCANO   = 631;
    public static final int CONTAINER_WILDY_LOOT_CHEST = InventoryID.WILDERNESS_LOOT_CHEST.getId();
    public static final int CONTAINER_LUNAR_CHEST      = InventoryID.LUNAR_CHEST.getId();
    public static final int CONTAINER_COLOSSEUM        = InventoryID.FORTIS_COLOSSEUM_REWARD_CHEST.getId();

    /** Describes how to read one reward source. */
    public static final class Source
    {
        /** Display / storage name (e.g. {@code "Chambers of Xeric"}). */
        public final String displayName;
        /** Inventory container to read, or {@code null} to walk the widget tree. */
        public final Integer containerId;
        /** How many top-level children to scan when walking the widget tree. */
        public final int widgetMaxChildren;

        Source(String displayName, Integer containerId, int widgetMaxChildren)
        {
            this.displayName       = displayName;
            this.containerId       = containerId;
            this.widgetMaxChildren = widgetMaxChildren;
        }
    }

    /**
     * Static widget-group → {@link Source} map.
     *
     * <p>NOTE: a few sources don't appear here because they need extra logic
     * (Nightmare-vs-Phosani name decision, Whisperer ground-item flow,
     * Wintertodt inventory diff). Those are still handled inline in
     * {@link RuneAlyticsPlugin#onWidgetLoaded}.</p>
     */
    public static final Map<Integer, Source> BY_WIDGET = ImmutableMap.<Integer, Source>builder()
            .put(WIDGET_BARROWS,            new Source("Barrows",            CONTAINER_BARROWS,   0))
            .put(WIDGET_COX,                new Source("Chambers of Xeric",  CONTAINER_COX,       0))
            .put(WIDGET_TOB,                new Source("Theatre of Blood",   CONTAINER_TOB,       0))
            .put(WIDGET_TOA,                new Source("Tombs of Amascut",   CONTAINER_TOA,       0))
            .put(WIDGET_CORRUPTED_GAUNTLET, new Source("Corrupted Gauntlet", CONTAINER_GAUNTLET,  0))
            .put(WIDGET_GAUNTLET,           new Source("The Gauntlet",       CONTAINER_GAUNTLET,  0))
            .put(WIDGET_ZALCANO,            new Source("Zalcano",            CONTAINER_ZALCANO,   0))
            .put(WIDGET_ROYAL_TITANS,       new Source("Royal Titans",       null, 100))
            .put(WIDGET_YAMA,               new Source("Yama",               null, 100))
            .put(WIDGET_COLOSSEUM,          new Source("Fortis Colosseum",   null, 150))
            .put(WIDGET_HESPORI,            new Source("Hespori",            null,  60))
            .put(WIDGET_TEMPOROSS,          new Source("Tempoross",          null,  80))
            .build();
}
