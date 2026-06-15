package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import java.util.EnumSet;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.runealytics.RuneAlyticsPanel.*;

@Slf4j
@PluginDescriptor(
        name = "RuneAlytics",
        description = "Advanced loot tracking and analytics",
        tags = {"loot", "tracking", "analytics"},
        enabledByDefault = true
)
public class RuneAlyticsPlugin extends Plugin
{
    // ═════════════════════════════════════════════════════════════════════════
    //  WIDGET GROUP-IDs
    //  All canonical values live in {@link RewardSources}.  The two widgets
    //  that need bespoke handling (Whisperer, Wintertodt) are aliased here for
    //  readability inside {@link #onWidgetLoaded}.
    // ═════════════════════════════════════════════════════════════════════════
    static final int WIDGET_WHISPERER  = RewardSources.WIDGET_WHISPERER;
    static final int WIDGET_WINTERTODT = RewardSources.WIDGET_WINTERTODT;
    static final int WIDGET_NIGHTMARE  = RewardSources.WIDGET_NIGHTMARE;
    static final int WIDGET_CLUE       = RewardSources.WIDGET_CLUE;

    // ── Timing ────────────────────────────────────────────────────────────────
    /** ms after a kill during which spawning ground items are attributed to it */
    private static final long GROUND_ITEM_WINDOW_MS      = 3_000;
    /** seconds before we clear lastKilledBoss to avoid stale attributions */
    private static final long BOSS_CLEAR_TIMEOUT_SECONDS = 30;

    // ─────────────────────────────────────────────────────────────────────────
    //  PICKPOCKET / THIEVING TRACKING CONSTANTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * How long (ms) after the most recent "Pickpocket" click we continue treating
     * inventory changes as belonging to that pickpocket action.
     */
    private static final long PICKPOCKET_WINDOW_MS = 1_800;
    /**
     * How long an open skilling-snapshot window stays "live" for inventory
     * diffs to be attributed to the skill that opened it.
     *
     * <p>Was 8 s, which was wide enough that <em>any</em> equipment swap,
     * bank withdrawal, or shop transaction within 8 s of a lamp / book / quest
     * XP reward would be falsely attributed to the rewarded skill (issue #3).</p>
     *
     * <p>1.5 s ≈ 2½ game ticks — wide enough to catch the inventory tick that
     * follows a real skilling action (logs/fish/ores arrive on the same or
     * next tick as the XP), but narrow enough that the player can't realistically
     * change gear in time after using a lamp.</p>
     */
    private static final long SKILLING_SESSION_MS  = 1_500;

    /**
     * Tick window after a known "Rub" / "Read" / "Use" lamp/book menu click
     * during which any XP gain skips the skilling-snapshot path entirely.
     * Game ticks rather than ms because the click → XP delay is tick-bound.
     */
    private static final int  LAMP_XP_SUPPRESS_TICKS = 5;

    /** Plugin tick at which the lamp-XP suppression window expires (0 = none). */
    private long lampXpSuppressUntilTick = 0L;

    private static final Set<Skill> SKILLING_TRACKED = java.util.EnumSet.of(
            Skill.WOODCUTTING, Skill.FISHING,  Skill.MINING,    Skill.FARMING,
            Skill.HUNTER,      Skill.HERBLORE, Skill.RUNECRAFT, Skill.FLETCHING,
            Skill.COOKING,     Skill.SMITHING, Skill.CRAFTING,  Skill.AGILITY
    );

    /**
     * Menu options that grant XP without producing a skilling drop. When any
     * of these click on a target whose name contains "lamp", "book", "scroll",
     * "tome", or "genie", we suppress the skilling snapshot for
     * {@link #LAMP_XP_SUPPRESS_TICKS} ticks so the subsequent XP gain doesn't
     * open a window that catches an equipment swap.
     */
    private static final Set<String> LAMP_XP_MENU_OPTIONS = java.util.Set.of(
            "rub", "read", "open", "use", "claim", "activate", "tear"
    );

    private final Map<String, List<ItemStack>> skillingSnapshot = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long>            skillingExpiry   = new java.util.concurrent.ConcurrentHashMap<>();

    // ═════════════════════════════════════════════════════════════════════════
    //  INJECTED FIELDS
    // ═════════════════════════════════════════════════════════════════════════

    @Inject private Client                   client;
    @Inject private ClientThread             clientThread;
    @Inject private RunealyticsConfig        config;
    @Inject private ClientToolbar            clientToolbar;
    @Inject private OkHttpClient             okHttpClient;
    @Inject private Gson                     gson;
    @Inject private ConfigManager            configManager;
    @Inject private ItemManager              itemManager;
    @Inject private LootTrackerManager       lootManager;
    @Inject private RuneAlyticsState         state;
    @Inject private ScheduledExecutorService executorService;
    @Inject private XpTrackerManager         xpTrackerManager;
    @Inject private RunealyticsApiClient     apiClient;
    @Inject private BankDataManager          bankDataManager;
    @Inject private MatchmakingManager       matchmakingManager;
    @Inject private MatchmakingMinimapOverlay matchmakingOverlay;
    @Inject private OverlayManager           overlayManager;

    // ── UI ───────────────────────────────────────────────────────────────────
    @Getter private RuneAlyticsPanel mainPanel;
    private NavigationButton         navButton;
    private LootTrackerPanel         lootTrackerPanel;

    // ── Live-map heartbeat ─────────────────────────────────────────────────────
    /** Heartbeat period — how often the live map location is pushed to the site. */
    private static final long HEARTBEAT_PERIOD_MS        = 60_000;
    /** Delay before the first heartbeat after login so player/RSN state settles. */
    private static final long HEARTBEAT_INITIAL_DELAY_MS = 5_000;
    /**
     * Repeating heartbeat task, alive only while the player is logged in. Started
     * on the {@code LOGGED_IN} game-state transition and cancelled on
     * {@code LOGIN_SCREEN} so no heartbeats fire while idle at the login screen.
     */
    private ScheduledFuture<?> heartbeatTask;

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT TRACKING STATE
    // ═════════════════════════════════════════════════════════════════════════

    private List<ItemStack> inventorySnapshot        = null;
    private boolean         waitingForTemporossLoot  = false;
    private boolean         waitingForWintertodtLoot = false;

    /**
     * True for {@value #WHISPERER_GROUND_ITEM_WINDOW_MS} ms after the Whisperer
     * KC chat message fires. During this window every {@code ItemSpawned} event
     * near the player is collected and attributed to the Whisperer kill.
     */
    private boolean         whispererGroundItemWindow = false;
    /** Timestamp when {@link #whispererGroundItemWindow} was opened. */
    private Instant         whispererKillTime         = null;
    /**
     * Accumulates {@link ItemStack}s collected via {@code ItemSpawned} while
     * {@link #whispererGroundItemWindow} is open.
     */
    private final List<ItemStack> whispererGroundItems = new ArrayList<>();
    private static final long WHISPERER_GROUND_ITEM_WINDOW_MS = 5_000;
    private ScheduledFuture<?> whispererFlushTask;
    private static final int WHISPERER_DEBOUNCE_MS = 450;
    private int whispererParsedKC = -1;

    private NPC             lastKilledBoss           = null;
    private Instant         lastKillTime             = null;

    // ── Ring of Wealth coin auto-pickup detection ─────────────────────────────
    /** Item ID for coins — used to identify RoW auto-collected drops. */
    private static final int ITEM_ID_COINS = 995;
    /** How long (ms) we keep the RoW inventory snapshot after an NPC kill. */
    private static final long ROW_WINDOW_MS = 4_000;
    /** Inventory snapshot taken at NPC kill to diff against after RoW message. */
    private List<ItemStack> rowInventorySnapshot = null;
    /** The boss NPC whose kill opened the current RoW snapshot window. */
    private NPC             rowSnapshotBoss      = null;
    /** Absolute expiry time (ms) for the RoW snapshot window. */
    private long            rowSnapshotExpiry    = 0L;

    private final List<ItemStack>  groundItemBuffer         = new ArrayList<>();
    private final AtomicBoolean    groundItemFlushScheduled = new AtomicBoolean(false);

    private String lastChestSource = null;

    /**
     * Baseline XP snapshot per skill — updated on every {@link StatChanged} event.
     * Used to compute the XP delta (gained this tick) passed to
     * {@link XpTrackerManager#onXpGained}.
     */
    private final Map<Skill, Integer> previousXp = new EnumMap<>(Skill.class);

    // ─────────────────────────────────────────────────────────────────────────
    //  PICKPOCKET / THIEVING STATE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The HTML-stripped NPC / stall name from the most recent "Pickpocket" menu click.
     * Reset to {@code null} when {@link #pickpocketWindowExpiry} passes or when a
     * successful pickpocket loot diff is recorded.
     *
     * <p>Example values: {@code "Master Farmer"}, {@code "Knight of Ardougne"},
     * {@code "Gem Stall"}</p>
     */
    private String  pendingPickpocketNpc = null;

    /**
     * Inventory snapshot taken immediately after (on the next client tick following)
     * a "Pickpocket" click.  Used to diff against the post-pickpocket inventory to
     * determine which items were gained.
     *
     * <p>Updated to the <em>current</em> inventory after each successful diff so
     * that rapid consecutive pickpocket actions are each counted separately.</p>
     */
    private List<ItemStack> pickpocketInventorySnapshot = null;

    /**
     * Absolute time (ms since epoch) at which the pickpocket attribution window closes.
     * Set to {@code System.currentTimeMillis() + PICKPOCKET_WINDOW_MS} on each click,
     * and reset on each successful loot diff so rapid pickpockets keep the window open.
     */
    private long pickpocketWindowExpiry = 0L;

    // ─────────────────────────────────────────────────────────────────────────
    //  IMPLING JAR STATE
    //
    //  Imp catches give the player an "* impling jar" inventory item which
    //  itself doesn't have the loot — the loot only materialises when the
    //  player picks the "Loot-jar" option on the jar.  That action gives no
    //  XP, so the generic Hunter skilling diff misses it entirely (which is
    //  the user-reported "if you catch an imp - will that work?" bug).
    // ─────────────────────────────────────────────────────────────────────────

    /** ms after a Loot-jar click during which inventory diffs are credited to the jar. */
    private static final long IMP_JAR_WINDOW_MS = 1_500;

    /** Raw item name of the impling jar that was last looted, e.g. "Eclectic impling jar". */
    private String  pendingImpJarName       = null;
    private List<ItemStack> impJarInventorySnapshot = null;
    private long    impJarWindowExpiry      = 0L;

    // ─────────────────────────────────────────────────────────────────────────
    //  ZERO-LOOT KILL TRACKING
    //
    //  RuneLite's NpcLootReceived fires only when the kill produced loot, so
    //  every dry kill on a low-level monster (and many bosses on RNG zero
    //  drops) was being lost.  We close that gap by:
    //
    //    1. Tracking every NPC the local player damaged (HitsplatApplied
    //       with hitsplat.isMine())
    //    2. On ActorDeath for an NPC we damaged, queue a pending zero-loot
    //       kill that flushes ZERO_LOOT_FLUSH_TICKS game ticks later
    //    3. If NpcLootReceived arrives for that NPC in the meantime the
    //       pending entry is cancelled (the normal loot path handles it)
    //
    //  Chest-only bosses (raids, Nightmare, Yama, Royal Titans, etc.) are
    //  excluded inside LootTrackerManager#processZeroLootKill so the chest
    //  read path stays the single source of truth for them.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Game ticks to wait after {@link ActorDeath} before flushing a pending
     * zero-loot kill.  Long enough for {@code NpcLootReceived} (which usually
     * arrives on the same tick or the very next one) to cancel the entry, but
     * short enough that the kill counter feels live.  3 ticks ≈ 1.8 s.
     */
    private static final int ZERO_LOOT_FLUSH_TICKS = 3;

    /** NPC indexes the local player damaged this combat. */
    private final Map<Integer, NPC> damagedNpcs = new HashMap<>();

    /**
     * NPCs that have entered the dying animation and are awaiting either a
     * cancelling {@link NpcLootReceived} or a scheduled flush as a zero-loot
     * kill.  Keyed by NPC index.
     */
    private final Map<Integer, PendingDeath> pendingDeaths = new HashMap<>();

    /** Plugin-local game tick counter; incremented every {@link #onGameTick}. */
    private long gameTickCount = 0;

    private static final class PendingDeath
    {
        final NPC  npc;
        final long flushAtTick;

        PendingDeath(NPC npc, long flushAtTick)
        {
            this.npc         = npc;
            this.flushAtTick = flushAtTick;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STARTUP / SHUTDOWN
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void startUp() throws Exception
    {
        log.info("RuneAlytics starting");

        // Build the navigation button SYNCHRONOUSLY so it is registered with the
        // toolbar before startUp() returns.  Previously this happened inside an
        // executor callback, which meant:
        //   - A rapid disable→enable cycle could leave a stale navButton orphaned
        //     in the toolbar (issue #1 — UI disappearing).
        //   - If shutDown() ran before the executor task fired, the button would
        //     be added AFTER shutDown nulled it, never to be removed.
        // The button now exists by the time shutDown() can possibly be called.
        mainPanel = injector.getInstance(RuneAlyticsPanel.class);

        navButton = NavigationButton.builder()
                .tooltip("RuneAlytics")
                .icon(loadPluginIcon())
                .priority(1)
                .panel(mainPanel)
                .build();
        clientToolbar.addNavigation(navButton);
        overlayManager.add(matchmakingOverlay);
        log.info("RuneAlytics nav button registered");

        // Heavy panel construction stays async — it can take a few ms and we
        // don't want it on the startup-critical path.  Tabs are appended via
        // SwingUtilities.invokeLater so they show up the moment they're ready.
        executorService.execute(() ->
        {
            try
            {
                LootTrackerPanel        lootPanel        = injector.getInstance(LootTrackerPanel.class);
                MatchmakingPanel        matchmakingPanel = injector.getInstance(MatchmakingPanel.class);
                RuneAlyticsSettingsPanel settingsPanel   = injector.getInstance(RuneAlyticsSettingsPanel.class);

                lootTrackerPanel = lootPanel;
                // Sync starts disabled until feature flags confirm it is active
                lootPanel.setSyncEnabled(false);

                SwingUtilities.invokeLater(() ->
                {
                    try
                    {
                        // Loot Tracker has no feature gate — local tracking is always available.
                        // Sync availability is controlled separately via setSyncEnabled().
                        mainPanel.addTab("Loot Tracker", null,             lootPanel);
                        mainPanel.addTab("Matches",      FEATURE_MATCHES,  matchmakingPanel);
                        mainPanel.addTab("Settings",     FEATURE_VERIFICATION, settingsPanel);
                        log.info("RuneAlytics tabs populated");
                    }
                    catch (Exception ex)
                    {
                        log.error("Failed to populate RuneAlytics tabs", ex);
                    }
                });
            }
            catch (Exception e)
            {
                log.error("Failed to instantiate RuneAlytics panels", e);
            }
        });
    }

    @Override
    protected void shutDown()
    {
        log.info("RuneAlytics shutting down");

        // Stop the live-map heartbeat so no pings fire after the plugin is gone.
        stopHeartbeat();

        // Flush any XP that accumulated in the current 30-second window before
        // the executor shuts down, so the player's last session gains are not lost.
        try { xpTrackerManager.flushImmediate(); } catch (Exception e) { log.warn("XP flush on shutdown failed: {}", e.getMessage()); }
        try { lootManager.shutdown();             } catch (Exception e) { log.warn("Loot manager shutdown failed: {}", e.getMessage()); }
        try { matchmakingManager.reset();         } catch (Exception e) { log.warn("Matchmaking reset on shutdown failed: {}", e.getMessage()); }
        try { overlayManager.remove(matchmakingOverlay); } catch (Exception e) { log.warn("Matchmaking overlay removal failed: {}", e.getMessage()); }

        // Always attempt to remove the nav button — even if some other code
        // path nulled the reference, leaving an orphan would mean the next
        // startUp adds a duplicate (and the user sees no button).
        if (navButton != null)
        {
            try { clientToolbar.removeNavigation(navButton); }
            catch (Exception e) { log.warn("Nav button removal failed: {}", e.getMessage()); }
            navButton = null;
        }

        state.reset();
    }

    private void clearTransientLootState()
    {
        lastKilledBoss            = null;
        lastKillTime              = null;
        lastChestSource           = null;
        inventorySnapshot         = null;
        waitingForTemporossLoot   = false;
        waitingForWintertodtLoot  = false;
        whispererGroundItemWindow = false;
        whispererKillTime         = null;
        whispererGroundItems.clear();
        groundItemBuffer.clear();
        groundItemFlushScheduled.set(false);

        // Clear Ring of Wealth snapshot
        rowInventorySnapshot = null;
        rowSnapshotBoss      = null;
        rowSnapshotExpiry    = 0L;

        // Clear pickpocket state
        pendingPickpocketNpc        = null;
        pickpocketInventorySnapshot = null;
        pickpocketWindowExpiry      = 0L;

        // Clear impling jar state
        pendingImpJarName       = null;
        impJarInventorySnapshot = null;
        impJarWindowExpiry      = 0L;

        // Clear zero-loot kill tracking (damage history is per-world)
        damagedNpcs.clear();
        pendingDeaths.clear();
    }

    @Provides
    RunealyticsConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(RunealyticsConfig.class);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 1 – NPC GROUND DROPS
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        if (!config.enableLootTracking()) return;

        NPC npc = event.getNpc();
        if (npc == null) return;

        // Capture game mode at kill time so the record reflects the world in use now
        updateCurrentGameMode();

        // Loot arrived → cancel any pending zero-loot kill for this NPC so
        // we don't double-count.
        pendingDeaths.remove(npc.getIndex());
        damagedNpcs.remove(npc.getIndex());

        lastKilledBoss = npc;
        lastKillTime   = Instant.now();

        // Snapshot inventory immediately so we can diff it if Ring of Wealth
        // auto-collects coins (those never fire ItemSpawned).
        clientThread.invokeLater(() -> {
            rowInventorySnapshot = getCurrentInventory();
            rowSnapshotBoss      = npc;
            rowSnapshotExpiry    = System.currentTimeMillis() + ROW_WINDOW_MS;
        });

        Collection<net.runelite.client.game.ItemStack> rlItems = event.getItems();
        if (rlItems == null || rlItems.isEmpty()) return;

        List<ItemStack> items = new ArrayList<>();
        for (net.runelite.client.game.ItemStack i : rlItems)
            items.add(new ItemStack(i.getId(), i.getQuantity()));

        lootManager.processNpcLoot(npc, items);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 1b – ZERO-LOOT NPC KILLS
    //
    //  See the comment block on the ZERO_LOOT_FLUSH_TICKS constant for the
    //  full design.  These three handlers feed the pendingDeaths map which
    //  onGameTick drains.
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event)
    {
        Actor target = event.getActor();
        Hitsplat hs   = event.getHitsplat();

        // ── Matchmaking: a hit between the two participants starts the fight ──
        // Runs independently of loot tracking — combat detection must work
        // even when loot tracking is disabled.  The manager scopes the splat
        // strictly to the opponent before reporting Ready → Fighting.
        if (target instanceof Player)
        {
            matchmakingManager.onCombatHitsplat(target, hs);
        }

        // ── Loot tracking: remember every NPC the local player damaged ───────
        if (!config.enableLootTracking()) return;

        if (!(target instanceof NPC)) return;
        if (hs == null || !hs.isMine()) return;

        damagedNpcs.put(((NPC) target).getIndex(), (NPC) target);
    }

    @Subscribe
    public void onActorDeath(ActorDeath event)
    {
        Actor actor = event.getActor();

        // ── Matchmaking: report player deaths to server ───────────────────────
        if (actor instanceof Player)
        {
            matchmakingManager.onActorDeath((Player) actor);
        }

        if (!config.enableLootTracking()) return;

        if (!(actor instanceof NPC)) return;

        NPC npc = (NPC) actor;
        if (!damagedNpcs.containsKey(npc.getIndex())) return;

        // ActorDeath fires at the *start* of the death animation, but
        // NpcLootReceived can lag by 1–2 ticks (loot table rolls server-side,
        // ground items take a tick to spawn).  Wait ZERO_LOOT_FLUSH_TICKS
        // before deciding this kill produced nothing.
        pendingDeaths.put(
                npc.getIndex(),
                new PendingDeath(npc, gameTickCount + ZERO_LOOT_FLUSH_TICKS));
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event)
    {
        NPC npc = event.getNpc();
        if (npc == null) return;

        // Drop the damage-tracking entry once the NPC is gone.  We deliberately
        // leave any pendingDeaths entry in place: a despawn often happens after
        // ActorDeath but before the flush window closes, and we still want to
        // record the zero-loot kill.
        damagedNpcs.remove(npc.getIndex());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 2 – PLAYER / CHEST LOOT
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        if (!config.enableLootTracking()) return;

        Collection<net.runelite.client.game.ItemStack> rlItems = event.getItems();
        if (rlItems == null || rlItems.isEmpty()) return;

        String source = (lastChestSource != null && !lastChestSource.isEmpty())
                ? lastChestSource : "Unknown Chest";
        lastChestSource = null;

        if (source.toLowerCase().contains("reward pool")
                || source.toLowerCase().contains("casket (tempoross)"))
            source = "Tempoross";

        List<ItemStack> items = new ArrayList<>();
        for (net.runelite.client.game.ItemStack i : rlItems)
            items.add(new ItemStack(i.getId(), i.getQuantity()));

        lootManager.processPlayerLoot(source, items);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 3 – WIDGET LOADED
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (!config.enableLootTracking()) return;

        int gid = event.getGroupId();
        log.info("WidgetLoaded: groupId={}", gid);

        // ── Special cases that can't use the generic registry ────────────────
        if (gid == WIDGET_WHISPERER)
        {
            log.info("Whisperer widget 834 detected — waiting for drops (KC={})", whispererParsedKC);
            whispererGroundItemWindow = true;
            if (whispererFlushTask != null) whispererFlushTask.cancel(false);
            whispererFlushTask = executorService.schedule(
                    this::flushWhispererLoot, 450, TimeUnit.MILLISECONDS);
            return;
        }
        if (gid == WIDGET_NIGHTMARE)
        {
            // Nightmare and Phosani share the same widget group; the chat
            // detector populates lastChestSource so we can tell them apart.
            String nm = (lastChestSource != null && lastChestSource.contains("Phosani"))
                    ? "Phosani's Nightmare" : "The Nightmare";
            lastChestSource = nm;
            lootManager.readRewardContainer(nm, RewardSources.CONTAINER_NIGHTMARE);
            return;
        }
        if (gid == WIDGET_WINTERTODT)
        {
            lastChestSource = "Wintertodt";
            clientThread.invokeLater(() -> {
                inventorySnapshot        = getCurrentInventory();
                waitingForWintertodtLoot = true;
            });
            // Wintertodt has no clean container ID, walk the widget tree.
            lootManager.readWidgetLoot("Wintertodt", WIDGET_WINTERTODT, 80);
            return;
        }
        if (gid == WIDGET_CLUE)
        {
            String src = (lastChestSource != null) ? lastChestSource : "Clue Scroll";
            lastChestSource = null;
            lootManager.readClueReward(src);
            return;
        }

        // ── Generic registry-driven reads ────────────────────────────────────
        RewardSources.Source src = RewardSources.BY_WIDGET.get(gid);
        if (src != null)
        {
            lastChestSource = src.displayName;
            lootManager.readReward(src, gid);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 4 – INVENTORY DIFF  (Tempoross / Wintertodt + Pickpocket)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Handles inventory container changes.
     *
     * <p>Serves two purposes:</p>
     * <ol>
     *   <li><b>Tempoross / Wintertodt</b> – existing fallback diff logic.</li>
     *   <li><b>Pickpocket / Thieving</b> – when {@link #pendingPickpocketNpc} is set
     *       and the pickpocket window has not expired, diffs the inventory against
     *       {@link #pickpocketInventorySnapshot} to find the items gained from the
     *       pickpocket.  The snapshot is then advanced to the current inventory so
     *       rapid consecutive pickpockets are each counted as individual loot events.</li>
     * </ol>
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        // ── Matchmaking: refresh gear snapshot and report on change ──────────
        // Runs on the client thread, so ItemContainer reads inside the manager
        // are safe.  The manager only acts if a match is active.
        matchmakingManager.onItemContainerChanged(event);

        // ── Bank sync ─────────────────────────────────────────────────────────
        if (event.getContainerId() == InventoryID.BANK.getId()
                && config.enableBankSync()
                && state.isLoggedIn()
                && state.isVerified())
        {
            final String token          = state.getVerificationCode();
            final String username       = state.getVerifiedUsername();
            // Build the complete JSON snapshot HERE on the client thread because
            // fromContainerWithValues calls ItemManager.getItemComposition which
            // requires client.getItemDefinition — a client-thread-only API.
            // Only the HTTP call is handed off to the background executor.
            final JsonObject bankSnapshot = bankDataManager.buildBankSnapshot(
                    username,
                    event.getItemContainer(),
                    client.getItemContainer(InventoryID.INVENTORY),
                    client.getItemContainer(InventoryID.EQUIPMENT));
            executorService.execute(() -> bankDataManager.syncBankData(token, username, bankSnapshot));
            return;
        }

        if (!config.enableLootTracking()) return;

        // ── Wilderness Loot Chest & Lunar (Moons of Peril) Chest ─────────────
        // These chests don't fire WidgetLoaded with a usable group for us, but
        // the inventory container itself can be read directly when it fills.
        int containerId = event.getContainerId();
        if (containerId == RewardSources.CONTAINER_WILDY_LOOT_CHEST)
        {
            captureRewardContainer("Wilderness Loot Chest", event.getItemContainer());
            return;
        }
        if (containerId == RewardSources.CONTAINER_LUNAR_CHEST)
        {
            captureRewardContainer("Moons of Peril", event.getItemContainer());
            return;
        }

        if (containerId != InventoryID.INVENTORY.getId()) return;

        clientThread.invokeLater(() ->
        {
            // ── Impling jar loot (no XP, so the skilling diff misses it) ──────
            if (pendingImpJarName != null && impJarInventorySnapshot != null)
            {
                if (System.currentTimeMillis() > impJarWindowExpiry)
                {
                    pendingImpJarName       = null;
                    impJarInventorySnapshot = null;
                }
                else
                {
                    List<ItemStack> currentInv = getCurrentInventory();
                    List<ItemStack> gained     = diffInventory(impJarInventorySnapshot, currentInv);
                    if (!gained.isEmpty())
                    {
                        String jarName = pendingImpJarName;
                        pendingImpJarName       = null;
                        impJarInventorySnapshot = null;
                        lootManager.processImplingLoot(jarName, gained);
                    }
                }
            }

            // ── Tempoross / Wintertodt ────────────────────────────────────────
            if ((waitingForTemporossLoot || waitingForWintertodtLoot) && inventorySnapshot != null)
            {
                List<ItemStack> current = getCurrentInventory();
                List<ItemStack> gained  = diffInventory(inventorySnapshot, current);

                if (!gained.isEmpty())
                {
                    if (waitingForTemporossLoot)
                    {
                        lootManager.processInventoryDiff("Tempoross", gained);
                        waitingForTemporossLoot = false;
                        inventorySnapshot = null;
                    }
                    else
                    {
                        lootManager.processInventoryDiff("Wintertodt", gained);
                        waitingForWintertodtLoot = false;
                        inventorySnapshot = null;
                    }
                }
            }

            // ── Skilling loot (runs regardless of pickpocket state) ───────────
            if (!skillingSnapshot.isEmpty() && config.enableLootTracking())
            {
                List<ItemStack> inv = getCurrentInventory();
                long now = System.currentTimeMillis();
                for (String skill : new ArrayList<>(skillingExpiry.keySet()))
                {
                    if (now > skillingExpiry.getOrDefault(skill, 0L))
                    {
                        skillingExpiry.remove(skill);
                        skillingSnapshot.remove(skill);
                        log.debug("Skilling session expired: {}", skill);
                        continue;
                    }
                    List<ItemStack> snap = skillingSnapshot.get(skill);
                    if (snap == null) continue;
                    List<ItemStack> items = diffInventory(snap, inv);
                    if (!items.isEmpty())
                    {
                        lootManager.processSkillLoot(skill, new ArrayList<>(items));
                        skillingSnapshot.put(skill, inv);
                    }
                }
            }

            // ── Pickpocket loot ───────────────────────────────────────────────
            if (!config.enablePickpocketTracking()) return;
            if (pendingPickpocketNpc == null || pickpocketInventorySnapshot == null) return;
            if (System.currentTimeMillis() > pickpocketWindowExpiry)
            {
                log.debug("Pickpocket window expired for '{}'", pendingPickpocketNpc);
                pendingPickpocketNpc        = null;
                pickpocketInventorySnapshot = null;
                return;
            }

            List<ItemStack> current = getCurrentInventory();
            List<ItemStack> gained  = diffInventory(pickpocketInventorySnapshot, current);

            if (!gained.isEmpty())
            {
                String npc = pendingPickpocketNpc;
                List<ItemStack> loot = new ArrayList<>(gained);

                log.debug("Pickpocket diff: '{}' gained {} item type(s)", npc, loot.size());
                lootManager.processPickpocketLoot(npc, loot);

                pickpocketInventorySnapshot = current;
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 6 – PICKPOCKET CLICK DETECTION  (MenuOptionClicked)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Detects "Pickpocket" menu option clicks and snapshots the current inventory
     * so that the subsequent {@link #onItemContainerChanged} call can diff it.
     *
     * <p>Both "Pickpocket" (NPC right-click) and "Pick-pocket" (older variant) are
     * accepted.  The NPC / stall name is extracted from the menu target by stripping
     * RuneScape HTML colour tags.</p>
     *
     * <p>If pickpocket tracking is disabled in config this handler returns early.</p>
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!config.enableLootTracking()) return;

        String option = event.getMenuOption();
        if (option == null) return;

        String rawTarget = event.getMenuTarget();
        if (rawTarget == null || rawTarget.isEmpty()) return;

        String targetName = rawTarget.replaceAll("<[^>]*>", "").trim();
        if (targetName.isEmpty()) return;

        // ── Lamp / book / genie / scroll XP suppression ──────────────────────
        // These give XP without an inventory drop, but the XP gain still opens
        // a skilling snapshot window. Pre-emptively close that window so the
        // next inventory change (e.g. unequip) isn't falsely attributed.
        String lowerTarget = targetName.toLowerCase();
        if (LAMP_XP_MENU_OPTIONS.contains(option.toLowerCase())
                && (lowerTarget.contains("lamp")
                || lowerTarget.contains("book")
                || lowerTarget.contains("tome")
                || lowerTarget.contains("scroll")
                || lowerTarget.contains("genie")
                || lowerTarget.contains("antique")))
        {
            lampXpSuppressUntilTick = gameTickCount + LAMP_XP_SUPPRESS_TICKS;
            log.debug("Lamp/book click '{}' on '{}': suppressing skilling snapshot for {} ticks",
                    option, targetName, LAMP_XP_SUPPRESS_TICKS);
            // fall through — the impling/pickpocket branches below should still run
        }

        // ── Impling jar loot ─────────────────────────────────────────────────
        // The menu option is "Loot-jar" (or "Loot" in some clients) and the
        // target is the jar item name (e.g. "Eclectic impling jar").
        if (("Loot-jar".equalsIgnoreCase(option) || "Loot".equalsIgnoreCase(option))
                && targetName.toLowerCase().endsWith("impling jar"))
        {
            pendingImpJarName  = targetName;
            impJarWindowExpiry = System.currentTimeMillis() + IMP_JAR_WINDOW_MS;
            clientThread.invokeLater(() -> {
                impJarInventorySnapshot = getCurrentInventory();
                log.debug("Impling jar snapshot for '{}' ({} slots)", targetName,
                        impJarInventorySnapshot.size());
            });
            return;
        }

        // ── Pickpocket / Thieving (only if enabled) ──────────────────────────
        if (!config.enablePickpocketTracking()) return;
        if (!"Pickpocket".equalsIgnoreCase(option) && !"Pick-pocket".equalsIgnoreCase(option))
            return;

        log.debug("Pickpocket click detected: option='{}' target='{}'", option, targetName);
        pendingPickpocketNpc   = targetName;
        pickpocketWindowExpiry = System.currentTimeMillis() + PICKPOCKET_WINDOW_MS;

        clientThread.invokeLater(() ->
        {
            pickpocketInventorySnapshot = getCurrentInventory();
            log.debug("Pickpocket snapshot taken for '{}' ({} slots occupied)",
                    targetName, pickpocketInventorySnapshot.size());
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT PATH 5 – GROUND ITEM SPAWN  (fallback for unsupported new content)
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (!config.enableLootTracking()) return;

        TileItem tile = event.getItem();
        WorldPoint itemLoc = event.getTile().getWorldLocation();

        // ── Whisperer personal drops (debounced) ──────────────────────────────
        if (whispererGroundItemWindow)
        {
            Player local = client.getLocalPlayer();
            if (local == null) return;

            WorldPoint playerLoc = local.getWorldLocation();

            if (itemLoc != null && itemLoc.distanceTo(playerLoc) <= 12)
            {
                whispererGroundItems.add(new ItemStack(tile.getId(), tile.getQuantity()));
                log.debug("Whisperer ground item collected: id={} qty={} at {}",
                        tile.getId(), tile.getQuantity(), itemLoc);

                if (whispererFlushTask != null) whispererFlushTask.cancel(false);
                whispererFlushTask = executorService.schedule(
                        this::flushWhispererLoot, 450, TimeUnit.MILLISECONDS);
            }
            return;
        }

        // ── Normal ground item logic ──────────────────────────────────────────
        if (lastKilledBoss == null || lastKillTime == null) return;

        long elapsedMs = Instant.now().toEpochMilli() - lastKillTime.toEpochMilli();
        if (elapsedMs > GROUND_ITEM_WINDOW_MS) return;

        WorldPoint bossLoc = lastKilledBoss.getWorldLocation();
        if (itemLoc == null || bossLoc == null || itemLoc.distanceTo(bossLoc) > 5) return;

        groundItemBuffer.add(new ItemStack(tile.getId(), tile.getQuantity()));

        if (groundItemFlushScheduled.compareAndSet(false, true))
        {
            final NPC boss = lastKilledBoss;

            executorService.schedule(() ->
                            clientThread.invokeLater(() ->
                            {
                                if (!groundItemBuffer.isEmpty())
                                {
                                    List<ItemStack> batch = new ArrayList<>(groundItemBuffer);
                                    groundItemBuffer.clear();
                                    groundItemFlushScheduled.set(false);
                                    lootManager.processGroundItemBatch(boss, batch);
                                }
                                else
                                {
                                    groundItemFlushScheduled.set(false);
                                }
                            }),
                    500, TimeUnit.MILLISECONDS);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CHAT MESSAGE
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!config.enableLootTracking()) return;

        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM) return;

        String msg   = event.getMessage();
        String lower = msg.toLowerCase();

        // ── Tempoross ─────────────────────────────────────────────────────────
        if (lower.contains("subdued the spirit") || lower.contains("you have helped to subdue"))
        {
            clientThread.invokeLater(() -> {
                inventorySnapshot       = getCurrentInventory();
                waitingForTemporossLoot = true;
                log.info("Tempoross: inventory snapshot taken ({} items)", inventorySnapshot.size());
            });
            lastChestSource = "Tempoross";
            return;
        }

        // ── Wintertodt ───────────────────────────────────────────────────────
        if (lower.contains("supply crate") && lower.contains("wintertodt"))
        {
            clientThread.invokeLater(() -> {
                inventorySnapshot        = getCurrentInventory();
                waitingForWintertodtLoot = true;
            });
            lastChestSource = "Wintertodt";
            return;
        }

        // ── The Whisperer ────────────────────────────────────────────────────
        if (lower.contains("whisperer") && lower.contains("kill count"))
        {
            String stripped = msg.replaceAll("<[^>]*>", "");
            Matcher kcM = Pattern
                    .compile("kill count is:?\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
                    .matcher(stripped);

            whispererParsedKC = kcM.find() ? Integer.parseInt(kcM.group(1)) : -1;

            lastChestSource           = "The Whisperer";
            whispererGroundItemWindow = true;
            whispererKillTime         = Instant.now();
            whispererGroundItems.clear();

            log.info("The Whisperer: KC detected (game KC={}) – ground-item collection window opened",
                    whispererParsedKC);
            return;
        }

        // ── Pet drop ─────────────────────────────────────────────────────────
        // OSRS never includes pets in NpcLootReceived — they arrive silently in
        // inventory (or bank) accompanied by one of these three game messages.
        if (lower.contains("funny feeling like you're being followed")
                || lower.contains("sneaking into your backpack"))
        {
            if (lastKilledBoss != null && lastKillTime != null)
            {
                long elapsedSec = ChronoUnit.SECONDS.between(lastKillTime, Instant.now());
                if (elapsedSec < BOSS_CLEAR_TIMEOUT_SECONDS)
                {
                    final NPC           boss = lastKilledBoss;
                    final List<ItemStack> snap = rowInventorySnapshot != null
                            ? new ArrayList<>(rowInventorySnapshot) : Collections.emptyList();
                    final boolean wentToBank = lower.contains("sneaking into your backpack");

                    clientThread.invokeLater(() -> {
                        ItemStack petItem = null;
                        if (!wentToBank)
                        {
                            // Pet went to inventory — diff to find which item it is
                            List<ItemStack> gained = diffInventory(snap, getCurrentInventory());
                            // Pick the first gained item that isn't coins or a common consumable
                            for (ItemStack is : gained)
                            {
                                if (is.getId() != ITEM_ID_COINS && is.getQuantity() == 1)
                                {
                                    petItem = is;
                                    break;
                                }
                            }
                        }
                        String bossName = lootManager.normalizeBossName(boss.getName());
                        lootManager.appendPetDrop(bossName, petItem);
                        log.info("[Pet] drop detected for '{}' (wentToBank={})", bossName, wentToBank);
                    });
                }
            }
            return;
        }

        // ── Ring of Wealth auto-pickup ────────────────────────────────────────
        // Game messages (imbued RoW):
        //   "Your ring of wealth has automatically picked up the coins."
        //   "Your ring of wealth has automatically alched the <item>."
        // Auto-picked items bypass ItemSpawned, so we diff inventory vs the
        // snapshot taken at the NPC kill and append the gain to the last
        // kill record. We now capture ALL gained items (issue #7 — previously
        // only coins were captured, so RoW-alched items were silently lost).
        if (lower.contains("ring of wealth") &&
                (lower.contains("automatically picked up") || lower.contains("automatically alched")))
        {
            if (rowInventorySnapshot != null && rowSnapshotBoss != null
                    && System.currentTimeMillis() < rowSnapshotExpiry)
            {
                final NPC           boss = rowSnapshotBoss;
                final List<ItemStack> snap = rowInventorySnapshot;
                rowInventorySnapshot = null;
                rowSnapshotBoss      = null;

                clientThread.invokeLater(() -> {
                    List<ItemStack> current = getCurrentInventory();
                    List<ItemStack> gained  = diffInventory(snap, current);
                    if (!gained.isEmpty())
                    {
                        String bossName = lootManager.normalizeBossName(boss.getName());
                        lootManager.appendDropsToLastKill(bossName, gained);
                        log.info("Ring of Wealth: {} item type(s) appended to '{}'",
                                gained.size(), bossName);
                    }
                });
            }
            return;
        }

        // ── Generic KC parser ────────────────────────────────────────────────
        if (lower.contains("kill count is"))
            lootManager.parseKillCountMessage(msg);

        // ── Chest detection fallback ─────────────────────────────────────────
        String detected = lootManager.detectChestSource(lower);
        if (detected != null && lastChestSource == null)
        {
            lastChestSource = detected;
            log.info("Chat: chest source set to '{}'", detected);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GAME TICK
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        gameTickCount++;

        // ── Matchmaking polling / automation ─────────────────────────────────
        // Runs on the client thread, so inventory/gear reads inside the manager
        // are safe (no AssertionError from ItemContainer access off-thread).
        matchmakingManager.onGameTick();

        // ── Flush zero-loot kills ──────────────────────────────────────────────
        // ActorDeath entries that aged out without a cancelling
        // NpcLootReceived are promoted to zero-loot kills here.
        if (!pendingDeaths.isEmpty())
        {
            final long now = gameTickCount;
            pendingDeaths.entrySet().removeIf(e ->
            {
                if (now < e.getValue().flushAtTick) return false;
                lootManager.processZeroLootKill(e.getValue().npc);
                damagedNpcs.remove(e.getKey());
                return true;
            });
        }

        // ── Expire stale boss ground-item attribution ──────────────────────────
        if (lastKillTime != null
                && ChronoUnit.SECONDS.between(lastKillTime, Instant.now())
                > BOSS_CLEAR_TIMEOUT_SECONDS)
        {
            lastKilledBoss = null;
            lastKillTime   = null;
        }

        // ── Expire the Whisperer ground-item window ───────────────────────────
        if (whispererGroundItemWindow && whispererKillTime != null)
        {
            long elapsed = Instant.now().toEpochMilli() - whispererKillTime.toEpochMilli();
            if (elapsed > WHISPERER_GROUND_ITEM_WINDOW_MS + 5_000)
            {
                log.warn("Whisperer ground-item window force-expired with {} items unclaimed",
                        whispererGroundItems.size());
                whispererGroundItemWindow = false;
                whispererGroundItems.clear();
                whispererKillTime = null;
            }
        }

        // ── Expire pickpocket attribution window ──────────────────────────────
        if (pendingPickpocketNpc != null
                && System.currentTimeMillis() > pickpocketWindowExpiry)
        {
            log.debug("Pickpocket window ticked out for '{}'", pendingPickpocketNpc);
            pendingPickpocketNpc        = null;
            pickpocketInventorySnapshot = null;
        }

        // ── Expire impling jar attribution window ─────────────────────────────
        if (pendingImpJarName != null && System.currentTimeMillis() > impJarWindowExpiry)
        {
            log.debug("Impling jar window ticked out for '{}'", pendingImpJarName);
            pendingImpJarName       = null;
            impJarInventorySnapshot = null;
        }

        // ── Expire skilling sessions ──────────────────────────────────────────
        long nowMs = System.currentTimeMillis();
        skillingExpiry.entrySet().removeIf(entry -> {
            if (nowMs > entry.getValue())
            {
                skillingSnapshot.remove(entry.getKey());
                log.debug("Skilling session ticked out: {}", entry.getKey());
                return true;
            }
            return false;
        });

        // NOTE: The per-tick XP flush that was previously here has been removed.
        // XP is now batched by XpTrackerManager and sent once every 30 seconds.
        // Sending on every game tick (≈600 ms) was causing ~100 API calls/minute,
        // which silently prevented XP from appearing on the website.
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GAME STATE CHANGE
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState gs = event.getGameState();
        log.info("GameState: {}", gs);

        if (gs == GameState.LOGIN_SCREEN)
        {
            state.setLoggedIn(false);
            // Stop the live-map heartbeat: while idle at the login screen there is
            // no player to locate, so we must not keep pinging the server.
            stopHeartbeat();
            matchmakingManager.reset(); // clear any active match on logout
            if (lootTrackerPanel != null) lootTrackerPanel.setSyncEnabled(false);
            SwingUtilities.invokeLater(() -> {
                mainPanel.showLoggedOutState();
                injector.getInstance(RuneAlyticsSettingsPanel.class).refreshLoginState();
                injector.getInstance(MatchmakingPanel.class).refreshLoginState();
            });
            return;
        }

        if (gs == GameState.HOPPING) return;

        if (gs == GameState.LOGGED_IN)
        {
            state.setLoggedIn(true);

            // (Re)start the live-map heartbeat now that the player is in-game.
            // Idempotent — a no-op if one is already running (e.g. world hop).
            // The heartbeat itself waits until the account is verified before
            // sending, so it is safe to start before verification completes.
            startHeartbeat();

            // Resolve the player's RSN.  getLocalPlayer() can briefly return null
            // during the early LOGGED_IN transition (before the character fully
            // spawns), so fall back to the stored verified username so we never
            // bail out and leave tabs in their logged-out (hidden) state.
            String username = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getName()
                    : null;
            if ((username == null || username.isEmpty()) && state.getVerifiedUsername() != null)
            {
                username = state.getVerifiedUsername();
            }

            // Refresh all sidebar panels that show login-state-dependent controls.
            // MatchmakingPanel is included here so its input/button enable correctly
            // as soon as the player is recognised as logged-in + verified.
            SwingUtilities.invokeLater(() -> {
                injector.getInstance(RuneAlyticsVerificationPanel.class).refreshLoginState();
                injector.getInstance(RuneAlyticsSettingsPanel.class).refreshLoginState();
                injector.getInstance(MatchmakingPanel.class).refreshLoginState();
            });

            if (username == null || username.isEmpty()) return;

            final String rsn = username.toLowerCase();

            if (!state.isVerified())
            {
                SwingUtilities.invokeLater(() -> mainPanel.showVerificationOnly());
                return;
            }

            executorService.submit(() ->
            {
                Map<String, Boolean> flags = apiClient.fetchFeatureFlags(rsn);
                boolean lootSync = flags.getOrDefault(FEATURE_LOOT, false);
                boolean matchEnabled = flags.getOrDefault(FEATURE_MATCHES, false);
                if (lootTrackerPanel != null) lootTrackerPanel.setSyncEnabled(lootSync);
                SwingUtilities.invokeLater(() ->
                        mainPanel.showMainFeatures(true, matchEnabled));
            });
        }
    }


    private void handlePostLogin()
    {
        String username = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName()
                : null;

        if (username == null || username.isEmpty()) return;

        if (!state.isVerified())
        {
            SwingUtilities.invokeLater(() -> mainPanel.showVerificationOnly());
            return;
        }

        executorService.submit(() -> {
            Map<String, Boolean> flags = apiClient.fetchFeatureFlags(username.toLowerCase());
            boolean lootSync = flags.getOrDefault(FEATURE_LOOT, false);
            if (lootTrackerPanel != null) lootTrackerPanel.setSyncEnabled(lootSync);
            // Loot Tracker tab is always visible; only Match Finder is flag-controlled
            Map<String, Boolean> tabFlags = new java.util.HashMap<>(flags);
            tabFlags.put(FEATURE_LOOT, true);
            SwingUtilities.invokeLater(() -> mainPanel.applyFeatureFlags(tabFlags));
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PLAYER SPAWNED
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned event)
    {
        if (event.getPlayer() != client.getLocalPlayer()) return;

        log.info("Local player spawned");

        // Always check per-account token and refresh the verification UI on login
        checkVerificationStatus();

        executorService.schedule(() -> {
            // Load local loot data regardless of verification — tracking always works locally.
            log.info("Post-login: loading local loot data (verified={})", state.isVerified());
            lootManager.loadFromStorage();
        }, 2, TimeUnit.SECONDS);

        executorService.schedule(
                () -> SwingUtilities.invokeLater(mainPanel::restoreLastTab),
                2_500, TimeUnit.MILLISECONDS);
    }

    @Subscribe
    public void onConfigChanged(net.runelite.client.events.ConfigChanged event)
    {
        if (!"runealytics".equals(event.getGroup())) return;
        String key = event.getKey();
        if ("bankPrivacy".equals(key) || "playerVisibility".equals(key))
        {
            SwingUtilities.invokeLater(() ->
                    injector.getInstance(RuneAlyticsSettingsPanel.class).refreshPrivacySettings());

            // Push the new preference to the site so it immediately knows whether
            // the player wants to be seen (visibility is enforced server-side).
            syncPrivacySettings();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PRIVACY SYNC
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Sends the current bank/player privacy preferences to the website on the
     * background executor. No-ops (inside the API client) when the account is not
     * verified yet, so it is always safe to call.
     */
    private void syncPrivacySettings()
    {
        if (!state.isVerified()) return;
        final PrivacySetting bank   = config.bankPrivacy();
        final PrivacySetting player = config.playerVisibility();
        executorService.execute(() -> apiClient.syncPrivacySettings(bank, player));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LIVE-MAP HEARTBEAT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Starts the repeating live-map heartbeat if it is not already running.
     *
     * <p>Called on every {@code LOGGED_IN} transition. Idempotent so repeated
     * transitions (e.g. world hopping, which momentarily passes through
     * {@code LOGGED_IN}) don't spawn duplicate tasks.</p>
     */
    private synchronized void startHeartbeat()
    {
        if (heartbeatTask != null && !heartbeatTask.isCancelled() && !heartbeatTask.isDone())
        {
            return;
        }
        log.debug("[Heartbeat] starting live-map heartbeat ({} ms period)", HEARTBEAT_PERIOD_MS);
        heartbeatTask = executorService.scheduleAtFixedRate(
                this::sendHeartbeatTick,
                HEARTBEAT_INITIAL_DELAY_MS,
                HEARTBEAT_PERIOD_MS,
                TimeUnit.MILLISECONDS);
    }

    /** Cancels the live-map heartbeat (called on logout / shutdown). */
    private synchronized void stopHeartbeat()
    {
        if (heartbeatTask != null)
        {
            log.debug("[Heartbeat] stopping live-map heartbeat");
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    /**
     * One heartbeat iteration: captures the live location, friends list and
     * ignore list on the client thread (those reads are client-thread-only) and
     * then hands the payload off to the API client on the executor.
     */
    private void sendHeartbeatTick()
    {
        if (!state.isLoggedIn() || !state.isVerified()) return;

        clientThread.invokeLater(() ->
        {
            if (client.getGameState() != GameState.LOGGED_IN) return;

            final PlayerLocationSnapshot location = PlayerLocationSnapshot.capture(client);
            final List<String> friends = readNames(client.getFriendContainer());
            final List<String> ignores = readNames(client.getIgnoreContainer());
            final PrivacySetting visibility = config.playerVisibility();

            executorService.execute(() ->
                    apiClient.sendHeartbeat(location, friends, ignores, visibility));
        });
    }

    /**
     * Reads the display names out of a RuneLite {@link NameableContainer}
     * (friends or ignores). MUST run on the client thread. Returns an empty list
     * when the container is unavailable.
     */
    private List<String> readNames(NameableContainer<? extends Nameable> container)
    {
        List<String> names = new ArrayList<>();
        if (container == null) return names;

        Nameable[] members = container.getMembers();
        if (members == null) return names;

        for (Nameable member : members)
        {
            if (member == null) continue;
            String name = member.getName();
            if (name != null && !name.isEmpty()) names.add(name);
        }
        return names;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  XP TRACKING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fires whenever the local player's XP changes in any skill.
     *
     * <p>Computes the XP delta since the last event and hands it off to
     * {@link XpTrackerManager#onXpGained}, which opens a 30-second accumulation
     * window on the first gain and sends a single batched POST at T+30s.
     * Nothing is sent per-tick here — all batching/scheduling lives in the manager.</p>
     *
     * <p>Also maintains the skilling-loot snapshot used by
     * {@link #onItemContainerChanged} to diff inventory changes during skilling sessions.</p>
     */
    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (!config.enableXpTracking() || !state.isLoggedIn() || !state.isVerified()) return;

        Skill skill = event.getSkill();
        if (skill == Skill.OVERALL) return;

        int current = event.getXp();
        Integer prev = previousXp.get(skill);

        // First observation for this skill — record a baseline and wait for a real gain
        if (prev == null)
        {
            previousXp.put(skill, current);
            return;
        }

        int gained = current - prev;
        previousXp.put(skill, current);

        if (gained <= 0 || gained < config.minXpGain()) return;

        // Snapshot game mode at XP-gain time (player may have world-hopped)
        updateCurrentGameMode();

        // Snapshot the player's location at XP-gain time (client thread) so the
        // batched /xp/batch POST can report where the XP was earned. Captured
        // here because the batch flushes off-thread where client reads are unsafe.
        state.setCurrentLocation(PlayerLocationSnapshot.capture(client));

        // ── Delegate XP batching to XpTrackerManager ──────────────────────────
        // The manager opens a 30-second window on the first call and accumulates
        // all subsequent gains within that window.  At T+30s it drains the buffer
        // and fires a single POST to /api/xp/batch.
        xpTrackerManager.onXpGained(skill, gained);

        // ── Skilling loot snapshot ────────────────────────────────────────────
        // Skipped when the lamp/book suppression window is active (issue #3 —
        // prevents equipment swaps after lamp XP from being attributed).
        if (SKILLING_TRACKED.contains(skill) && gameTickCount >= lampXpSuppressUntilTick)
        {
            String key = skill.getName();
            clientThread.invokeLater(() -> {
                if (!skillingSnapshot.containsKey(key))
                    skillingSnapshot.put(key, getCurrentInventory());
                skillingExpiry.put(key, System.currentTimeMillis() + SKILLING_SESSION_MS);
            });
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCHEDULED SYNC
    // ═════════════════════════════════════════════════════════════════════════

    @net.runelite.client.task.Schedule(
            period = 60000,
            unit   = ChronoUnit.MILLIS,
            asynchronous = true
    )
    public void syncDataScheduled()
    {
        if (!config.syncLootToServer() || !state.isLoggedIn() || !state.isVerified()) return;
        lootManager.uploadUnsyncedKills();
    }

    /**
     * Polls feature flags from the server every 60 seconds while the player is
     * logged in and verified. This keeps the UI consistent without relying solely
     * on the one-time fetch that happens on each game-state change.
     */
    @net.runelite.client.task.Schedule(
            period = 60000,
            unit   = ChronoUnit.MILLIS,
            asynchronous = true
    )
    public void pollFeatureFlags()
    {
        if (!state.isLoggedIn() || !state.isVerified()) return;
        String rsn = state.getVerifiedUsername();
        if (rsn == null || rsn.isEmpty()) return;

        Map<String, Boolean> flags = apiClient.fetchFeatureFlags(rsn);
        boolean lootSync    = flags.getOrDefault(FEATURE_LOOT,    false);
        boolean matchEnabled = flags.getOrDefault(FEATURE_MATCHES, false);

        if (lootTrackerPanel != null) lootTrackerPanel.setSyncEnabled(lootSync);
        SwingUtilities.invokeLater(() -> mainPanel.showMainFeatures(true, matchEnabled));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  WHISPERER FLUSH
    // ═════════════════════════════════════════════════════════════════════════

    private void flushWhispererLoot()
    {
        clientThread.invokeLater(() ->
        {
            whispererGroundItemWindow = false;

            final int kc = whispererParsedKC;
            whispererParsedKC = -1;

            if (whispererGroundItems.isEmpty())
            {
                log.warn("Whisperer flush: no items");
                return;
            }

            final List<ItemStack> loot = mergeItemStacks(whispererGroundItems);
            whispererGroundItems.clear();

            log.info("The Whisperer: merged into {} item types, KC={}", loot.size(), kc);
            lootManager.processPlayerLootWithGameKC("The Whisperer", loot, kc);
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Merges a list of {@link ItemStack}s with duplicate IDs into a single entry
     * per item ID by summing quantities.
     */
    private List<ItemStack> mergeItemStacks(List<ItemStack> raw)
    {
        Map<Integer, Integer> merged = new LinkedHashMap<>();
        for (ItemStack s : raw)
            merged.merge(s.getId(), s.getQuantity(), Integer::sum);

        List<ItemStack> result = new ArrayList<>(merged.size());
        for (Map.Entry<Integer, Integer> e : merged.entrySet())
            result.add(new ItemStack(e.getKey(), e.getValue()));
        return result;
    }

    private List<ItemStack> getCurrentInventory()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return Collections.emptyList();

        List<ItemStack> items = new ArrayList<>();
        for (Item item : inv.getItems())
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                items.add(new ItemStack(item.getId(), item.getQuantity()));
        return items;
    }

    /**
     * Reads a freshly-filled reward container and dispatches the items as
     * {@code source} loot.  Used for chests that don't surface a usable
     * WidgetLoaded event (Wilderness Loot Chest, Lunar/Moons of Peril Chest).
     */
    private void captureRewardContainer(String source, ItemContainer container)
    {
        if (container == null) return;
        Item[] arr = container.getItems();
        if (arr == null || arr.length == 0) return;

        List<ItemStack> items = new ArrayList<>();
        for (Item item : arr)
        {
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                items.add(new ItemStack(item.getId(), item.getQuantity()));
        }
        if (items.isEmpty()) return;

        log.info("Reward container '{}': {} items", source, items.size());
        lootManager.processPlayerLoot(source, items);
    }

    /**
     * Returns only the items that were GAINED (new or increased in quantity)
     * between {@code before} and {@code after} inventory snapshots.
     *
     * @param before snapshot taken before the action
     * @param after  snapshot taken after the action
     * @return list of gained {@link ItemStack}s (may be empty)
     */
    private List<ItemStack> diffInventory(List<ItemStack> before, List<ItemStack> after)
    {
        Map<Integer, Integer> beforeMap = new HashMap<>();
        for (ItemStack i : before) beforeMap.merge(i.getId(), i.getQuantity(), Integer::sum);

        Map<Integer, Integer> afterMap = new HashMap<>();
        for (ItemStack i : after) afterMap.merge(i.getId(), i.getQuantity(), Integer::sum);

        List<ItemStack> gained = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : afterMap.entrySet())
        {
            int delta = e.getValue() - beforeMap.getOrDefault(e.getKey(), 0);
            if (delta > 0) gained.add(new ItemStack(e.getKey(), delta));
        }
        return gained;
    }

    private void checkVerificationStatus()
    {
        if (client.getLocalPlayer() == null) return;
        String rawRsn = client.getLocalPlayer().getName();
        if (rawRsn == null) return;

        // RSN is stored/matched in lowercase to align with server normalisation
        final String rsn = rawRsn.trim().toLowerCase();

        RuneAlyticsVerificationPanel vp = injector.getInstance(RuneAlyticsVerificationPanel.class);
        String token = vp.loadAccountToken(rsn);

        if (token == null)
        {
            // No local token — definitely not verified
            applyVerificationState(false, rsn, null, vp);
            return;
        }

        // Local token exists — confirm it is still valid on the server before trusting it
        executorService.execute(() -> {
            boolean serverConfirmed = false;
            try
            {
                serverConfirmed = apiClient.verifyToken(token, rsn);
            }
            catch (Exception e)
            {
                // Network failure: keep existing state rather than clearing a valid token
                log.warn("Could not reach server to validate stored token for '{}': {}", rsn, e.getMessage());
                serverConfirmed = true; // optimistic — don't log out on connectivity issues
            }

            final boolean verified = serverConfirmed;
            if (!verified)
            {
                log.info("Stored token for '{}' rejected by server — clearing", rsn);
                vp.clearAccountToken(rsn);
            }

            applyVerificationState(verified, rsn, verified ? token : null, vp);
        });
    }

    private void applyVerificationState(boolean verified, String rsn, String token,
                                        RuneAlyticsVerificationPanel vp)
    {
        if (verified)
        {
            state.setVerified(true);
            state.setVerifiedUsername(rsn);
            state.setVerificationCode(token);
            // Mirror into the config field so it always reflects the active account
            config.authToken(token);
            log.info("Account '{}' is linked (server confirmed)", rsn);
        }
        else
        {
            state.setVerified(false);
            state.setVerifiedUsername(null);
            state.setVerificationCode(null);
            config.authToken("");
            log.info("Account '{}' is not linked", rsn);
        }

        SwingUtilities.invokeLater(() -> {
            RuneAlyticsSettingsPanel sp = injector.getInstance(RuneAlyticsSettingsPanel.class);
            sp.updateVerificationStatus(verified, verified ? rsn : null);
            vp.refreshLoginState();
            injector.getInstance(MatchmakingPanel.class).refreshLoginState();
        });

        // If the account is now verified, fetch feature flags and show the full
        // UI.  This path is needed because onGameStateChanged(LOGGED_IN) fires
        // before checkVerificationStatus() completes — at that point
        // state.isVerified() is still false, so the flag fetch and showMainFeatures
        // call are skipped.  Once verification is confirmed here we replay that
        // logic so every tab (including Match Finder) appears correctly.
        if (verified)
        {
            // Push current privacy preferences so the site's stored visibility
            // mirrors the in-client setting the moment the account links.
            syncPrivacySettings();

            executorService.submit(() ->
            {
                Map<String, Boolean> flags = apiClient.fetchFeatureFlags(rsn);
                SwingUtilities.invokeLater(() ->
                {
                    mainPanel.showMainFeatures(
                            flags.getOrDefault(FEATURE_LOOT, false),
                            flags.getOrDefault(FEATURE_MATCHES, false));
                    injector.getInstance(MatchmakingPanel.class).refreshLoginState();
                });
            });
        }
    }

    private void logConfiguration()
    {
        log.info("=== RUNEALYTICS CONFIG ===");
        log.info("Loot tracking     : {}", config.enableLootTracking());
        log.info("Track all NPCs    : {}", config.trackAllNpcs());
        log.info("Track pickpockets : {}", config.enablePickpocketTracking());
        log.info("Min loot value    : {}", config.minimumLootValue());
        log.info("Sync to server    : {}", config.syncLootToServer());
        log.info("Auto-verify       : {}", config.enableAutoVerification());
        log.info("API URL           : {}", config.apiUrl());
        log.info("=========================");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GAME-MODE DETECTION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Resolves the current game mode by checking world type first (temporary
     * session modes like Leagues/Deadman override the account type), then
     * falling back to the permanent account restriction.
     *
     * <p>World type takes precedence: an Ironman playing Leagues records as
     * {@code "leagues"}, not {@code "ironman"}.</p>
     *
     * <p>Grid Master is a plugin config toggle (not a native OSRS concept) and
     * overrides every other check when enabled.</p>
     *
     * @return one of "regular", "ironman", "leagues", "deadman",
     *         "fresh_start", or "grid_master"
     */
    private String determineGameMode()
    {
        if (config.gridMasterMode()) return "grid_master";

        EnumSet<WorldType> worldTypes = client.getWorldType();
        if (worldTypes != null)
        {
            if (worldTypes.contains(WorldType.DEADMAN))  return "deadman";
            if (worldTypes.contains(WorldType.SEASONAL)) return "leagues";
            // FRESH_START_WORLD was added in a later RuneLite version; guard with valueOf.
            try
            {
                WorldType fsr = WorldType.valueOf("FRESH_START_WORLD");
                if (worldTypes.contains(fsr)) return "fresh_start";
            }
            catch (IllegalArgumentException ignored) {}
        }

        String subtype = determineAccountSubtype();
        return "normal".equals(subtype) ? "regular" : "ironman";
    }

    /**
     * Returns the specific OSRS account subtype for fine-grained server-side
     * filtering, independent of the current world mode.
     *
     * <p>Uses reflection so the plugin compiles against any RuneLite version —
     * {@code AccountType} was added to {@code net.runelite.api} in a later
     * release and may not be present in all cached JARs.</p>
     *
     * @return one of "normal", "ironman", "hardcore_ironman",
     *         "ultimate_ironman", "group_ironman",
     *         "hardcore_group_ironman", "unranked_group_ironman"
     */
    private String determineAccountSubtype()
    {
        try
        {
            Object accountType = client.getClass()
                    .getMethod("getAccountType")
                    .invoke(client);
            if (accountType == null) return "normal";
            switch (accountType.toString())
            {
                case "IRONMAN":                return "ironman";
                case "HARDCORE_IRONMAN":       return "hardcore_ironman";
                case "ULTIMATE_IRONMAN":       return "ultimate_ironman";
                case "GROUP_IRONMAN":          return "group_ironman";
                case "HARDCORE_GROUP_IRONMAN": return "hardcore_group_ironman";
                case "UNRANKED_GROUP_IRONMAN": return "unranked_group_ironman";
                default:                       return "normal";
            }
        }
        catch (Exception e)
        {
            return "normal";
        }
    }

    /**
     * Snapshots the current game mode and account subtype into {@link RuneAlyticsState}
     * so they are available to API clients without re-reading the client on the EDT.
     * Call this at the start of any loot or XP event handler.
     */
    private void updateCurrentGameMode()
    {
        state.setCurrentGameMode(determineGameMode());
        state.setCurrentAccountSubtype(determineAccountSubtype());
        log.debug("[GameMode] mode={} subtype={} world={}",
                state.getCurrentGameMode(), state.getCurrentAccountSubtype(), client.getWorld());
    }

    private BufferedImage loadPluginIcon()
    {
        try
        {
            BufferedImage img = ImageUtil.loadImageResource(getClass(), "/runealytics_icon.png");
            if (img != null) return img;
        }
        catch (Exception e)
        {
            log.debug("Plugin icon not found, using fallback", e);
        }

        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(new java.awt.Color(255, 165, 0));
        g.fillRect(0, 0, 32, 32);
        g.setColor(new java.awt.Color(255, 215, 0));
        g.drawRect(0, 0, 31, 31);
        g.dispose();
        return img;
    }
}