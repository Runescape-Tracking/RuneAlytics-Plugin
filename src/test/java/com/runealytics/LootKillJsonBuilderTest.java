package com.runealytics;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Edge-case coverage for {@link LootKillJsonBuilder}, the shared schema builder
 * for every loot upload path.
 */
public class LootKillJsonBuilderTest
{
    private static LootStorageData.DropRecord drop(int id, String name, int qty)
    {
        LootStorageData.DropRecord d = new LootStorageData.DropRecord();
        d.setItemId(id);
        d.setItemName(name);
        d.setQuantity(qty);
        return d;
    }

    @Test
    public void buildDrop_nullName_usesSyntheticLabel()
    {
        JsonObject json = LootKillJsonBuilder.buildDrop(drop(4151, null, 1));
        assertEquals("Item #4151", json.get("item_name").getAsString());
    }

    @Test
    public void buildDrop_emptyName_usesSyntheticLabel()
    {
        JsonObject json = LootKillJsonBuilder.buildDrop(drop(995, "", 1));
        assertEquals("Item #995", json.get("item_name").getAsString());
    }

    @Test
    public void buildDrop_realName_isPreserved()
    {
        JsonObject json = LootKillJsonBuilder.buildDrop(drop(4151, "Abyssal whip", 1));
        assertEquals("Abyssal whip", json.get("item_name").getAsString());
    }

    @Test
    public void buildDrop_zeroQuantity_isClampedToOne()
    {
        assertEquals(1, LootKillJsonBuilder.buildDrop(drop(1, "x", 0)).get("quantity").getAsInt());
    }

    @Test
    public void buildDrop_negativeQuantity_isClampedToOne()
    {
        assertEquals(1, LootKillJsonBuilder.buildDrop(drop(1, "x", -7)).get("quantity").getAsInt());
    }

    @Test
    public void buildDrop_positiveQuantity_isPreserved()
    {
        assertEquals(15, LootKillJsonBuilder.buildDrop(drop(1, "x", 15)).get("quantity").getAsInt());
    }

    @Test
    public void buildDrop_copiesValueAndFlagFields()
    {
        LootStorageData.DropRecord d = drop(20997, "Twisted bow", 1);
        d.setGePrice(1_200_000);
        d.setHighAlch(720_000);
        d.setTotalValue(1_200_000L);
        d.setHidden(true);
        d.setPet(false);

        JsonObject json = LootKillJsonBuilder.buildDrop(d);
        assertEquals(1_200_000, json.get("ge_price").getAsInt());
        assertEquals(720_000, json.get("high_alch").getAsInt());
        assertEquals(1_200_000L, json.get("total_value").getAsLong());
        assertTrue(json.get("hidden").getAsBoolean());
        assertFalse(json.get("is_pet").getAsBoolean());
    }

    private static LootStorageData.KillRecord killWith(List<LootStorageData.DropRecord> drops)
    {
        LootStorageData.KillRecord k = new LootStorageData.KillRecord();
        k.setDrops(drops);
        return k;
    }

    @Test
    public void buildKill_nullDrops_yieldsEmptyAggregates()
    {
        LootStorageData.KillRecord k = killWith(null);
        JsonObject json = LootKillJsonBuilder.buildKill(k, "Zulrah", 2042, 0);

        assertEquals(0, json.getAsJsonArray("drops").size());
        assertEquals(0L, json.get("total_loot_value").getAsLong());
        assertEquals(0, json.get("drop_count").getAsInt());
    }

    @Test
    public void buildKill_sumsTotalValueAndCountsDrops()
    {
        LootStorageData.DropRecord a = drop(1, "a", 1);
        a.setTotalValue(500L);
        LootStorageData.DropRecord b = drop(2, "b", 1);
        b.setTotalValue(1_500L);

        JsonObject json = LootKillJsonBuilder.buildKill(
                killWith(new ArrayList<>(Arrays.asList(a, b))), "Vorkath", 8061, 3);

        assertEquals(2, json.getAsJsonArray("drops").size());
        assertEquals(2_000L, json.get("total_loot_value").getAsLong());
        assertEquals(2, json.get("drop_count").getAsInt());
        assertEquals(3, json.get("prestige").getAsInt());
    }

    @Test
    public void buildKill_nullModeAndType_defaultToRegularNormal()
    {
        LootStorageData.KillRecord k = killWith(new ArrayList<>());
        k.setGameMode(null);
        k.setAccountType(null);

        JsonObject json = LootKillJsonBuilder.buildKill(k, "npc", 1, 0);
        assertEquals("regular", json.get("game_mode").getAsString());
        assertEquals("normal", json.get("account_type").getAsString());
    }

    @Test
    public void buildKill_explicitModeAndType_arePreserved()
    {
        LootStorageData.KillRecord k = killWith(new ArrayList<>());
        k.setGameMode("leagues");
        k.setAccountType("ironman");

        JsonObject json = LootKillJsonBuilder.buildKill(k, "npc", 1, 0);
        assertEquals("leagues", json.get("game_mode").getAsString());
        assertEquals("ironman", json.get("account_type").getAsString());
    }

    @Test
    public void buildKill_nullLocation_omitsLocationField()
    {
        LootStorageData.KillRecord k = killWith(new ArrayList<>());
        assertFalse(LootKillJsonBuilder.buildKill(k, "npc", 1, 0).has("location"));
    }

    @Test
    public void buildKill_withLocation_includesLocationObject()
    {
        LootStorageData.KillRecord k = killWith(new ArrayList<>());
        k.setLocation(PlayerLocationSnapshot.privacyDecoy());

        JsonObject json = LootKillJsonBuilder.buildKill(k, "npc", 1, 0);
        assertTrue(json.has("location"));
        assertEquals(PlayerLocationSnapshot.PRIVACY_DECOY_WORLD,
                json.getAsJsonObject("location").get("world").getAsInt());
    }

    @Test
    public void buildBulkEnvelope_nullModeAndType_default()
    {
        JsonObject env = LootKillJsonBuilder.buildBulkEnvelope("Zezima", null, null, new ArrayList<>());
        assertEquals("Zezima", env.get("username").getAsString());
        assertEquals("regular", env.get("game_mode").getAsString());
        assertEquals("normal", env.get("account_type").getAsString());
        assertEquals(0, env.getAsJsonArray("kills").size());
    }

    @Test
    public void buildBulkEnvelope_wrapsAllKills()
    {
        List<JsonObject> kills = new ArrayList<>();
        kills.add(new JsonObject());
        kills.add(new JsonObject());

        JsonObject env = LootKillJsonBuilder.buildBulkEnvelope("p", "ironman", "hardcore_ironman", kills);
        assertEquals(2, env.getAsJsonArray("kills").size());
        assertEquals("ironman", env.get("game_mode").getAsString());
        assertEquals("hardcore_ironman", env.get("account_type").getAsString());
    }
}
