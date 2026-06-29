package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.vars.AccountType;
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
        enabledByDefault = false
)
public class RuneAlyticsPlugin extends Plugin
{
    // ═════════════════════════════════════════════════════════════════════════
    //  WIDGET GROUP-IDs
    //  Aliases for the widgets that need bespoke handling in onWidgetLoaded.
    //  Canonical values live in RewardSources.
    // ═════════════════════════════════════════════════════════════════════════
    static final int WIDGET_WHISPERER  = RewardSources.WIDGET_WHISPERER;
    static final int WIDGET_WINTERTODT = RewardSources.WIDGET_WINTERTODT;
    static final int WIDGET_NIGHTMARE  = RewardSources.WIDGET_NIGHTMARE;
    static final int WIDGET_CLUE       = RewardSources.WIDGET_CLUE;

    // ── Timing ────────────────────────────────────────────────────────────────
    /** ms after a kill during which spawning ground items are attributed to it */
    private static final long GROUND_ITEM_WINDOW_MS      = 3_000;
    /** Seconds before lastKilledBoss is cleared. */
    private static final long BOSS_CLEAR_TIMEOUT_SECONDS = 30;

    // ─────────────────────────────────────────────────────────────────────────
    //  PICKPOCKET / THIEVING TRACKING CONSTANTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Window (ms) after a "Pickpocket" click during which inventory changes are
     * attributed to that pickpocket action.
     */
    private static final long PICKPOCKET_WINDOW_MS = 1_800;
    /**
     * Window (ms) an open skilling-snapshot stays live for inventory diffs to be
     * attributed to the skill that opened it.
     */
    private static final long SKILLING_SESSION_MS  = 1_500;

    /**
     * Tick window after a lamp/book menu click during which XP gains skip the
     * skilling-snapshot path.
     */
    private static final int  LAMP_XP_SUPPRESS_TICKS = 5;

    /** Plugin tick at which the lamp-XP suppression window expires (0 = none). */
    private long lampXpSuppressUntilTick = 0L;

    // AGILITY is excluded: its XP never produces an item drop, so tracking it
    // would misattribute incidental inventory changes during the session window.
    private static final Set<Skill> SKILLING_TRACKED = java.util.EnumSet.of(
            Skill.WOODCUTTING, Skill.FISHING,  Skill.MINING,    Skill.FARMING,
            Skill.HUNTER,      Skill.HERBLORE, Skill.RUNECRAFT, Skill.FLETCHING,
            Skill.COOKING,     Skill.SMITHING, Skill.CRAFTING
    );

    /** Menu options that grant XP without producing a skilling drop. */
    private static final Set<String> LAMP_XP_MENU_OPTIONS = java.util.Set.of(
            "rub", "read", "open", "use", "claim", "activate", "tear"
    );

    /** Every menu option onMenuOptionClicked acts on; used as a fast pre-filter. */
    private static final Set<String> RELEVANT_MENU_OPTIONS = java.util.Set.of(
            "rub", "read", "open", "use", "claim", "activate", "tear",
            "loot-jar", "loot", "pickpocket", "pick-pocket"
    );

    private final Map<String, List<ItemStack>> skillingSnapshot = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long>            skillingExpiry   = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Last-known equipment loadout, refreshed on every ItemContainerChanged.
     * Used to recognise equip/unequip swaps so they aren't mistaken for loot.
     */
    private List<ItemStack> equipmentSnapshot = Collections.emptyList();

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
    @Inject private LiveMapMinimapOverlay     liveMapOverlay;
    @Inject private OverlayManager           overlayManager;
    @Inject private CurrentPlayerIdentityService currentPlayerIdentity;
    @Inject private LootSyncMergeService     lootSyncMergeService;
    @Inject private DeathRecoveryGuard       deathRecoveryGuard;

    // ── UI ───────────────────────────────────────────────────────────────────
    @Getter private RuneAlyticsPanel mainPanel;
    private NavigationButton         navButton;
    // Assigned on the EDT during startUp, read from background executor threads.
    private volatile LootTrackerPanel lootTrackerPanel;

    // ── Live-map heartbeat ─────────────────────────────────────────────────────
    /** Heartbeat period (ms): how often live-map location, gear and inventory are pushed to the site. */
    private static final long HEARTBEAT_PERIOD_MS        = 20_000;
    /** Delay before the first heartbeat after login so player/RSN state settles. */
    private static final long HEARTBEAT_INITIAL_DELAY_MS = 5_000;
    /** Delay before the automatic post-login loot reconcile, so login isn't slowed. */
    private static final long LOGIN_AUTO_SYNC_DELAY_MS   = 6_000;
    /** Repeating heartbeat task; non-null only while the player is logged in. */
    private ScheduledFuture<?> heartbeatTask;

    // ── Bank sync debounce ─────────────────────────────────────────────────────
    /**
     * Quiet-period (ms) after the last bank container change before a wealth
     * snapshot is built and uploaded.
     */
    private static final long BANK_SYNC_DEBOUNCE_MS = 1_500;
    /** True while a debounced bank-sync task is already queued. */
    private final AtomicBoolean bankSyncScheduled = new AtomicBoolean(false);

    // ── Feature-flag change tracking ────────────────────────────────────────────
    /** Last loot-sync / match-enabled flags pushed to the UI. Null = not yet fetched. */
    private Boolean lastLootSyncFlag    = null;
    private Boolean lastMatchEnabledFlag = null;

    // ═════════════════════════════════════════════════════════════════════════
    //  LOOT TRACKING STATE
    // ═════════════════════════════════════════════════════════════════════════

    private List<ItemStack> inventorySnapshot        = null;
    private boolean         waitingForTemporossLoot  = false;
    private boolean         waitingForWintertodtLoot = false;
    /** Expiry time (ms) for the Tempoross/Wintertodt crate-loot wait window. */
    private long            crateLootWaitExpiry      = 0L;
    private static final long CRATE_LOOT_WINDOW_MS   = 60_000L;

    /**
     * True after the Whisperer KC chat message fires. While open, every
     * ItemSpawned near the player is collected and attributed to the kill.
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
    /** How long (ms) the RoW inventory snapshot is kept after an NPC kill. */
    private static final long ROW_WINDOW_MS = 4_000;
    /** Inventory snapshot taken at NPC kill to diff against after RoW message. */
    private List<ItemStack> rowInventorySnapshot = null;
    /** The boss NPC whose kill opened the current RoW snapshot window. */
    private NPC             rowSnapshotBoss      = null;
    /** Absolute expiry time (ms) for the RoW snapshot window. */
    private long            rowSnapshotExpiry    = 0L;

    /**
     * One open "ground loot attribution window" for a single NPC kill. Kept as
     * a list (rather than a single shared field) so that when an AOE attack
     * kills several NPCs — same type or not — within {@link #GROUND_ITEM_WINDOW_MS}
     * of each other, each kill gets its own buffer and location, and ground
     * items spawning nearby are matched to whichever kill they're actually
     * closest to instead of all being pooled onto the single most recent kill.
     */
    private static final class GroundLootSession
    {
        final NPC            npc;
        final WorldPoint     loc;
        final long           killTimeMs;
        final List<ItemStack> buffer         = new ArrayList<>();
        boolean              flushScheduled  = false;

        GroundLootSession(NPC npc, WorldPoint loc, long killTimeMs)
        {
            this.npc = npc;
            this.loc = loc;
            this.killTimeMs = killTimeMs;
        }
    }

    /** All mutations happen on the client thread (event handlers / invokeLater), so a plain list is safe. */
    private final List<GroundLootSession> groundLootSessions = new ArrayList<>();

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
    //  Imp catches give an "* impling jar" inventory item; the loot only
    //  materialises when the "Loot-jar" option is used on the jar. That action
    //  gives no XP, so the generic Hunter skilling diff misses it.
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
    //  NpcLootReceived fires only when a kill produced loot, so dry kills are
    //  captured here:
    //
    //    1. Track every NPC the local player damaged (HitsplatApplied with
    //       hitsplat.isMine())
    //    2. On ActorDeath for a damaged NPC, queue a pending zero-loot kill
    //       that flushes ZERO_LOOT_FLUSH_TICKS game ticks later
    //    3. If NpcLootReceived arrives first, the pending entry is cancelled
    //
    //  Chest-only bosses (raids, Nightmare, Yama, Royal Titans, etc.) are
    //  excluded inside LootTrackerManager#processZeroLootKill.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Game ticks to wait after ActorDeath before flushing a pending zero-loot
     * kill, allowing NpcLootReceived time to cancel it. 3 ticks ≈ 1.8 s.
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
        log.debug("RuneAlytics starting");

        // Build the root panel on the EDT, then register the nav button.
        buildOnEdt(() -> mainPanel = injector.getInstance(RuneAlyticsPanel.class));

        navButton = NavigationButton.builder()
                .tooltip("RuneAlytics")
                .icon(loadPluginIcon())
                .priority(1)
                .panel(mainPanel)
                .build();
        clientToolbar.addNavigation(navButton);
        overlayManager.add(matchmakingOverlay);
        overlayManager.add(liveMapOverlay);
        log.debug("RuneAlytics nav button registered");

        // Construct the heavy sub-panels on the EDT.
        SwingUtilities.invokeLater(() ->
        {
            try
            {
                LootTrackerPanel        lootPanel        = injector.getInstance(LootTrackerPanel.class);
                MatchmakingPanel        matchmakingPanel = injector.getInstance(MatchmakingPanel.class);
                RuneAlyticsSettingsPanel settingsPanel   = injector.getInstance(RuneAlyticsSettingsPanel.class);
                // Create the verification panel singleton on the EDT.
                injector.getInstance(RuneAlyticsVerificationPanel.class);

                lootTrackerPanel = lootPanel;
                // Give the panel a reference to this plugin for the new buttons.
                lootPanel.setPlugin(this);
                // Sync starts in a neutral "checking…" state until feature flags
                // confirm whether it is active — never assert it's turned off
                // before we actually know (it defaults ON for verified players).
                lootPanel.setSyncChecking();

                // Loot Tracker has no feature gate — local tracking is always available.
                // Sync availability is controlled separately via setSyncEnabled().
                mainPanel.addTab("Loot Tracker", null,             lootPanel);
                mainPanel.addTab("Matches",      FEATURE_MATCHES,  matchmakingPanel);
                mainPanel.addTab("Settings",     FEATURE_VERIFICATION, settingsPanel);
                log.debug("RuneAlytics tabs populated");
            }
            catch (Exception ex)
            {
                log.error("Failed to populate RuneAlytics tabs", ex);
            }
        });
    }

    /**
     * Runs {@code r} on the Swing EDT and waits for it to finish. Runs inline if
     * already on the EDT, otherwise blocks via {@link SwingUtilities#invokeAndWait}.
     * Used so panel (Swing) construction never happens off the EDT.
     */
    private static void buildOnEdt(Runnable r) throws Exception
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            r.run();
        }
        else
        {
            SwingUtilities.invokeAndWait(r);
        }
    }

    @Override
    protected void shutDown()
    {
        log.debug("RuneAlytics shutting down");

        // Stop the live-map heartbeat so no pings fire after the plugin is gone.
        stopHeartbeat();

        // Cancel the pending whisperer loot flush.
        if (whispererFlushTask != null)
        {
            whispererFlushTask.cancel(false);
            whispererFlushTask = null;
        }

        // Flush accumulated XP before the executor shuts down.
        try { xpTrackerManager.flushImmediate(); } catch (Exception e) { log.warn("XP flush on shutdown failed: {}", e.getMessage()); }
        try { lootManager.shutdown();             } catch (Exception e) { log.warn("Loot manager shutdown failed: {}", e.getMessage()); }
        try { matchmakingManager.reset();         } catch (Exception e) { log.warn("Matchmaking reset on shutdown failed: {}", e.getMessage()); }
        try { overlayManager.remove(matchmakingOverlay); } catch (Exception e) { log.warn("Matchmaking overlay removal failed: {}", e.getMessage()); }
        try { overlayManager.remove(liveMapOverlay);     } catch (Exception e) { log.warn("Live-map overlay removal failed: {}", e.getMessage()); }

        // Remove the nav button.
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
        crateLootWaitExpiry       = 0L;
        whispererGroundItemWindow = false;
        whispererKillTime         = null;
        whispererGroundItems.clear();
        // Cancel the pending Whisperer flush.
        if (whispererFlushTask != null)
        {
            whispererFlushTask.cancel(false);
            whispererFlushTask = null;
        }
        groundLootSessions.clear();

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

        // Capture game mode at kill time so the record reflects the current world.
        updateCurrentGameMode();

        // Cancel any pending zero-loot kill for this NPC.
        pendingDeaths.remove(npc.getIndex());
        damagedNpcs.remove(npc.getIndex());

        lastKilledBoss = npc;
        lastKillTime   = Instant.now();

        // Open a dedicated ground-loot attribution window for THIS kill, so an
        // AOE kill of several NPCs at once doesn't pool everyone's drops onto
        // whichever NPC happens to die last.
        WorldPoint killLoc = npc.getWorldLocation();
        if (killLoc != null)
        {
            groundLootSessions.add(new GroundLootSession(npc, killLoc, Instant.now().toEpochMilli()));
        }

        // Snapshot inventory to diff against if Ring of Wealth auto-collects coins.
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
    //  These three handlers feed the pendingDeaths map, which onGameTick drains.
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event)
    {
        Actor target = event.getActor();
        Hitsplat hs   = event.getHitsplat();

        // ── Matchmaking: a hit between the two participants starts the fight ──
        // Runs independently of loot tracking.
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

        // ── Death recovery guard: detect local player death ───────────────────
        deathRecoveryGuard.onActorDeath(event);

        // ── Matchmaking: report player deaths to server ───────────────────────
        if (actor instanceof Player)
        {
            matchmakingManager.onActorDeath((Player) actor);
        }

        if (!config.enableLootTracking()) return;

        if (!(actor instanceof NPC)) return;

        NPC npc = (NPC) actor;
        if (!damagedNpcs.containsKey(npc.getIndex())) return;

        // ActorDeath fires at the start of the death animation, but
        // NpcLootReceived can lag 1–2 ticks. Wait ZERO_LOOT_FLUSH_TICKS before
        // treating the kill as zero-loot.
        pendingDeaths.put(
                npc.getIndex(),
                new PendingDeath(npc, gameTickCount + ZERO_LOOT_FLUSH_TICKS));
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event)
    {
        NPC npc = event.getNpc();
        if (npc == null) return;

        // Drop the damage-tracking entry once the NPC is gone. Any pendingDeaths
        // entry is left in place so the zero-loot kill is still recorded.
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
        // ── Death recovery guard: detect gravestone / Death's Office UI ───────
        deathRecoveryGuard.onWidgetLoaded(event);

        if (!config.enableLootTracking()) return;

        int gid = event.getGroupId();
        log.debug("WidgetLoaded: groupId={}", gid);

        // ── Special cases that can't use the generic registry ────────────────
        if (gid == WIDGET_WHISPERER)
        {
            log.debug("Whisperer widget 834 detected — waiting for drops (KC={})", whispererParsedKC);
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
     *   <li><b>Tempoross / Wintertodt</b> – fallback diff logic.</li>
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
        // Runs on the client thread, so ItemContainer reads are safe.
        matchmakingManager.onItemContainerChanged(event);

        // ── Bank sync (debounced) ───────────────────────────────────────────
        if (event.getContainerId() == InventoryID.BANK.getId()
                && config.enableBankSync()
                && state.isLoggedIn()
                && state.isVerified())
        {
            scheduleBankSync();
            return;
        }

        if (!config.enableLootTracking()) return;

        // ── Wilderness Loot Chest & Lunar (Moons of Peril) Chest ─────────────
        // These chests fire no usable WidgetLoaded; read the container directly.
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
            // ── Death recovery guard: suppressed inventory changes are ignored ─
            // NpcLootReceived/PlayerLootReceived paths don't need this guard
            // because they are confirmed kill attributions from RuneLite.
            if (deathRecoveryGuard.shouldSuppressLootEvent())
            {
                log.debug("[death-guard] Inventory diff suppressed during recovery mode");
                return;
            }

            // ── Equip/unequip detection (must run first) ──────────────────────
            // Find items that just left equipment; their ids are subtracted from
            // every inventory gain below so an unequip isn't mistaken for a drop.
            List<ItemStack> currentEquipment = getCurrentEquipment();
            List<ItemStack> justUnequipped   = diffInventory(currentEquipment, equipmentSnapshot);
            equipmentSnapshot = currentEquipment;

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
                    List<ItemStack> gained     = excludeEquipmentMovement(
                            diffInventory(impJarInventorySnapshot, currentInv), justUnequipped);
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
                List<ItemStack> gained  = excludeEquipmentMovement(
                        diffInventory(inventorySnapshot, current), justUnequipped);

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
                    List<ItemStack> items = excludeEquipmentMovement(diffInventory(snap, inv), justUnequipped);
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
            List<ItemStack> gained  = excludeEquipmentMovement(
                    diffInventory(pickpocketInventorySnapshot, current), justUnequipped);

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

        // Fast pre-filter: ignore irrelevant clicks before any string work.
        String lowerOption = option.toLowerCase();
        if (!RELEVANT_MENU_OPTIONS.contains(lowerOption)) return;

        String rawTarget = event.getMenuTarget();
        if (rawTarget == null || rawTarget.isEmpty()) return;

        String targetName = rawTarget.replaceAll("<[^>]*>", "").trim();
        if (targetName.isEmpty()) return;

        // ── Lamp / book / genie / scroll XP suppression ──────────────────────
        // These grant XP without a drop; suppress the skilling snapshot window
        // so the next inventory change isn't attributed to the skill.
        String lowerTarget = targetName.toLowerCase();
        if (LAMP_XP_MENU_OPTIONS.contains(lowerOption)
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
        if (itemLoc == null || groundLootSessions.isEmpty()) return;

        long now = Instant.now().toEpochMilli();
        groundLootSessions.removeIf(s -> now - s.killTimeMs > GROUND_ITEM_WINDOW_MS);
        if (groundLootSessions.isEmpty()) return;

        // Multiple kills (same or different NPC types) can have open, overlapping
        // attribution windows at once — e.g. an AOE attack that kills several
        // NPCs together. Attribute this item to whichever recent kill happened
        // closest to it, rather than to "the last kill" unconditionally.
        GroundLootSession best = null;
        int bestDist = Integer.MAX_VALUE;
        for (GroundLootSession s : groundLootSessions)
        {
            int dist = itemLoc.distanceTo(s.loc);
            if (dist <= 5 && dist < bestDist)
            {
                best = s;
                bestDist = dist;
            }
        }
        if (best == null) return;

        final GroundLootSession session = best;
        session.buffer.add(new ItemStack(tile.getId(), tile.getQuantity()));

        if (!session.flushScheduled)
        {
            session.flushScheduled = true;

            executorService.schedule(() ->
                            clientThread.invokeLater(() ->
                            {
                                if (!session.buffer.isEmpty())
                                {
                                    List<ItemStack> batch = new ArrayList<>(session.buffer);
                                    session.buffer.clear();
                                    session.flushScheduled = false;
                                    lootManager.processGroundItemBatch(session.npc, batch);
                                }
                                else
                                {
                                    session.flushScheduled = false;
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
        // ── Death recovery guard: detect death messages ───────────────────────
        deathRecoveryGuard.onChatMessage(event);

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
                crateLootWaitExpiry     = System.currentTimeMillis() + CRATE_LOOT_WINDOW_MS;
                log.debug("Tempoross: inventory snapshot taken ({} items)", inventorySnapshot.size());
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
                crateLootWaitExpiry      = System.currentTimeMillis() + CRATE_LOOT_WINDOW_MS;
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

            log.debug("The Whisperer: KC detected (game KC={}) – ground-item collection window opened",
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
                        log.debug("[Pet] drop detected for '{}' (wentToBank={})", bossName, wentToBank);
                    });
                }
            }
            return;
        }

        // ── Ring of Wealth auto-pickup ────────────────────────────────────────
        // Game messages (imbued RoW):
        //   "Your ring of wealth has automatically picked up the coins."
        //   "Your ring of wealth has automatically alched the <item>."
        // Auto-picked items bypass ItemSpawned; diff inventory against the
        // snapshot taken at the NPC kill and append all gained items to the
        // last kill record.
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
                        log.debug("Ring of Wealth: {} item type(s) appended to '{}'",
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
            log.debug("Chat: chest source set to '{}'", detected);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GAME TICK
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        gameTickCount++;

        // ── Death recovery guard: update region tracking ──────────────────────
        deathRecoveryGuard.onGameTick();

        // ── Matchmaking polling / automation ─────────────────────────────────
        // Runs on the client thread, so inventory/gear reads are safe.
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

        // ── Expire stale Tempoross/Wintertodt crate-loot wait windows ─────────
        if ((waitingForTemporossLoot || waitingForWintertodtLoot)
                && System.currentTimeMillis() > crateLootWaitExpiry)
        {
            log.debug("Crate-loot wait window expired (Tempoross={}, Wintertodt={})",
                    waitingForTemporossLoot, waitingForWintertodtLoot);
            waitingForTemporossLoot  = false;
            waitingForWintertodtLoot = false;
            inventorySnapshot        = null;
            crateLootWaitExpiry      = 0L;
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
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GAME STATE CHANGE
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState gs = event.getGameState();
        log.debug("GameState: {}", gs);

        // ── Death recovery guard: reset on logout/hop ─────────────────────────
        deathRecoveryGuard.onGameStateChanged(event);

        if (gs == GameState.LOGIN_SCREEN)
        {
            // Flush this account's loot to the server BEFORE marking logged-out,
            // so the sync slot can still be claimed and the live-sync path can't
            // double-upload. Upload-only; runs async so logout isn't delayed.
            performLogoutSyncFlush();

            // Clear the in-memory + on-screen loot so the panel resets to empty
            // and a different account logging in next can't inherit this
            // account's loot (which would then sync under the wrong account).
            lootManager.resetForLogout();

            state.setLoggedIn(false);
            // Stop the live-map heartbeat while at the login screen.
            stopHeartbeat();

            // Reset per-account tracking state so nothing leaks across an account switch.
            clearTransientLootState();
            previousXp.clear();
            lastLootSyncFlag     = null;
            lastMatchEnabledFlag = null;

            matchmakingManager.reset(); // clear any active match on logout
            // Back to the neutral "unknown" state — the next login re-fetches the
            // flag rather than leaving a stale "turned off" message behind.
            if (lootTrackerPanel != null) lootTrackerPanel.setSyncChecking();
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
            // Idempotent; the heartbeat waits until the account is verified.
            startHeartbeat();

            // Resolve the player's RSN, falling back to the stored verified
            // username (getLocalPlayer() can briefly be null during early LOGGED_IN).
            String username = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getName()
                    : null;
            if ((username == null || username.isEmpty()) && state.getVerifiedUsername() != null)
            {
                username = state.getVerifiedUsername();
            }

            // Refresh sidebar panels that show login-state-dependent controls.
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

                // Auto-reconcile loot on login. Delayed so the login itself
                // isn't slowed and the client has settled (local player + item
                // cache ready); runs on the background executor.
                if (lootSync && config.syncLootToServer())
                {
                    executorService.schedule(
                            () -> performLootSync(false),
                            LOGIN_AUTO_SYNC_DELAY_MS,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            });
        }
    }


    // ═════════════════════════════════════════════════════════════════════════
    //  PLAYER SPAWNED
    // ═════════════════════════════════════════════════════════════════════════

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned event)
    {
        if (event.getPlayer() != client.getLocalPlayer()) return;

        log.debug("Local player spawned");

        // Cache the account name now (client thread) so a logout flush can scope
        // the sync after the local player is gone.
        currentPlayerIdentity.rememberCurrentPlayer();

        // Always check per-account token and refresh the verification UI on login
        checkVerificationStatus();

        executorService.schedule(() -> {
            // Load local loot data regardless of verification — tracking always works locally.
            log.debug("Post-login: loading local loot data (verified={})", state.isVerified());
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

            // Push the new visibility preference to the site.
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

    /**
     * Coalesces a burst of bank container changes into a single wealth snapshot
     * + upload {@value #BANK_SYNC_DEBOUNCE_MS} ms after the last change. The
     * snapshot is built on the client thread (ItemManager reads are
     * client-thread-only) and only the HTTP call is handed to the executor.
     */
    private void scheduleBankSync()
    {
        if (!bankSyncScheduled.compareAndSet(false, true)) return;

        executorService.schedule(() -> clientThread.invokeLater(() ->
        {
            // Cleared first so changes that arrive while we build the snapshot
            // schedule a fresh follow-up sync.
            bankSyncScheduled.set(false);

            if (!config.enableBankSync() || !state.isLoggedIn() || !state.isVerified()) return;

            ItemContainer bank = client.getItemContainer(InventoryID.BANK);
            if (bank == null) return;

            final String token    = state.getVerificationCode();
            final String username = state.getVerifiedUsername();
            final JsonObject snapshot = bankDataManager.buildBankSnapshot(
                    username,
                    client.getWorld(),
                    bank,
                    client.getItemContainer(InventoryID.INVENTORY),
                    client.getItemContainer(InventoryID.EQUIPMENT));
            executorService.execute(() -> bankDataManager.syncBankData(token, username, snapshot));
        }), BANK_SYNC_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
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
        // Drop any cached map players so the overlay clears immediately on logout.
        state.setVisibleMapPlayers(new ArrayList<>());
    }

    /**
     * One heartbeat iteration: captures location, friends and ignore lists on
     * the client thread, then hands the payload to the API client on the executor.
     *
     * <p>When location visibility is private, the real coordinates are replaced
     * with a decoy here (client-side) before the payload is built; the real
     * location is never serialized or sent.</p>
     */
    private void sendHeartbeatTick()
    {
        if (!state.isLoggedIn() || !state.isVerified()) return;

        clientThread.invokeLater(() ->
        {
            if (client.getGameState() != GameState.LOGGED_IN) return;

            final PrivacySetting visibility    = config.playerVisibility();
            final PrivacySetting gearVisibility = config.bankPrivacy();

            // Private players' real coordinates are replaced with a decoy here,
            // before the payload leaves the client thread.
            final PlayerLocationSnapshot location =
                    PlayerLocationSnapshot.captureRespectingPrivacy(client, visibility);

            final List<String> friends = readNames(client.getFriendContainer());
            final List<String> ignores = readNames(client.getIgnoreContainer());

            // Read equipment/inventory on the client thread and convert to JSON
            // immediately so the executor never touches live ItemContainers.
            final JsonArray equipment = RuneAlyticsItemJson.fromEquipment(client.getItemContainer(InventoryID.EQUIPMENT));
            final JsonArray inventory = RuneAlyticsItemJson.fromContainer(client.getItemContainer(InventoryID.INVENTORY));

            // Non-authoritative preview of the in-progress 30s XP batch window.
            final Map<String, Integer> xpPreview = xpTrackerManager.peekPendingGains();

            executorService.execute(() ->
                    apiClient.sendHeartbeat(location, friends, ignores, visibility,
                            equipment, inventory, gearVisibility, xpPreview));
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
        // batched /xp/batch POST can report where the XP was earned.
        // captureRespectingPrivacy keeps a private player's real coordinates out
        // of state.currentLocation.
        state.setCurrentLocation(
                PlayerLocationSnapshot.captureRespectingPrivacy(client, config.playerVisibility()));

        // ── Delegate XP batching to XpTrackerManager ──────────────────────────
        // The manager opens a 30-second window on the first call and accumulates
        // all subsequent gains within that window.  At T+30s it drains the buffer
        // and fires a single POST to /api/xp/batch.
        xpTrackerManager.onXpGained(skill, gained);

        // ── Skilling loot snapshot ────────────────────────────────────────────
        // Skipped while the lamp/book suppression window is active.
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
     * Unified loot sync triggered by the single "Sync" button and by the
     * automatic login sync.
     *
     * <p>Runs the full pipeline for the <em>currently logged-in</em> account:
     * pull server history → import RuneLite tracker → upload unsynced kills →
     * three-source absolute-merge reconcile → upload. All scoped to the live
     * account so no other profile's data can leak in.</p>
     *
     * <p>Blocked (with a UI message when {@code userInitiated}) if no account is
     * logged in or the logged-in account doesn't match the linked RuneAlytics
     * account. Always runs off the client thread.</p>
     */
    public void performLootSync(boolean userInitiated)
    {
        if (!currentPlayerIdentity.canSync())
        {
            if (userInitiated && lootTrackerPanel != null)
            {
                String msg = currentPlayerIdentity.getMismatchMessage();
                if (msg != null) lootTrackerPanel.showAccountMismatch(msg);
                // getMismatchMessage() should never be null when canSync() is
                // false, but guard against a TOCTOU race so the button (already
                // showing "Syncing…") is never left stuck.
                else                lootTrackerPanel.showSyncBusy("Log in to sync loot…");
            }
            return;
        }

        final String accountKey = currentPlayerIdentity.getAccountKey();
        if (accountKey == null)
        {
            // canSync() was true a moment ago but the player just logged out.
            if (userInitiated && lootTrackerPanel != null)
            {
                lootTrackerPanel.showSyncBusy("Log in to sync loot…");
            }
            return;
        }

        executorService.submit(() ->
        {
            if (!state.tryStartSync())
            {
                // A sync (live/auto/manual) is already running. Reset the button
                // so a manual click doesn't get stuck on "Syncing…".
                if (userInitiated && lootTrackerPanel != null)
                {
                    SwingUtilities.invokeLater(() ->
                            lootTrackerPanel.showSyncBusy("A sync is already running…"));
                }
                return;
            }
            try     { runSyncPipeline(accountKey, true, userInitiated); }
            finally { state.endSync(); }
        });
    }

    /**
     * Flushes the current account's loot to the server on logout.
     *
     * <p>Must be called on the client thread <em>before</em> {@code loggedIn} is
     * flipped to {@code false}, so the sync slot can be claimed while the
     * session is still valid. Upload-only (no pull) to keep logout snappy.</p>
     */
    private void performLogoutSyncFlush()
    {
        final String accountKey = currentPlayerIdentity.getLastKnownAccountKey();
        if (accountKey == null) return;
        if (!currentPlayerIdentity.isLinkedAccount(accountKey)) return;

        // Claim the slot synchronously while still logged in; the live-sync
        // path therefore cannot also upload and double-count.
        if (!state.tryStartSync()) return;

        executorService.submit(() ->
        {
            try     { runSyncPipeline(accountKey, false, false); }
            finally { state.endSync(); }
        });
    }

    /**
     * Shared sync body. The caller MUST already hold the sync slot.
     *
     * @param accountKey    normalized account to scope every step to
     * @param pull          when {@code true}, also pull server history + import
     *                      RuneLite tracker before uploading; {@code false} for
     *                      an upload-only logout flush
     * @param userInitiated when {@code true}, surface failures in the UI
     */
    private void runSyncPipeline(String accountKey, boolean pull, boolean userInitiated)
    {
        try
        {
            log.debug("[plugin] Loot sync start (account='{}', pull={})", accountKey, pull);

            if (userInitiated && lootTrackerPanel != null)
            {
                SwingUtilities.invokeLater(() -> lootTrackerPanel.showSyncPhase("Syncing with server…"));
            }

            // 1. Legacy per-kill website history pull + upload.
            lootManager.syncLegacyBlocking(accountKey, pull);

            if (userInitiated && lootTrackerPanel != null)
            {
                SwingUtilities.invokeLater(() -> lootTrackerPanel.showSyncPhase("Syncing RuneLite tracker…"));
            }

            // 2. Absolute-merge reconcile: website + RuneLite's own rsprofile
            //    loot tracker file, read fresh every sync and scoped to this
            //    account's OSRS username.
            LootSyncMergeService.MergeResult result =
                    lootSyncMergeService.performMergeForAccount(accountKey);

            // The merge writes straight to LootStorageData, bypassing the
            // in-memory display cache — rebuild it now so the panel reflects
            // the merged KCs/drops (and any newly-empty placeholders get
            // purged) instead of showing a stale pre-merge snapshot.
            lootManager.refreshFromStorage();

            SwingUtilities.invokeLater(() ->
            {
                if (lootTrackerPanel == null) return;
                if (result.isSuccess())          lootTrackerPanel.showAbsoluteMergeResult(result);
                else if (userInitiated)          lootTrackerPanel.showSyncFailed(result.getBlockedReason());
            });
        }
        catch (Throwable t)
        {
            // Catch Throwable (not just Exception) so an Error — e.g. an
            // ItemManager "must be called on client thread" assertion — still
            // resets the Sync button instead of leaving it stuck on "Syncing…".
            log.error("[plugin] Loot sync failed", t);
            if (userInitiated)
            {
                SwingUtilities.invokeLater(() -> {
                    if (lootTrackerPanel != null) lootTrackerPanel.showSyncFailed(t.getMessage());
                });
            }
        }
    }

    /**
     * Exposes the DeathRecoveryGuard to the panel UI so the "End Recovery Mode"
     * button can call it.
     */
    public DeathRecoveryGuard getDeathRecoveryGuard()
    {
        return deathRecoveryGuard;
    }

    /**
     * Exposes the CurrentPlayerIdentityService for panel account-status display.
     */
    public CurrentPlayerIdentityService getCurrentPlayerIdentity()
    {
        return currentPlayerIdentity;
    }
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

        // Only touch the UI when a flag actually changed.
        if (lastLootSyncFlag == null || lastLootSyncFlag != lootSync)
        {
            lastLootSyncFlag = lootSync;
            if (lootTrackerPanel != null) lootTrackerPanel.setSyncEnabled(lootSync);
        }
        if (lastMatchEnabledFlag == null || lastMatchEnabledFlag != matchEnabled)
        {
            lastMatchEnabledFlag = matchEnabled;
            final boolean me = matchEnabled;
            SwingUtilities.invokeLater(() -> mainPanel.showMainFeatures(true, me));
        }
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

            log.debug("The Whisperer: merged into {} item types, KC={}", loot.size(), kc);
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

    private List<ItemStack> getCurrentEquipment()
    {
        ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eq == null) return Collections.emptyList();

        List<ItemStack> items = new ArrayList<>();
        for (Item item : eq.getItems())
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                items.add(new ItemStack(item.getId(), item.getQuantity()));
        return items;
    }

    /**
     * Strips equip/unequip noise out of an inventory-gain list.
     *
     * <p>Unequipping an item makes it appear in the inventory exactly like a
     * drop would, so any "gained" item that just disappeared from the
     * equipment container in the same tick is removed here before the
     * remainder is handed to the loot manager.</p>
     *
     * @param gained        items the caller believes were gained
     * @param justUnequipped items that disappeared from equipment this tick
     *                       (see {@link #onItemContainerChanged})
     */
    private List<ItemStack> excludeEquipmentMovement(List<ItemStack> gained, List<ItemStack> justUnequipped)
    {
        if (gained.isEmpty() || justUnequipped.isEmpty()) return gained;

        Map<Integer, Integer> unequippedMap = new HashMap<>();
        for (ItemStack i : justUnequipped) unequippedMap.merge(i.getId(), i.getQuantity(), Integer::sum);

        List<ItemStack> filtered = new ArrayList<>();
        for (ItemStack i : gained)
        {
            int unequippedQty = unequippedMap.getOrDefault(i.getId(), 0);
            int remaining     = i.getQuantity() - unequippedQty;
            if (remaining > 0) filtered.add(new ItemStack(i.getId(), remaining));
        }
        return filtered;
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

        log.debug("Reward container '{}': {} items", source, items.size());
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
                log.debug("Stored token for '{}' rejected by server — clearing", rsn);
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
            log.debug("Account '{}' is linked (server confirmed)", rsn);
        }
        else
        {
            state.setVerified(false);
            state.setVerifiedUsername(null);
            state.setVerificationCode(null);
            config.authToken("");
            log.debug("Account '{}' is not linked", rsn);
        }

        SwingUtilities.invokeLater(() -> {
            RuneAlyticsSettingsPanel sp = injector.getInstance(RuneAlyticsSettingsPanel.class);
            sp.updateVerificationStatus(verified, verified ? rsn : null);
            vp.refreshLoginState();
            injector.getInstance(MatchmakingPanel.class).refreshLoginState();
        });

        // Fetch feature flags and show the full UI once verification is
        // confirmed (onGameStateChanged(LOGGED_IN) runs before this completes).
        if (verified)
        {
            // Push current privacy preferences so the site mirrors the in-client setting.
            syncPrivacySettings();

            executorService.submit(() ->
            {
                // Now that the verified account is known, (re)load that account's
                // own loot. No-op if the post-spawn load already loaded it; forces
                // a reload if it had loaded a stale/previous account.
                lootManager.loadFromStorage();

                Map<String, Boolean> flags = apiClient.fetchFeatureFlags(rsn);
                boolean lootSync = flags.getOrDefault(FEATURE_LOOT, false);

                // LOGGED_IN fired before verification completed, so its
                // setSyncEnabled() call was skipped. Apply the loot flag here so
                // the Sync button reflects reality immediately instead of staying
                // in its "checking…" state until the next 60s feature poll.
                lastLootSyncFlag = lootSync;
                if (lootTrackerPanel != null) lootTrackerPanel.setSyncEnabled(lootSync);

                SwingUtilities.invokeLater(() ->
                {
                    mainPanel.showMainFeatures(
                            lootSync,
                            flags.getOrDefault(FEATURE_MATCHES, false));
                    injector.getInstance(MatchmakingPanel.class).refreshLoginState();
                });
            });
        }
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
            if (worldTypes.contains(WorldType.DEADMAN))           return "deadman";
            if (worldTypes.contains(WorldType.SEASONAL))          return "leagues";
            if (worldTypes.contains(WorldType.FRESH_START_WORLD)) return "fresh_start";
        }

        String subtype = determineAccountSubtype();
        return "normal".equals(subtype) ? "regular" : "ironman";
    }

    /**
     * Returns the specific OSRS account subtype for fine-grained server-side
     * filtering, independent of the current world mode.
     *
     * @return one of "normal", "ironman", "hardcore_ironman",
     *         "ultimate_ironman", "group_ironman", "hardcore_group_ironman"
     */
    private String determineAccountSubtype()
    {
        AccountType accountType = client.getAccountType();
        if (accountType == null) return "normal";
        switch (accountType)
        {
            case IRONMAN:                return "ironman";
            case HARDCORE_IRONMAN:       return "hardcore_ironman";
            case ULTIMATE_IRONMAN:       return "ultimate_ironman";
            case GROUP_IRONMAN:          return "group_ironman";
            case HARDCORE_GROUP_IRONMAN: return "hardcore_group_ironman";
            default:                     return "normal";
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
            BufferedImage img = ImageUtil.loadImageResource(getClass(), "/runealytics_logo.png");
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