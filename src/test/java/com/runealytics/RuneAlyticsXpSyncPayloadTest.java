package com.runealytics;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wire-contract tests for the XP session sync payload: snake_case keys, mode
 * defaults, conditional profile_id, and the per-skill array shape expected by
 * POST /api/plugin/xp/session.
 */
public class RuneAlyticsXpSyncPayloadTest
{
    @Test
    public void toJson_emitsAllTopLevelFieldsAndSkillArray()
    {
        RuneAlyticsXpSyncPayload.SkillEntry mining =
                new RuneAlyticsXpSyncPayload.SkillEntry("mining", 12_000, 30_000, 70, 755_000);
        RuneAlyticsXpSyncPayload p = new RuneAlyticsXpSyncPayload(
                "Zezima", "prof123", "regular", "normal",
                1_720_000_000L, 3_600L, 45_000L, true, Collections.singletonList(mining));

        JsonObject json = p.toJson();
        assertEquals("Zezima", json.get("username").getAsString());
        assertEquals(1_720_000_000L, json.get("session_start").getAsLong());
        assertEquals(3_600L, json.get("duration_sec").getAsLong());
        assertEquals(45_000L, json.get("total_xp").getAsLong());
        assertTrue(json.get("ended").getAsBoolean());
        assertEquals("regular", json.get("game_mode").getAsString());
        assertEquals("normal", json.get("account_type").getAsString());
        assertEquals("prof123", json.get("profile_id").getAsString());

        assertEquals(1, json.getAsJsonArray("skills").size());
        JsonObject s = json.getAsJsonArray("skills").get(0).getAsJsonObject();
        assertEquals("mining", s.get("skill").getAsString());
        assertEquals(12_000L, s.get("xp_gained").getAsLong());
        assertEquals(30_000L, s.get("xp_per_hour").getAsLong());
        assertEquals(70, s.get("level").getAsInt());
        assertEquals(755_000L, s.get("current_xp").getAsLong());
    }

    @Test
    public void toJson_appliesModeDefaultsWhenNull()
    {
        RuneAlyticsXpSyncPayload p = new RuneAlyticsXpSyncPayload(
                "A", null, null, null, 1L, 1L, 1L, false, Collections.emptyList());

        JsonObject json = p.toJson();
        assertEquals("regular", json.get("game_mode").getAsString());
        assertEquals("normal", json.get("account_type").getAsString());
    }

    @Test
    public void toJson_omitsProfileIdWhenNullOrEmpty()
    {
        JsonObject nullProfile = new RuneAlyticsXpSyncPayload(
                "A", null, "regular", "normal", 1L, 1L, 1L, false,
                Collections.emptyList()).toJson();
        assertFalse(nullProfile.has("profile_id"));

        JsonObject emptyProfile = new RuneAlyticsXpSyncPayload(
                "A", "", "regular", "normal", 1L, 1L, 1L, false,
                Collections.emptyList()).toJson();
        assertFalse(emptyProfile.has("profile_id"));
    }

    @Test
    public void toJson_serializesMultipleSkillsInOrder()
    {
        RuneAlyticsXpSyncPayload p = new RuneAlyticsXpSyncPayload(
                "A", null, "regular", "normal", 1L, 1L, 3L, true,
                Arrays.asList(
                        new RuneAlyticsXpSyncPayload.SkillEntry("mining", 1, 1, 1, 1),
                        new RuneAlyticsXpSyncPayload.SkillEntry("fishing", 2, 2, 2, 2)));

        assertEquals(2, p.toJson().getAsJsonArray("skills").size());
        assertEquals("mining",
                p.toJson().getAsJsonArray("skills").get(0).getAsJsonObject().get("skill").getAsString());
        assertEquals("fishing",
                p.toJson().getAsJsonArray("skills").get(1).getAsJsonObject().get("skill").getAsString());
    }
}
