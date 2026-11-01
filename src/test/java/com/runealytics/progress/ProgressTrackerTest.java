package com.runealytics.progress;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.runealytics.CurrentPlayerIdentityService;
import com.runealytics.RuneAlyticsState;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.ScriptID;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link ProgressTracker} through login → readiness → historical snapshot →
 * live completion → delta, with a fake client, an inline executor and a mock website.
 */
public class ProgressTrackerTest
{
    private final Gson gson = new Gson();
    private MockWebServer server;
    private Client client;
    private RuneAlyticsState state;
    private ProgressTracker tracker;
    private final List<Runnable> scheduled = new ArrayList<>();
    private final Map<String, String> files = new HashMap<>();

    private final Map<Integer, Integer> questResults = new HashMap<>();
    private final Map<Integer, Integer> varps = new HashMap<>();
    private long accountHash = 123L;
    private String playerName = "Zezima";

    @Before
    public void setUp() throws Exception
    {
        server = new MockWebServer();
        server.start();

        client = mock(Client.class);
        int[] stack = new int[1];
        doAnswer(inv ->
        {
            Object[] args = inv.getArguments();
            assertEquals(ScriptID.QUEST_STATUS_GET, ((Integer) args[0]).intValue());
            stack[0] = questResults.getOrDefault((Integer) args[1], 1);
            return null;
        }).when(client).runScript(any(Object[].class));
        when(client.getIntStack()).thenReturn(stack);
        when(client.getVarbitValue(anyInt())).thenReturn(0);
        when(client.getVarpValue(anyInt())).thenAnswer(inv -> varps.getOrDefault((Integer) inv.getArgument(0), 0));
        when(client.getAccountHash()).thenAnswer(inv -> accountHash);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        Player player = mock(Player.class);
        when(player.getName()).thenAnswer(inv -> playerName);
        when(client.getLocalPlayer()).thenReturn(player);

        ClientThread clientThread = mock(ClientThread.class);
        doAnswer(inv ->
        {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(clientThread).invokeLater(any(Runnable.class));

        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        doAnswer(inv ->
        {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        doAnswer(inv ->
        {
            scheduled.add(inv.getArgument(0));
            return mock(ScheduledFuture.class);
        }).when(executor).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        state = new RuneAlyticsState();
        verifyAs("zezima", "token-a");

        tracker = new ProgressTracker(client, clientThread, executor, gson, state,
                new CurrentPlayerIdentityService(client, state), new OkHttpClient());
    }

    @After
    public void tearDown() throws Exception
    {
        server.shutdown();
    }

    private void verifyAs(String name, String token)
    {
        state.setVerified(true);
        state.setVerifiedUsername(name);
        state.setVerificationCode(token);
        state.setLoggedIn(true);
    }

    private void start()
    {
        ProgressStore store = new ProgressStore()
        {
            @Override
            public String read(String fileName)
            {
                return files.get(fileName);
            }

            @Override
            public void write(String fileName, String json)
            {
                files.put(fileName, json);
            }
        };
        String base = server.url("/api").toString();
        tracker.start(store, () -> base, "rl_testinstall", () -> "regular", () -> true, () -> true);
    }

    private void ticks(int n)
    {
        for (int i = 0; i < n; i++) tracker.onGameTick();
    }

    private void runScheduled()
    {
        while (!scheduled.isEmpty())
        {
            List<Runnable> now = new ArrayList<>(scheduled);
            scheduled.clear();
            for (Runnable r : now) r.run();
        }
    }

    private JsonObject takeBody(RecordedRequest req)
    {
        return gson.fromJson(req.getBody().readUtf8(), JsonObject.class);
    }

    @Test
    public void historicalSnapshotThenLiveCompletion() throws Exception
    {
        questResults.put(Quest.COOKS_ASSISTANT.getId(), 2);   // already done before install
        questResults.put(Quest.DRAGON_SLAYER_I.getId(), 0);   // in progress
        varps.put(VarPlayerID.QP, 1);

        start();
        ticks(3); // readiness gate → first scan
        server.enqueue(new MockResponse().setResponseCode(200));
        runScheduled();

        RecordedRequest snapReq = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("Bearer token-a", snapReq.getHeader("Authorization"));
        JsonObject snap = takeBody(snapReq);
        assertEquals("snapshot", snap.get("kind").getAsString());
        assertEquals("zezima", snap.get("username").getAsString());
        assertEquals("complete", snap.getAsJsonObject("coverage").get("quests").getAsString());
        assertEquals(Quest.values().length, snap.getAsJsonArray("quests").size());
        assertEquals("no fake 'just completed' events on import", 0, snap.getAsJsonArray("transitions").size());

        // Dragon Slayer I is completed while the plugin is running.
        questResults.put(Quest.DRAGON_SLAYER_I.getId(), 2);
        varps.put(VarPlayerID.QP, 3);
        tracker.onVarChanged();
        ticks(ProgressTracker.MIN_SCAN_GAP_TICKS + 1);
        server.enqueue(new MockResponse().setResponseCode(200));
        runScheduled();

        JsonObject delta = takeBody(server.takeRequest(5, TimeUnit.SECONDS));
        assertEquals("delta", delta.get("kind").getAsString());
        assertEquals(2, delta.get("sequence").getAsLong());
        JsonArray quests = delta.getAsJsonArray("quests");
        assertEquals(1, quests.size());
        assertEquals("COMPLETED", quests.get(0).getAsJsonObject().get("state").getAsString());
        JsonObject t = delta.getAsJsonArray("transitions").get(0).getAsJsonObject();
        assertEquals("quest", t.get("category").getAsString());
        assertEquals(String.valueOf(Quest.DRAGON_SLAYER_I.getId()), t.get("key").getAsString());
        assertEquals("IN_PROGRESS", t.get("from").getAsString());
        assertEquals("COMPLETED", t.get("to").getAsString());
        assertEquals("live", t.get("detection").getAsString());

        assertTrue("progress is persisted locally", files.keySet().iterator().next().startsWith("progress-7b-standard"));
    }

    @Test
    public void loadingZerosAreNotUploaded() throws Exception
    {
        varps.put(VarPlayerID.QP, 50); // quests "not loaded": 0 finished but 50 QP
        start();
        ticks(ProgressReadinessGate.MAX_TICKS + 1);
        server.enqueue(new MockResponse().setResponseCode(200));
        runScheduled();

        JsonObject snap = takeBody(server.takeRequest(5, TimeUnit.SECONDS));
        assertEquals("unavailable", snap.getAsJsonObject("coverage").get("quests").getAsString());
        assertNull(snap.get("quests"));
    }

    @Test
    public void unlinkedAccountIsNeverUploaded() throws Exception
    {
        playerName = "SomeoneElse"; // RuneAlytics is linked to "zezima"
        start();
        ticks(3);
        runScheduled();
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void accountSwitchKeepsEachAccountsDataWithItsOwnToken() throws Exception
    {
        questResults.put(Quest.DRAGON_SLAYER_I.getId(), 0);
        start();
        ticks(3);
        server.enqueue(new MockResponse().setResponseCode(200));
        runScheduled();
        server.takeRequest(5, TimeUnit.SECONDS); // account A snapshot

        // A completes a quest, then logs out before the delta is uploaded.
        questResults.put(Quest.DRAGON_SLAYER_I.getId(), 2);
        tracker.onVarChanged();
        ticks(ProgressTracker.MIN_SCAN_GAP_TICKS + 1);
        tracker.onLogout();

        // Account B logs in and is verified with its own token.
        accountHash = 456L;
        playerName = "Other";
        questResults.clear();
        verifyAs("other", "token-b");
        tracker.onLoggedIn();
        ticks(3);

        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));
        runScheduled();

        Map<String, JsonObject> byToken = new HashMap<>();
        for (int i = 0; i < 2; i++)
        {
            RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
            byToken.put(r.getHeader("Authorization"), takeBody(r));
        }
        JsonObject a = byToken.get("Bearer token-a");
        JsonObject b = byToken.get("Bearer token-b");
        assertEquals("zezima", a.get("username").getAsString());
        assertEquals("delta", a.get("kind").getAsString());
        assertEquals(1, a.getAsJsonArray("transitions").size());
        assertEquals("other", b.get("username").getAsString());
        assertEquals("snapshot", b.get("kind").getAsString());
        assertEquals(0, b.getAsJsonArray("transitions").size());
    }

    @Test
    public void hopRearmsReadinessWithoutLosingBaseline() throws Exception
    {
        questResults.put(Quest.DRAGON_SLAYER_I.getId(), 0);
        start();
        ticks(3);
        server.enqueue(new MockResponse().setResponseCode(200));
        runScheduled();
        server.takeRequest(5, TimeUnit.SECONDS);

        tracker.onHopping();
        questResults.put(Quest.DRAGON_SLAYER_I.getId(), 2);
        tracker.onLoggedIn();
        ticks(3);
        server.enqueue(new MockResponse().setResponseCode(200));
        runScheduled();

        JsonObject delta = takeBody(server.takeRequest(5, TimeUnit.SECONDS));
        assertEquals("delta", delta.get("kind").getAsString());
        assertEquals(1, delta.getAsJsonArray("transitions").size());
    }

    @Test
    public void restartDoesNotReimportAsNewCompletions() throws Exception
    {
        questResults.put(Quest.COOKS_ASSISTANT.getId(), 2);
        start();
        ticks(3);
        server.enqueue(new MockResponse().setResponseCode(200));
        runScheduled();
        server.takeRequest(5, TimeUnit.SECONDS);

        tracker.stop();
        tracker.start(new ProgressStore()
        {
            @Override
            public String read(String fileName)
            {
                return files.get(fileName);
            }

            @Override
            public void write(String fileName, String json)
            {
                files.put(fileName, json);
            }
        }, () -> server.url("/api").toString(), "rl_testinstall", () -> "regular", () -> true, () -> true);
        ticks(3);
        server.enqueue(new MockResponse().setResponseCode(200));
        runScheduled();

        JsonObject snap = takeBody(server.takeRequest(5, TimeUnit.SECONDS));
        assertEquals("snapshot", snap.get("kind").getAsString());
        assertEquals("sequence continues from the persisted file", 2, snap.get("sequence").getAsLong());
        assertEquals(0, snap.getAsJsonArray("transitions").size());
    }
}
