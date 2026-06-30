package com.runealytics;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable snapshot of the local player's world location at a single instant,
 * shared by every RuneAlytics upload (XP + loot) as the {@code location}
 * payload object documented by the website API.
 *
 * <p><b>Threading:</b> {@link #capture(Client)} reads live client state and so
 * MUST be called on the client thread (e.g. from an event handler). The
 * resulting object is plain immutable data, so it can be stashed in
 * {@link RuneAlyticsState} or persisted on a kill record and serialized later
 * on a background thread when the batched request is actually sent.</p>
 *
 * <p><b>Optional-safe:</b> capture returns {@code null} when the player isn't
 * rendered/logged-in. Callers add {@code location} to a payload only when
 * non-null, so older website code (and our own backend, which treats the field
 * as nullable) keeps working when location is absent.</p>
 *
 * <p>The {@code @SerializedName} annotations let Gson round-trip this object
 * straight to/from the on-disk loot storage file, while {@link #toJson()}
 * produces the exact wire shape the API expects.</p>
 */
@Getter
public final class PlayerLocationSnapshot
{
    @SerializedName("plane")      private int     plane;
    @SerializedName("world_x")    private int     worldX;
    @SerializedName("world_y")    private int     worldY;
    @SerializedName("region_id")  private int     regionId;
    @SerializedName("region_x")   private int     regionX;
    @SerializedName("region_y")   private int     regionY;
    @SerializedName("chunk_x")    private int     chunkX;
    @SerializedName("chunk_y")    private int     chunkY;
    @SerializedName("map_region") private String  mapRegion;
    @SerializedName("area_name")  private String  areaName;
    @SerializedName("instanced")  private boolean instanced;
    @SerializedName("world")      private int     world;
    @SerializedName("timestamp")  private long    timestamp;

    /** Required by Gson when deserializing persisted kill records. */
    public PlayerLocationSnapshot() {}

    public PlayerLocationSnapshot(
            int plane, int worldX, int worldY,
            int regionId, int regionX, int regionY,
            int chunkX, int chunkY,
            String mapRegion, String areaName,
            boolean instanced, int world, long timestamp)
    {
        this.plane     = plane;
        this.worldX    = worldX;
        this.worldY    = worldY;
        this.regionId  = regionId;
        this.regionX   = regionX;
        this.regionY   = regionY;
        this.chunkX    = chunkX;
        this.chunkY    = chunkY;
        this.mapRegion = mapRegion;
        this.areaName  = areaName;
        this.instanced = instanced;
        this.world     = world;
        this.timestamp = timestamp;
    }

    /**
     * World number that NEVER exists in real OSRS (live worlds top out in the
     * 500s). Used exclusively as the tag on {@link #privacyDecoy()} so any
     * downstream consumer — server code, dashboards, logs — can recognize at a
     * glance that a record is a decoy and must never be rendered as a real
     * player position.
     */
    public static final int PRIVACY_DECOY_WORLD = 1337;

    /**
     * Returns a fixed decoy location used in place of the real location when a
     * player's location visibility is {@code private}, so real coordinates never
     * leave the client. Always resolves to the Grand Exchange, tagged with
     * {@link #PRIVACY_DECOY_WORLD} so it is distinguishable from a genuine
     * location in logs/storage. {@code instanced} is {@code false} and
     * {@code area_name}/{@code map_region} are both {@code "Grand Exchange"}.
     */
    public static PlayerLocationSnapshot privacyDecoy()
    {
        return new PlayerLocationSnapshot(
                0, 3164, 3477,
                12598, 28, 21,
                395, 434,
                "Grand Exchange", "Grand Exchange",
                false, PRIVACY_DECOY_WORLD,
                System.currentTimeMillis() / 1000L);
    }

    /**
     * Entry point for location-sending events (live map heartbeat, XP-batch
     * location, loot kill-record location). Returns the privacy decoy when
     * {@code visibility} is {@code PRIVATE} so the decoy substitution is applied
     * uniformly at every call site rather than per-caller.
     *
     * @param client     the RuneLite client (read on the client thread)
     * @param visibility the player's current location-visibility {@link PrivacySetting}
     * @return {@link #privacyDecoy()} when {@code visibility} is {@code PRIVATE};
     *         otherwise the {@link #capture(Client)} result (which may be
     *         {@code null} if the player isn't renderable right now)
     */
    public static PlayerLocationSnapshot captureRespectingPrivacy(Client client, PrivacySetting visibility)
    {
        return (visibility == PrivacySetting.PRIVATE) ? privacyDecoy() : capture(client);
    }

    /**
     * Captures the local player's current location.
     *
     * <p>MUST be called on the client thread. Returns {@code null} when the
     * client or local player / world location is unavailable so callers can
     * simply omit the {@code location} field.</p>
     *
     * <p>For uploads use {@link #captureRespectingPrivacy} so the {@code private}
     * visibility decoy substitution is applied.</p>
     */
    public static PlayerLocationSnapshot capture(Client client)
    {
        if (client == null) return null;

        Player local = client.getLocalPlayer();
        if (local == null) return null;

        WorldPoint wp = local.getWorldLocation();
        if (wp == null) return null;

        int worldX = wp.getX();
        int worldY = wp.getY();

        return new PlayerLocationSnapshot(
                wp.getPlane(),
                worldX,
                worldY,
                wp.getRegionID(),
                wp.getRegionX(),
                wp.getRegionY(),
                worldX >> 3,                    // chunk_x
                worldY >> 3,                    // chunk_y
                null,                           // map_region — best effort, unresolved
                null,                           // area_name  — best effort, unresolved
                client.isInInstancedRegion(),
                client.getWorld(),
                System.currentTimeMillis() / 1000L
        );
    }

    /**
     * Serializes to the exact {@code location} object the website API expects.
     *
     * <p>{@code map_region}/{@code area_name} are emitted as JSON {@code null}
     * when unresolved ({@link JsonObject#addProperty(String, String)} maps a
     * {@code null} string to {@code JsonNull}).</p>
     */
    public JsonObject toJson()
    {
        JsonObject o = new JsonObject();
        o.addProperty("plane",      plane);
        o.addProperty("world_x",    worldX);
        o.addProperty("world_y",    worldY);
        o.addProperty("region_id",  regionId);
        o.addProperty("region_x",   regionX);
        o.addProperty("region_y",   regionY);
        o.addProperty("chunk_x",    chunkX);
        o.addProperty("chunk_y",    chunkY);
        o.addProperty("map_region", mapRegion); // null -> JSON null
        o.addProperty("area_name",  areaName);  // null -> JSON null
        o.addProperty("instanced",  instanced);
        o.addProperty("world",      world);
        o.addProperty("timestamp",  timestamp);
        return o;
    }

    @Override
    public String toString()
    {
        return "Location{world=" + world
                + ", plane=" + plane
                + ", wp=(" + worldX + "," + worldY + ")"
                + ", region=" + regionId
                + ", chunk=(" + chunkX + "," + chunkY + ")"
                + ", instanced=" + instanced
                + ", ts=" + timestamp + "}";
    }
}
