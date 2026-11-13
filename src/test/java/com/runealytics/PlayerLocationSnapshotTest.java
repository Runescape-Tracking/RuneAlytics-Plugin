package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Coverage for {@link PlayerLocationSnapshot}: the privacy decoy, the private
 * visibility substitution, null-safe capture, and JSON wire shape.
 */
public class PlayerLocationSnapshotTest
{
    @Test
    public void capture_nullClient_returnsNull()
    {
        assertNull(PlayerLocationSnapshot.capture(null));
    }

    @Test
    public void privacyDecoy_isTaggedGrandExchange()
    {
        PlayerLocationSnapshot decoy = PlayerLocationSnapshot.privacyDecoy();
        assertEquals(PlayerLocationSnapshot.PRIVACY_DECOY_WORLD, decoy.getWorld());
        assertEquals("Grand Exchange", decoy.getAreaName());
        assertEquals("Grand Exchange", decoy.getMapRegion());
        assertEquals(0, decoy.getPlane());
        assertFalse(decoy.isInstanced());
    }

    @Test
    public void captureRespectingPrivacy_private_returnsDecoyWithoutTouchingClient()
    {
        PlayerLocationSnapshot loc =
                PlayerLocationSnapshot.captureRespectingPrivacy(null, PrivacySetting.PRIVATE);
        assertEquals(PlayerLocationSnapshot.PRIVACY_DECOY_WORLD, loc.getWorld());
    }

    @Test
    public void toJson_emitsAllFieldsAndNullsUnresolvedNames()
    {
        PlayerLocationSnapshot snap = new PlayerLocationSnapshot(
                1, 3200, 3200,
                12850, 10, 20,
                400, 400,
                null, null,
                true, 302, 1_700_000_000L);

        JsonObject json = snap.toJson();
        assertEquals(1, json.get("plane").getAsInt());
        assertEquals(3200, json.get("world_x").getAsInt());
        assertEquals(12850, json.get("region_id").getAsInt());
        assertEquals(302, json.get("world").getAsInt());
        assertEquals(1_700_000_000L, json.get("timestamp").getAsLong());
        assertTrue(json.get("instanced").getAsBoolean());
        assertTrue(json.get("map_region").isJsonNull());
        assertTrue(json.get("area_name").isJsonNull());
        assertFalse("raw instance coordinates are not a template position", json.has("location_source"));
        assertFalse(json.has("instance_name"));
    }

    @Test
    public void toJson_keepsLegacyInstancedKillsUnlabelled()
    {
        PlayerLocationSnapshot legacy = new Gson().fromJson(
                "{\"instanced\":true,\"world_x\":7000,\"world_y\":8000,\"world\":330}",
                PlayerLocationSnapshot.class);
        JsonObject json = legacy.toJson();
        assertTrue(json.get("instanced").getAsBoolean());
        assertEquals(7000, json.get("world_x").getAsInt());
        assertFalse(json.has("location_source"));
    }

    @Test
    public void toJson_stillLabelsAnEntranceSavedBeforeLocationSourceExisted()
    {
        PlayerLocationSnapshot saved = new Gson().fromJson(
                "{\"instanced\":true,\"instance_entrance\":true,\"instance_name\":\"Cerberus\",\"world_x\":2873}",
                PlayerLocationSnapshot.class);
        JsonObject json = saved.toJson();
        assertEquals("instance_entrance", json.get("location_source").getAsString());
        assertEquals("Cerberus", json.get("instance_name").getAsString());
    }
}
