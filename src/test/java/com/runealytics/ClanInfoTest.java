package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Snapshot / JSON contract for clan state. ClanManager itself is RuneLite-event
 * driven; the serialisable model is the part that can be pinned without a client.
 */
public class ClanInfoTest
{
    @Test
    public void upsertMember_addsThenUpdatesLastSeenWithoutDuplicating()
    {
        ClanInfo clan = new ClanInfo("Legends");
        clan.upsertMember("Zezima", true);
        clan.upsertMember("Zezima", true);

        assertEquals(1, clan.getMemberCount());
        assertTrue(clan.hasMember("Zezima"));
        assertTrue(clan.getMembers().get("Zezima").isTracked());
    }

    @Test
    public void removeMember_dropsUsernameAndMarksDirty()
    {
        ClanInfo clan = new ClanInfo("Legends");
        clan.upsertMember("Alice", false);
        // lastUpdateAt is second-resolution; rewind sync so the next mutation
        // is observably later even when both calls land in the same second.
        clan.setLastSyncAt(Math.max(0, clan.getLastUpdateAt() - 1));
        assertTrue(clan.isDirty());

        clan.removeMember("Alice");
        assertFalse(clan.hasMember("Alice"));
        assertEquals(0, clan.getMemberCount());
        assertTrue(clan.isDirty());
    }

    @Test
    public void toJson_omitsMemberListAndUsesSnakeCase()
    {
        ClanInfo clan = new ClanInfo("Legends");
        clan.setClanTag("<leg>");
        clan.setPlayerRank("captain");
        clan.setClanId(42);
        clan.upsertMember("Alice", true);
        clan.upsertMember("Bob", false);

        JsonObject json = clan.toJson();
        assertEquals("Legends", json.get("clan_name").getAsString());
        assertEquals("<leg>", json.get("clan_tag").getAsString());
        assertEquals("captain", json.get("player_rank").getAsString());
        assertEquals(2, json.get("member_count").getAsInt());
        assertEquals(42, json.get("clan_id").getAsInt());
        assertFalse(json.has("members"));
    }

    @Test
    public void membersToJsonArray_includesEssentialFields()
    {
        ClanInfo clan = new ClanInfo("Legends");
        clan.upsertMember("Alice", true);
        clan.getMembers().get("Alice").setRank("general");

        JsonArray arr = clan.membersToJsonArray();
        assertEquals(1, arr.size());
        JsonObject member = arr.get(0).getAsJsonObject();
        assertEquals("Alice", member.get("username").getAsString());
        assertEquals("general", member.get("rank").getAsString());
        assertTrue(member.get("is_tracked").getAsBoolean());
        assertTrue(member.has("joined_at"));
        assertTrue(member.has("last_seen"));
    }
}
