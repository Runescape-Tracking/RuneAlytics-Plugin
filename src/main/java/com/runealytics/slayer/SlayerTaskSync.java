package com.runealytics.slayer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runealytics.CurrentPlayerIdentityService;
import com.runealytics.RuneAlyticsState;
import com.runealytics.RunealyticsConfig;
import com.runealytics.progress.ProgressStore;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports the player's Slayer task to {@code POST /api/plugin/slayer-task}
 * (config "Share Slayer tasks"). Game-side work runs on the client thread;
 * file and network work never does. Unsent events and the last known task are
 * kept per account in the plugin data folder, so they survive a restart.
 */
@Slf4j
@Singleton
public class SlayerTaskSync
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** Saved per account: the task the server last heard about, and unsent events. */
    static final class Saved
    {
        SlayerTaskTracker.Task task;
        List<SlayerTaskEvent> events;
    }

    private final Client client;
    private final RunealyticsConfig config;
    private final RuneAlyticsState state;
    private final OkHttpClient http;
    private final Gson gson;
    private final ScheduledExecutorService executor;
    private final Random random = new Random();

    private final SlayerTaskTracker tracker = new SlayerTaskTracker(this::enqueue);
    private volatile SlayerEventOutbox outbox = new SlayerEventOutbox();
    private ProgressStore store;
    /** Account the tracker / outbox belong to (normalized RSN); client thread. */
    private String account;
    private final AtomicReference<Object[]> loaded = new AtomicReference<>();
    private boolean loading;
    private boolean loginSync;
    private boolean varsDirty;
    private boolean saveDirty;
    private String lastDialog;

    @Inject
    public SlayerTaskSync(Client client, RunealyticsConfig config, RuneAlyticsState state,
                          OkHttpClient http, Gson gson, ScheduledExecutorService executor)
    {
        this.client = client;
        this.config = config;
        this.state = state;
        this.http = http;
        this.gson = gson;
        this.executor = executor;
    }

    /** Plugin startUp; {@code store} may be null (nothing is kept across restarts then). */
    public void start(ProgressStore store)
    {
        this.store = store;
        account = null;
    }

    /** Plugin shutDown / logout: send what's pending and save. Client thread. */
    public void onLoggedOut()
    {
        if (account == null) return;
        tracker.flush(System.currentTimeMillis());
        save();
        pump();
        account = null;
    }

    public void onVarChanged(int varpId, int varbitId)
    {
        if (SlayerTaskReader.isTaskVar(varpId, varbitId)) varsDirty = true;
    }

    public void onChatMessage(ChatMessageType type, String message)
    {
        if (account == null || !config.shareSlayerTasks()) return;
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM) return;
        tracker.onSignal(SlayerChatParser.parseChat(message), null, System.currentTimeMillis());
    }

    /** "Share Slayer tasks" changed: off drops anything unsent; on catches the server up. */
    public void onShareToggled(boolean enabled)
    {
        if (!enabled)
        {
            outbox.clear();
            saveDirty = true;
        }
        else
        {
            loginSync = true;
        }
    }

    /** Game tick (client thread). */
    public void onGameTick()
    {
        if (!config.shareSlayerTasks()) return;
        Player me = client.getLocalPlayer();
        String name = me != null ? CurrentPlayerIdentityService.normalizeUsername(me.getName()) : null;
        if (name == null) return;
        if (!name.equals(account) && !switchAccount(name)) return;

        long now = System.currentTimeMillis();
        readDialog(now);
        if (loginSync || varsDirty)
        {
            SlayerTaskTracker.Task t = SlayerTaskReader.read(client);
            if (t != null)
            {
                if (loginSync) tracker.onLogin(t, now);
                else tracker.observe(t, now);
                loginSync = false;
                varsDirty = false;
                saveDirty = true;
            }
        }
        tracker.tick(now);
        if (saveDirty) save();
        pump();
    }

    /** Loads the account's saved state off the client thread; true once it's in place. */
    private boolean switchAccount(String name)
    {
        Object[] got = loaded.get();
        if (got != null && name.equals(got[0]))
        {
            loaded.set(null);
            loading = false;
            Saved saved = (Saved) got[1];
            SlayerEventOutbox box = new SlayerEventOutbox();
            if (saved != null && saved.events != null) box.addAll(saved.events);
            outbox = box;
            tracker.restore(saved != null ? saved.task : null);
            account = name;
            loginSync = true;
            return true;
        }
        if (!loading)
        {
            loading = true;
            final ProgressStore s = store;
            executor.execute(() ->
            {
                Saved saved = null;
                try
                {
                    String json = s != null ? s.read(fileName(name)) : null;
                    if (json != null) saved = gson.fromJson(json, Saved.class);
                }
                catch (IOException | RuntimeException e)
                {
                    log.debug("[Slayer] could not read saved state: {}", e.getMessage());
                }
                loaded.set(new Object[]{name, saved});
            });
        }
        return false;
    }

    private void readDialog(long now)
    {
        Widget text = client.getWidget(InterfaceID.ChatLeft.TEXT);
        String t = text != null && !text.isHidden() ? text.getText() : null;
        if (t == null || t.equals(lastDialog))
        {
            lastDialog = t;
            return;
        }
        lastDialog = t;
        SlayerChatParser.Signal s = SlayerChatParser.parseDialog(t);
        if (s == null) return;
        Widget speaker = client.getWidget(InterfaceID.ChatLeft.NAME);
        String master = speaker != null ? SlayerChatParser.clean(speaker.getText()) : null;
        tracker.onSignal(s, master == null || master.isEmpty() ? null : master, now);
    }

    private void enqueue(SlayerTaskEvent e)
    {
        if (!config.shareSlayerTasks()) return;
        log.debug("[Slayer] {} {} remaining={}", e.type, e.creatureName, e.remaining);
        outbox.add(e);
        saveDirty = true;
    }

    private void save()
    {
        saveDirty = false;
        final ProgressStore s = store;
        final String acct = account;
        if (s == null || acct == null) return;
        Saved saved = new Saved();
        saved.task = tracker.getTask();
        saved.events = outbox.snapshot();
        final String json = gson.toJson(saved);
        executor.execute(() ->
        {
            try
            {
                s.write(fileName(acct), json);
            }
            catch (IOException e)
            {
                log.debug("[Slayer] could not save state: {}", e.getMessage());
            }
        });
    }

    /** Sends the next batch when this account is the verified one. Any thread. */
    private void pump()
    {
        final SlayerEventOutbox box = outbox;
        String token = state.getVerificationCode();
        String verified = CurrentPlayerIdentityService.normalizeUsername(state.getVerifiedUsername());
        if (token == null || token.isEmpty() || verified == null || !verified.equals(account)) return;
        List<SlayerTaskEvent> batch = box.take(System.currentTimeMillis());
        if (batch == null) return;

        JsonObject body = new JsonObject();
        body.add("events", gson.toJsonTree(batch));
        Request request = new Request.Builder()
                .url(config.apiUrl() + "/plugin/slayer-task")
                .post(RequestBody.create(JSON, gson.toJson(body)))
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Accept", "application/json")
                .build();
        http.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("[Slayer] send failed: {}", e.getMessage());
                box.failed(System.currentTimeMillis(), random);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response r = response)
                {
                    int code = r.code();
                    if (r.isSuccessful())
                    {
                        box.acknowledged(ackedIds(r.body() != null ? r.body().string() : "", batch));
                    }
                    else if (code == 400 || code == 422)
                    {
                        log.debug("[Slayer] server rejected the batch (HTTP {}), dropping {} event(s)", code, batch.size());
                        box.dropInflight();
                    }
                    else
                    {
                        log.debug("[Slayer] send failed: HTTP {}", code);
                        box.failed(System.currentTimeMillis(), random);
                    }
                }
                catch (IOException | RuntimeException e)
                {
                    log.debug("[Slayer] send failed: {}", e.getMessage());
                    box.failed(System.currentTimeMillis(), random);
                }
            }
        });
    }

    /** UUIDs the server answered for; every event in the batch when the reply has no acks. */
    Set<String> ackedIds(String responseBody, List<SlayerTaskEvent> batch)
    {
        Set<String> ids = new HashSet<>();
        try
        {
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            if (json != null && json.has("acks") && json.get("acks").isJsonArray())
            {
                for (JsonElement a : json.getAsJsonArray("acks"))
                {
                    JsonObject ack = a.getAsJsonObject();
                    if (!ack.has("event_uuid")) continue;
                    if ("rejected".equals(ack.has("status") ? ack.get("status").getAsString() : null))
                    {
                        log.debug("[Slayer] event rejected: {}", ack);
                    }
                    ids.add(ack.get("event_uuid").getAsString());
                }
                return ids;
            }
        }
        catch (RuntimeException e)
        {
            log.debug("[Slayer] unreadable reply: {}", e.getMessage());
        }
        for (SlayerTaskEvent e : batch) ids.add(e.getEventUuid());
        return ids;
    }

    static String fileName(String account)
    {
        return "slayer-" + account.replaceAll("[^a-z0-9]", "_") + ".json";
    }
}
