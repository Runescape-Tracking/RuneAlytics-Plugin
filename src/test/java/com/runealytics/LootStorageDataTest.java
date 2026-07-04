package com.runealytics;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Schema-stability tests for the persisted loot model: snake_case
 * {@code @SerializedName} mapping, long-valued totals, boolean flags, and the
 * default values applied to records loaded from older files.
 */
public class LootStorageDataTest
{
    private final Gson gson = new Gson();

    private static LootStorageData.DropRecord drop(int id, String name, int qty, long total, boolean pet)
    {
        LootStorageData.DropRecord d = new LootStorageData.DropRecord();
        d.setItemId(id);
        d.setItemName(name);
        d.setQuantity(qty);
        d.setTotalValue(total);
        d.setPet(pet);
        return d;
    }

    @Test
    public void roundTrip_preservesFieldsAndLongTotals()
    {
        LootStorageData data = new LootStorageData();
        data.setUsername("Zezima");
        data.setLastSyncTimestamp(1234567890123L);

        LootStorageData.BossKillData boss = new LootStorageData.BossKillData();
        boss.setNpcName("Zulrah");
        boss.setNpcId(2042);
        boss.setKillCount(3);
        boss.setPrestige(1);
        boss.setTotalLootValue(9_000_000_000L); // exceeds Integer.MAX_VALUE

        LootStorageData.KillRecord kill = new LootStorageData.KillRecord();
        kill.setKillNumber(3);
        kill.setWorld(330);
        List<LootStorageData.DropRecord> drops = new ArrayList<>();
        drops.add(drop(4151, "Abyssal whip", 1, 3_000_000L, false));
        drops.add(drop(13655, "Tanzanite mutagen", 1, 5_000_000L, true));
        kill.setDrops(drops);
        boss.getKills().add(kill);
        data.getBossKills().put("Zulrah", boss);

        LootStorageData restored = gson.fromJson(gson.toJson(data), LootStorageData.class);

        assertEquals("Zezima", restored.getUsername());
        assertEquals(1234567890123L, restored.getLastSyncTimestamp());
        LootStorageData.BossKillData rBoss = restored.getBossKills().get("Zulrah");
        assertEquals(2042, rBoss.getNpcId());
        assertEquals(9_000_000_000L, rBoss.getTotalLootValue());
        assertEquals(2, rBoss.getKills().get(0).getDrops().size());
        assertTrue(rBoss.getKills().get(0).getDrops().get(1).isPet());
        assertFalse(rBoss.getKills().get(0).getDrops().get(0).isPet());
    }

    @Test
    public void serialization_usesSnakeCaseKeys()
    {
        LootStorageData data = new LootStorageData();
        LootStorageData.BossKillData boss = new LootStorageData.BossKillData();
        boss.setNpcName("Zulrah");
        LootStorageData.KillRecord kill = new LootStorageData.KillRecord();
        kill.getDrops().add(drop(995, "Coins", 100, 100L, false));
        boss.getKills().add(kill);
        data.getBossKills().put("Zulrah", boss);

        String json = gson.toJson(data);
        assertTrue(json.contains("\"boss_kills\""));
        assertTrue(json.contains("\"kill_count\""));
        assertTrue(json.contains("\"total_value\""));
        assertTrue(json.contains("\"is_pet\""));
        assertTrue(json.contains("\"high_alch\""));
        assertTrue(json.contains("\"game_mode\""));
        assertTrue(json.contains("\"account_type\""));
    }

    @Test
    public void deserialization_appliesDefaultsForMissingModeFields()
    {
        String legacy = "{\"boss_kills\":{\"Zulrah\":{\"kills\":[{\"item_id\":1,"
                + "\"drops\":[{\"item_id\":1,\"quantity\":1}]}]}}}";
        LootStorageData data = gson.fromJson(legacy, LootStorageData.class);

        LootStorageData.KillRecord kill = data.getBossKills().get("Zulrah").getKills().get(0);
        assertEquals("regular", kill.getGameMode());
        assertEquals("normal", kill.getAccountType());
    }

    @Test
    public void defaultCollections_areInitializedNotNull()
    {
        LootStorageData fresh = new LootStorageData();
        assertTrue(fresh.getBossKills().isEmpty());
        assertTrue(fresh.getHiddenBosses().isEmpty());
        assertTrue(fresh.getHiddenDropsByBoss().isEmpty());
    }
}
