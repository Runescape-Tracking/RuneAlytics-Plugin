package com.runealytics;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * A single player the website says the local player is allowed to see on the
 * live map, parsed from the {@code players} array of the {@code /plugin/heartbeat}
 * response. Visibility/eligibility is decided server-side; the client just renders
 * whatever it is given.
 *
 * <p>Plain immutable data so it can be stashed in {@link RuneAlyticsState} and
 * read from the client thread by {@link LiveMapMinimapOverlay}.</p>
 */
@Getter
public final class MapPlayer
{
    @SerializedName("username") private String username;
    @SerializedName("world_x")  private int    worldX;
    @SerializedName("world_y")  private int    worldY;
    @SerializedName("plane")    private int    plane;
    @SerializedName("world")    private int    world;

    /** Required by Gson. */
    public MapPlayer() {}

    public MapPlayer(String username, int worldX, int worldY, int plane, int world)
    {
        this.username = username;
        this.worldX   = worldX;
        this.worldY   = worldY;
        this.plane    = plane;
        this.world    = world;
    }

    /** Convenience accessor for rendering on the scene/minimap. */
    public WorldPoint toWorldPoint()
    {
        return new WorldPoint(worldX, worldY, plane);
    }

    @Override
    public String toString()
    {
        return "MapPlayer{" + username + " @(" + worldX + "," + worldY + "," + plane
                + ") world=" + world + "}";
    }
}
