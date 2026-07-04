package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for the loot HTTP client: snapshot/history parsing, bulk-sync
 * orchestration (zero-loot skipping), sync-absolute + death-event envelopes,
 * and path-segment URL encoding — all driven against MockWebServer.
 */
public class LootTrackerApiClientTest
{
    private MockWebServer server;
    private RuneAlyticsState state;
    private ItemManager itemManager;
    private ClientThread clientThread;
    private LootTrackerApiClient client;

    @Before
    public void setUp() throws Exception
    {
        server = new MockWebServer();
        server.start();

        RunealyticsConfig config = mock(RunealyticsConfig.class);
        when(config.apiUrl()).thenReturn(server.url("/api").toString());
        when(config.syncTimeout()).thenReturn(5);

        state = new RuneAlyticsState();
        itemManager = mock(ItemManager.class);
        clientThread = mock(ClientThread.class);

        client = new LootTrackerApiClient(new OkHttpClient(), config, state,
                new Gson(), itemManager, clientThread);
    }

    @After
    public void tearDown() throws Exception
    {
        server.shutdown();
    }

    private RecordedRequest awaitRequest() throws InterruptedException
    {
        RecordedRequest req = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(req);
        return req;
    }

    private static LootStorageData.DropRecord drop(int id, int qty, int ge, int alch)
    {
        LootStorageData.DropRecord d = new LootStorageData.DropRecord();
        d.setItemId(id);
        d.setItemName("item" + id);
        d.setQuantity(qty);
        d.setGePrice(ge);
        d.setHighAlch(alch);
        d.setTotalValue((long) ge * qty);
        return d;
    }

    // ── fetchKillHistoryFromServer / parseHistory ────────────────────────────

    @Test
    public void fetchKillHistory_parsesBossesAndDrops() throws Exception
    {
        String body = "{\"kills\":[{\"boss_name\":\"Zulrah\",\"boss_id\":2042,"
                + "\"combat_level\":100,\"world\":330,\"kill_time\":1000,"
                + "\"drops\":[{\"item_id\":4151,\"item_name\":\"Whip\",\"quantity\":2,"
                + "\"ge_price\":50,\"high_alch\":40}]}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));

        Map<String, LootStorageData.BossKillData> result =
                client.fetchKillHistoryFromServer("Zezima");

        LootStorageData.BossKillData boss = result.get("Zulrah");
        assertNotNull(boss);
        assertEquals(1, boss.getKillCount());
        LootStorageData.KillRecord kill = boss.getKills().get(0);
        assertEquals(1_000_000L, kill.getTimestamp()); // seconds → ms
        assertEquals(2, kill.getDrops().get(0).getQuantity());
        assertEquals(100L, kill.getDrops().get(0).getTotalValue()); // 50 * 2
    }

    @Test
    public void fetchKillHistory_skipsMalformedAndMissingBossName() throws Exception
    {
        String body = "{\"kills\":[5,{\"combat_level\":10},"
                + "{\"boss_name\":\"Vorkath\",\"kill_time\":1,\"drops\":[]}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));

        Map<String, LootStorageData.BossKillData> result =
                client.fetchKillHistoryFromServer("Zezima");
        assertEquals(1, result.size());
        assertTrue(result.containsKey("Vorkath"));
    }

    @Test
    public void fetchKillHistory_nonSuccessOrInvalid_returnsEmpty() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));
        assertTrue(client.fetchKillHistoryFromServer("Zezima").isEmpty());

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"other\":1}"));
        assertTrue(client.fetchKillHistoryFromServer("Zezima").isEmpty());

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"kills\":\"nope\"}"));
        assertTrue(client.fetchKillHistoryFromServer("Zezima").isEmpty());
    }

    @Test
    public void fetchKillHistory_urlEncodesUsername() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"kills\":[]}"));
        client.fetchKillHistoryFromServer("Iron Man");

        RecordedRequest req = awaitRequest();
        assertTrue(req.getPath().endsWith("/loot/history/Iron%20Man"));
    }

    @Test
    public void fetchKillHistory_enrichesZeroPricedDropsOnClientThread() throws Exception
    {
        // Run the client-thread runnable inline.
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(clientThread).invoke(any(Runnable.class));

        when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(itemManager.getItemPrice(100)).thenReturn(500);
        ItemComposition comp = mock(ItemComposition.class);
        when(comp.getHaPrice()).thenReturn(40);
        when(itemManager.getItemComposition(100)).thenReturn(comp);

        String body = "{\"kills\":[{\"boss_name\":\"Zulrah\",\"kill_time\":1,"
                + "\"drops\":[{\"item_id\":100,\"item_name\":\"x\",\"quantity\":2,"
                + "\"ge_price\":0,\"high_alch\":0}]}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));

        LootStorageData.DropRecord d = client.fetchKillHistoryFromServer("Zezima")
                .get("Zulrah").getKills().get(0).getDrops().get(0);
        assertEquals(500, d.getGePrice());
        assertEquals(1000L, d.getTotalValue());
        assertEquals(40, d.getHighAlch());
    }

    // ── fetchLootSnapshot / parseSnapshot ────────────────────────────────────

    @Test
    public void fetchLootSnapshot_parsesSourcesAndItemKeys() throws Exception
    {
        String body = "{\"success\":true,\"username\":\"zezima\",\"sources\":["
                + "{\"source_key\":\"zulrah\",\"source_name\":\"Zulrah\",\"items\":["
                + "{\"item_id\":536,\"item_name\":\"Dragon bones\",\"quantity\":10},"
                + "{\"item_id\":0,\"item_name\":\"Clue\",\"quantity\":3}]}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));

        LootTrackerApiClient.LootSnapshot snap = client.fetchLootSnapshot("zezima");
        assertNotNull(snap);
        assertEquals("zezima", snap.username);
        LootTrackerApiClient.LootSnapshot.SourceData src = snap.sources.get("zulrah");
        assertEquals(Long.valueOf(10), src.itemTotals.get("id_536"));
        assertEquals(Long.valueOf(3), src.itemTotals.get("name_clue"));
        assertEquals(Integer.valueOf(536), snap.itemIdsByKey.get("id_536"));
        assertFalse(snap.itemIdsByKey.containsKey("name_clue"));
    }

    @Test
    public void fetchLootSnapshot_dedupsItemKeysByMax() throws Exception
    {
        String body = "{\"success\":true,\"username\":\"z\",\"sources\":["
                + "{\"source_key\":\"k\",\"source_name\":\"K\",\"items\":["
                + "{\"item_id\":536,\"quantity\":5},{\"item_id\":536,\"quantity\":10}]}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));

        LootTrackerApiClient.LootSnapshot snap = client.fetchLootSnapshot("z");
        assertEquals(Long.valueOf(10), snap.sources.get("k").itemTotals.get("id_536"));
    }

    @Test
    public void fetchLootSnapshot_successFalseOrMissing_returnsNull() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"success\":false}"));
        assertNull(client.fetchLootSnapshot("z"));

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        assertNull(client.fetchLootSnapshot("z"));
    }

    @Test
    public void fetchLootSnapshot_authError_returnsNull() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));
        assertNull(client.fetchLootSnapshot("z"));

        server.enqueue(new MockResponse().setResponseCode(403).setBody("{}"));
        assertNull(client.fetchLootSnapshot("z"));
    }

    @Test
    public void fetchLootSnapshot_noSources_returnsSnapshotWithUsernameOnly() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"username\":\"zez\"}"));
        LootTrackerApiClient.LootSnapshot snap = client.fetchLootSnapshot("zez");
        assertNotNull(snap);
        assertEquals("zez", snap.username);
        assertTrue(snap.sources.isEmpty());
    }

    @Test
    public void fetchLootSnapshot_sendsAuthHeaderWhenTokenPresent() throws Exception
    {
        state.setVerificationCode("TOK");
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"username\":\"z\"}"));
        client.fetchLootSnapshot("z");

        RecordedRequest req = awaitRequest();
        assertEquals("Bearer TOK", req.getHeader("Authorization"));
        assertTrue(req.getPath().contains("username=z"));
        assertTrue(req.getPath().contains("game=osrs"));
    }

    // ── bulkSyncKills ────────────────────────────────────────────────────────

    @Test
    public void bulkSyncKills_nullOrEmpty_returnsTrueWithoutHttp() throws Exception
    {
        assertTrue(client.bulkSyncKills("Zezima", null, null));
        assertTrue(client.bulkSyncKills("Zezima", new HashMap<>(), null));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void bulkSyncKills_allZeroLoot_marksSyncedWithoutHttp() throws Exception
    {
        LootStorageData.KillRecord zeroLoot = new LootStorageData.KillRecord();
        zeroLoot.setDrops(new ArrayList<>());
        Map<String, List<LootStorageData.KillRecord>> kills = new HashMap<>();
        kills.put("Zulrah", Collections.singletonList(zeroLoot));

        assertTrue(client.bulkSyncKills("Zezima", kills, null));
        assertTrue(zeroLoot.isSyncedToServer());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void bulkSyncKills_postsEnvelopeForKillsWithDrops() throws Exception
    {
        state.setVerificationCode("TOK");
        LootStorageData.KillRecord kill = new LootStorageData.KillRecord();
        kill.setKillNumber(1);
        kill.setDrops(new ArrayList<>(Arrays.asList(drop(4151, 1, 100, 40))));
        Map<String, List<LootStorageData.KillRecord>> kills = new HashMap<>();
        kills.put("Zulrah", Collections.singletonList(kill));

        LootStorageData.BossKillData boss = new LootStorageData.BossKillData();
        boss.setNpcId(2042);
        boss.setPrestige(1);
        Map<String, LootStorageData.BossKillData> lookup = new HashMap<>();
        lookup.put("Zulrah", boss);

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        assertTrue(client.bulkSyncKills("Zezima", kills, lookup));

        RecordedRequest req = awaitRequest();
        assertTrue(req.getPath().endsWith("/loot/bulk-sync"));
        assertEquals("Bearer TOK", req.getHeader("Authorization"));
    }

    // ── syncAbsolute ─────────────────────────────────────────────────────────

    @Test
    public void syncAbsolute_emptyOrNull_returnsTrueWithoutHttp() throws Exception
    {
        assertTrue(client.syncAbsolute("z", null));
        assertTrue(client.syncAbsolute("z", new ArrayList<>()));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void syncAbsolute_buildsMergeEnvelope() throws Exception
    {
        LootSyncMergeService.MergedItem item =
                new LootSyncMergeService.MergedItem(536, "Dragon bones", 10, "max");
        LootSyncMergeService.MergedSource src = new LootSyncMergeService.MergedSource(
                "zulrah", "Zulrah", "npc", Collections.singletonList(item), 5, "website");

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        assertTrue(client.syncAbsolute("Zezima", Collections.singletonList(src)));

        JsonObject sent = new Gson().fromJson(awaitRequest().getBody().readUtf8(), JsonObject.class);
        assertEquals("osrs", sent.get("game").getAsString());
        assertEquals("absolute_merge", sent.get("sync_mode").getAsString());
        JsonObject sourceObj = sent.getAsJsonArray("sources").get(0).getAsJsonObject();
        assertEquals("zulrah", sourceObj.get("source_key").getAsString());
        assertEquals(5, sourceObj.get("kill_count").getAsInt());
        assertEquals(536, sourceObj.getAsJsonArray("items").get(0).getAsJsonObject()
                .get("item_id").getAsInt());
    }

    // ── sendDeathEvent ───────────────────────────────────────────────────────

    @Test
    public void sendDeathEvent_includesLocationAndRecoveredItems() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        assertTrue(client.sendDeathEvent("Zezima", "death", 330, 12598,
                PlayerLocationSnapshot.privacyDecoy(),
                Collections.singletonList(drop(4151, 1, 100, 40))));

        JsonObject sent = new Gson().fromJson(awaitRequest().getBody().readUtf8(), JsonObject.class);
        assertEquals("death", sent.get("event_type").getAsString());
        assertTrue(sent.has("location"));
        assertEquals(3164, sent.getAsJsonObject("location").get("x").getAsInt());
        assertTrue(sent.getAsJsonObject("death_context").getAsJsonArray("items_recovered").size() > 0);
    }

    @Test
    public void sendDeathEvent_omitsLocationAndContextWhenAbsent() throws Exception
    {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        assertTrue(client.sendDeathEvent("Zezima", "recovery", 330, 0, null, null));

        JsonObject sent = new Gson().fromJson(awaitRequest().getBody().readUtf8(), JsonObject.class);
        assertFalse(sent.has("location"));
        assertFalse(sent.has("death_context"));
    }
}
