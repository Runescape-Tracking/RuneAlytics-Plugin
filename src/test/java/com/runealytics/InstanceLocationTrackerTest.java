package com.runealytics;

import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class InstanceLocationTrackerTest
{
    /** Taverley Dungeon, by the Cerberus entrance. */
    private static final WorldPoint CERBERUS_ENTRANCE = new WorldPoint(2873, 9847, 0);
    /** A tile in Cerberus's lair (template region 4883). */
    private static final WorldPoint CERBERUS_LAIR = new WorldPoint(1240, 1250, 0);
    /** Some instance template not in the table. */
    private static final WorldPoint UNKNOWN_TEMPLATE = new WorldPoint(3000, 3000, 0);

    private Client client;
    private Player me;
    private InstanceLocationTracker tracker;
    private boolean instanced;
    private WorldPoint standing;
    private WorldPoint template;

    @Before
    public void setUp()
    {
        client = mock(Client.class);
        me = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(me);
        when(client.isInInstancedRegion()).thenAnswer(i -> instanced);
        when(me.getWorldLocation()).thenAnswer(i -> standing);
        tracker = new InstanceLocationTracker(client)
        {
            @Override
            WorldPoint templatePoint(Player p)
            {
                return template;
            }
        };
    }

    /** What PlayerLocationSnapshot.capture reports inside an instance: instance-space coordinates. */
    private static PlayerLocationSnapshot instanceCapture()
    {
        return new PlayerLocationSnapshot(0, 7000, 8000, 0, 0, 0, 875, 1000, null, null, true, 330, 123L);
    }

    @Test
    public void outsideAnInstanceTheLocationIsUnchanged()
    {
        standing = CERBERUS_ENTRANCE;
        tracker.onGameTick();
        PlayerLocationSnapshot raw = new PlayerLocationSnapshot(0, 2873, 9847, 11417, 57, 55, 359, 1230,
                null, null, false, 330, 1L);
        assertSame(raw, tracker.resolve(raw));
        assertNull(tracker.resolve(null));
        PlayerLocationSnapshot decoy = PlayerLocationSnapshot.privacyDecoy();
        assertSame("private players keep the decoy", decoy, tracker.resolve(decoy));
    }

    @Test
    public void cerberusShowsTheEntranceAndTheBoss()
    {
        standing = CERBERUS_ENTRANCE;
        tracker.onGameTick();
        instanced = true;
        standing = new WorldPoint(7000, 8000, 0);
        template = CERBERUS_LAIR;
        tracker.onGameTick();

        PlayerLocationSnapshot loc = tracker.resolve(instanceCapture());
        assertEquals(2873, loc.getWorldX());
        assertEquals(9847, loc.getWorldY());
        assertEquals(CERBERUS_ENTRANCE.getRegionID(), loc.getRegionId());
        assertTrue(loc.isInstanced());
        assertEquals("Cerberus", loc.getInstanceName());
        assertEquals("Cerberus", loc.getAreaName());
        assertEquals(330, loc.getWorld());

        JsonObject json = loc.toJson();
        assertEquals("Cerberus", json.get("instance_name").getAsString());
        assertEquals("instance_entrance", json.get("location_source").getAsString());
        assertTrue(json.get("instanced").getAsBoolean());

        // Still the entrance after wandering around inside.
        tracker.onGameTick();
        assertEquals(2873, tracker.resolve(instanceCapture()).getWorldX());
    }

    @Test
    public void entranceIsKnownEvenBeforeTheFirstTickInside()
    {
        standing = CERBERUS_ENTRANCE;
        tracker.onGameTick();
        instanced = true;               // a kill/XP event fires before our tick handler
        template = CERBERUS_LAIR;
        assertEquals(2873, tracker.resolve(instanceCapture()).getWorldX());
    }

    @Test
    public void withoutAnEntranceTheTemplatePositionIsUsed()
    {
        instanced = true;               // logged in inside the instance
        template = CERBERUS_LAIR;
        tracker.onGameTick();
        PlayerLocationSnapshot loc = tracker.resolve(instanceCapture());
        assertEquals(1240, loc.getWorldX());
        assertFalse(loc.isInstanceEntrance());
        assertEquals("instance_template", loc.toJson().get("location_source").getAsString());
    }

    @Test
    public void unknownInstanceUsesTheNpcBeingFought()
    {
        standing = CERBERUS_ENTRANCE;
        tracker.onGameTick();
        instanced = true;
        template = UNKNOWN_TEMPLATE;
        NPC npc = mock(NPC.class);
        when(npc.getName()).thenReturn("<col=ffff00>Sarachnis</col>");
        when(npc.getCombatLevel()).thenReturn(318);
        when(me.getInteracting()).thenReturn(npc);
        tracker.onGameTick();
        when(me.getInteracting()).thenReturn(null);
        tracker.onGameTick();
        assertEquals("remembered after the fight", "Sarachnis", tracker.resolve(instanceCapture()).getInstanceName());

        // Leaving and entering a new instance forgets it.
        instanced = false;
        tracker.onGameTick();
        instanced = true;
        tracker.onGameTick();
        PlayerLocationSnapshot loc = tracker.resolve(instanceCapture());
        assertNull(loc.getInstanceName());
        assertTrue(loc.toJson().get("instance_name").isJsonNull());
    }

    @Test
    public void chambersOfXericByItsVarbit()
    {
        instanced = true;
        template = UNKNOWN_TEMPLATE;
        when(client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(1);
        tracker.onGameTick();
        assertEquals("Chambers of Xeric", tracker.resolve(instanceCapture()).getInstanceName());
    }

    @Test
    public void logoutForgetsTheEntrance()
    {
        standing = CERBERUS_ENTRANCE;
        tracker.onGameTick();
        tracker.reset();
        instanced = true;
        template = CERBERUS_LAIR;
        tracker.onGameTick();
        assertEquals(1240, tracker.resolve(instanceCapture()).getWorldX());
    }

    @Test
    public void tableCoversTheRaidRooms()
    {
        assertEquals("Theatre of Blood: Verzik Vitur", InstanceLocationTracker.AREAS.get(12611));
        assertEquals("Theatre of Blood: Verzik Vitur", InstanceLocationTracker.AREAS.get(12867));
        assertEquals("Tombs of Amascut: Wardens", InstanceLocationTracker.AREAS.get(15696));
        assertEquals("Vorkath", InstanceLocationTracker.AREAS.get(9023));
        assertEquals("Corporeal Beast", InstanceLocationTracker.AREAS.get(11844));
        assertEquals("Thermonuclear smoke devil", InstanceLocationTracker.AREAS.get(9619));
        assertEquals("Skotizo", InstanceLocationTracker.AREAS.get(9048));
        assertEquals(4883, CERBERUS_LAIR.getRegionID());
    }

    @Test
    public void anEmptyTemplateChunkIsNotPlottedAtTheOrigin()
    {
        instanced = true;
        standing = new WorldPoint(7000, 8000, 0);
        template = new WorldPoint(3, 5, 0);
        tracker.onGameTick();
        assertNull(tracker.resolve(instanceCapture()));
    }

    @Test
    public void anEmptyTemplateDoesNotDiscardAKnownEntrance()
    {
        standing = CERBERUS_ENTRANCE;
        tracker.onGameTick();
        instanced = true;
        standing = new WorldPoint(7000, 8000, 0);
        template = new WorldPoint(3, 5, 0);
        tracker.onGameTick();
        PlayerLocationSnapshot loc = tracker.resolve(instanceCapture());
        assertEquals(2873, loc.getWorldX());
        assertEquals("instance_entrance", loc.toJson().get("location_source").getAsString());
    }
}
