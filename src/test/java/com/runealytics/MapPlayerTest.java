package com.runealytics;

import com.google.gson.Gson;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Coverage for the live-map player DTO: WorldPoint conversion + caching and
 * the diagnostic toString.
 */
public class MapPlayerTest
{
    @Test
    public void toWorldPoint_mapsCoordinatesAndPlane()
    {
        MapPlayer player = new MapPlayer("Zezima", 3200, 3210, 1, 330);
        WorldPoint wp = player.toWorldPoint();

        assertEquals(3200, wp.getX());
        assertEquals(3210, wp.getY());
        assertEquals(1, wp.getPlane());
    }

    @Test
    public void toWorldPoint_isCachedAcrossCalls()
    {
        MapPlayer player = new MapPlayer("Zezima", 3200, 3210, 0, 330);
        assertSame(player.toWorldPoint(), player.toWorldPoint());
    }

    @Test
    public void toWorldPoint_handlesNegativeAndLargeCoordinates()
    {
        MapPlayer player = new MapPlayer("edge", -1, Integer.MAX_VALUE, 3, 1);
        WorldPoint wp = player.toWorldPoint();
        assertEquals(-1, wp.getX());
        assertEquals(Integer.MAX_VALUE, wp.getY());
        assertEquals(3, wp.getPlane());
    }

    @Test
    public void noArgConstructor_leavesDefaults()
    {
        MapPlayer player = new MapPlayer();
        assertEquals(0, player.getWorldX());
        assertEquals(0, player.getWorld());
        // username defaults to null for the Gson no-arg form.
        assertEquals("MapPlayer{null @(0,0,0) world=0}", player.toString());
    }

    @Test
    public void toString_containsUsernameAndCoordinates()
    {
        MapPlayer player = new MapPlayer("Zezima", 3200, 3210, 2, 330);
        String text = player.toString();
        assertTrue(text.contains("Zezima"));
        assertTrue(text.contains("3200"));
        assertTrue(text.contains("world=330"));
    }

    @Test
    public void gsonDeserialization_mapsSnakeCaseWireFields()
    {
        // Mirrors the shape parsed from the /plugin/heartbeat "players" array.
        String json = "{\"username\":\"Zezima\",\"world_x\":3200,\"world_y\":3210,"
                + "\"plane\":1,\"world\":330}";
        MapPlayer player = new Gson().fromJson(json, MapPlayer.class);

        assertEquals("Zezima", player.getUsername());
        assertEquals(3200, player.getWorldX());
        assertEquals(3210, player.getWorldY());
        assertEquals(1, player.getPlane());
        assertEquals(330, player.getWorld());
    }
}
