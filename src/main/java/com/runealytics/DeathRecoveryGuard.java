package com.runealytics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Guards loot recording against false positives during death-recovery events.
 *
 * <h2>Problem</h2>
 * <p>When a player dies and recovers items from a Gravestone or Death's Office,
 * the RuneLite event bus fires {@link ItemContainerChanged} and {@link ItemSpawned}
 * events that look identical to normal loot drops.  Without this guard every
 * piece of recovered gear would be counted as new boss loot.</p>
 *
 * <h2>Suppression window</h2>
 * <ol>
 *   <li>A death is detected via one of:
 *       <ul>
 *         <li>{@link ActorDeath} where the dying actor is the local player.</li>
 *         <li>A recognised death chat message (e.g. "Oh dear, you are dead!").</li>
 *         <li>The player entering the Death's Office region (8543).</li>
 *       </ul>
 *   </li>
 *   <li>Suppression lasts a minimum of {@value #DEATH_SUPPRESSION_MS} ms
 *       ({@value #DEATH_SUPPRESSION_MINUTES} minutes) from the death time.</li>
 *   <li>Suppression is extended while the player is in the Death's Office
 *       region or while a gravestone widget is open.</li>
 *   <li>Suppression ends when {@link #endRecoveryMode()} is called explicitly
 *       (UI "End Recovery Mode" button) or when the window expires and the
 *       player is no longer in a recovery region.</li>
 * </ol>
 *
 * <h2>Valid loot attribution requirement</h2>
 * <p>Callers should use {@link #shouldSuppressLootEvent()} before recording any
 * item gain that is NOT confirmed by a {@code LootReceived} event (i.e. any
 * item gain read from an inventory diff, ground item, or container change).
 * {@code LootReceived} events from the RuneLite bus are never suppressed
 * because they require a server-confirmed kill attribution.</p>
 *
 * <h2>Thread safety</h2>
 * <p>All state mutations in this class originate on the RuneLite client thread.
 * {@link #shouldSuppressLootEvent()} may be called from background sync threads
 * and reads {@code volatile} fields to ensure visibility.</p>
 */
@Slf4j
@Singleton
public class DeathRecoveryGuard
{
    // ── Region IDs for death-related areas ───────────────────────────────────

    /** Region ID of Death's Office / Death's Domain (OSRS). */
    private static final int REGION_DEATHS_OFFICE = 8543;

    /** A few neighbouring region IDs that may appear during the death cinematic. */
    private static final int REGION_DEATHS_OFFICE_ALT1 = 8799;
    private static final int REGION_DEATHS_OFFICE_ALT2 = 8544;

    // ── Widget IDs associated with gravestone / death storage ─────────────────

    /** Gravestone info/manage widget group. */
    private static final int WIDGET_GRAVESTONE   = 659;
    /** Death's coffer / office fee widget group. */
    private static final int WIDGET_DEATHS_COFFER = 4;

    // ── Timing ────────────────────────────────────────────────────────────────

    /** Minimum suppression duration after death, in milliseconds (5 minutes). */
    private static final long DEATH_SUPPRESSION_MS      = 5 * 60 * 1_000L;
    private static final int  DEATH_SUPPRESSION_MINUTES = 5;

    /**
     * Additional idle extension: if no recovery interaction occurs within this
     * window after the base suppression expires, recovery mode ends automatically.
     */
    private static final long IDLE_EXTENSION_MS = 60_000L;

    // ── Death/recovery chat messages to detect ────────────────────────────────

    private static final String DEATH_MESSAGE_OVERHEAD = "oh dear, you are dead!";
    private static final String DEATH_MESSAGE_IRONMAN  = "you have died.";
    private static final String GRAVESTONE_RECLAIM     = "your gravestone has been reclaimed";
    private static final String GRAVESTONE_COLLAPSED   = "your gravestone has collapsed";

    // ── Injected deps ─────────────────────────────────────────────────────────

    private final Client client;

    /**
     * Time source for all suppression-window arithmetic. Defaults to the system
     * clock in production; tests inject a controllable one for deterministic
     * boundary checks.
     */
    private final LongSupplier clock;

    // ── Mutable state (all written on client thread) ──────────────────────────

    /** Time at which the most recent death was detected (epoch ms), or 0. */
    private volatile long deathDetectedAt = 0L;

    /** True while the player is in a gravestone/death office recovery area. */
    private volatile boolean inRecoveryRegion = false;

    /** True while a gravestone/death UI widget is open. */
    private volatile boolean recoveryWidgetOpen = false;

    /** Time of the last recovery interaction, used for idle-extension logic. */
    private volatile long lastRecoveryInteractionAt = 0L;

    /** Running count of item-gain events suppressed this session. */
    @Getter
    private volatile int suppressedEventCount = 0;

    /** Locally recorded ignored items (for UI display). */
    private final List<IgnoredRecoveryItem> ignoredItems = new ArrayList<>();

    @Inject
    public DeathRecoveryGuard(Client client)
    {
        this(client, System::currentTimeMillis);
    }

    /**
     * Test seam: lets a unit test supply a deterministic time source. Production
     * always goes through the {@code @Inject} constructor above, which uses
     * {@link System#currentTimeMillis()}.
     */
    DeathRecoveryGuard(Client client, LongSupplier clock)
    {
        this.client = client;
        this.clock  = clock;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when item-gain events should be ignored because the
     * player is likely recovering items from a gravestone or Death's Office.
     *
     * <p>Callers that already have a confirmed {@code LootReceived} RuneLite
     * event (with a valid source/NPC attribution) do NOT need to consult this
     * guard — those paths are always reliable.</p>
     */
    public boolean shouldSuppressLootEvent()
    {
        if (deathDetectedAt == 0L) return false;

        long now        = clock.getAsLong();
        long elapsed    = now - deathDetectedAt;
        boolean inWindow = elapsed < DEATH_SUPPRESSION_MS;
        boolean extended = inRecoveryRegion || recoveryWidgetOpen;

        // Allow idle-extension past the base window while actively recovering.
        if (!inWindow && extended)
        {
            // If the last recovery interaction was within the idle-extension
            // window, keep suppression active.
            inWindow = (now - lastRecoveryInteractionAt) < IDLE_EXTENSION_MS;
        }

        return inWindow;
    }

    /**
     * Returns {@code true} if recovery mode is currently active (death was
     * detected and the suppression window has not yet expired).
     */
    public boolean isRecovering()
    {
        return shouldSuppressLootEvent();
    }

    /**
     * Record an item pickup that was suppressed during recovery mode.
     * Used for UI display and optional audit upload.
     */
    public synchronized void recordIgnoredItem(int itemId, String itemName, int quantity, String reason)
    {
        suppressedEventCount++;
        ignoredItems.add(new IgnoredRecoveryItem(itemId, itemName, quantity, reason,
                Instant.now(), getCurrentRegionId()));

        log.debug("[death-guard] Ignored item during recovery: {} x{} — {}",
                itemName, quantity, reason);
    }

    /**
     * Explicitly ends recovery mode (called from UI "End Recovery Mode" button
     * or from {@link RuneAlyticsPlugin} when the player returns to a safe area).
     */
    public void endRecoveryMode()
    {
        log.debug("[death-guard] Recovery mode ended manually");
        deathDetectedAt = 0L;
        inRecoveryRegion = false;
        recoveryWidgetOpen = false;
    }

    /**
     * Resets the guard completely — called on plugin startup and account switch.
     */
    public synchronized void reset()
    {
        deathDetectedAt           = 0L;
        inRecoveryRegion          = false;
        recoveryWidgetOpen        = false;
        lastRecoveryInteractionAt = 0L;
        suppressedEventCount      = 0;
        ignoredItems.clear();
        log.debug("[death-guard] Guard reset");
    }

    /**
     * Returns an unmodifiable snapshot of all ignored recovery items this session.
     */
    public synchronized List<IgnoredRecoveryItem> getIgnoredItems()
    {
        return java.util.Collections.unmodifiableList(new ArrayList<>(ignoredItems));
    }

    // ── Event handlers (called by RuneAlyticsPlugin) ──────────────────────────

    /**
     * Called when an actor dies.  Triggers the suppression window if the dying
     * actor is the local player.
     */
    public void onActorDeath(ActorDeath event)
    {
        if (event.getActor() instanceof Player)
        {
            Player dying = (Player) event.getActor();
            Player local = client.getLocalPlayer();
            if (local != null && dying == local)
            {
                triggerDeath("ActorDeath event");
            }
        }
    }

    /**
     * Called for each chat message.  Detects death confirmation messages and
     * gravestone recovery notifications.
     */
    public void onChatMessage(ChatMessage event)
    {
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM) return;

        String msg = event.getMessage().toLowerCase()
                .replaceAll("<[^>]+>", "").trim();

        if (msg.contains(DEATH_MESSAGE_OVERHEAD) || msg.contains(DEATH_MESSAGE_IRONMAN))
        {
            triggerDeath("chat message: " + msg);
            return;
        }

        if (msg.contains(GRAVESTONE_RECLAIM) || msg.contains(GRAVESTONE_COLLAPSED))
        {
            markRecoveryInteraction("gravestone chat: " + msg);
        }
    }

    /**
     * Called when a widget loads.  Detects the gravestone management UI and
     * Death's Coffer widget.
     */
    public void onWidgetLoaded(WidgetLoaded event)
    {
        int group = event.getGroupId();
        if (group == WIDGET_GRAVESTONE || group == WIDGET_DEATHS_COFFER)
        {
            log.debug("[death-guard] Recovery widget loaded: group={}", group);
            recoveryWidgetOpen = true;
            markRecoveryInteraction("widget loaded: " + group);
        }
    }

    /**
     * Called every game tick.  Checks whether the player is currently in the
     * Death's Office region and extends suppression accordingly.
     */
    public void onGameTick()
    {
        int regionId = getCurrentRegionId();
        boolean wasInRegion = inRecoveryRegion;

        inRecoveryRegion = isDeathOfficeRegion(regionId);

        if (inRecoveryRegion && !wasInRegion)
        {
            log.debug("[death-guard] Entered Death's Office region ({})", regionId);
            if (deathDetectedAt == 0L)
            {
                // Player entered Death's Office without a detected death event.
                // Treat this as an implicit death trigger.
                triggerDeath("entered Death's Office region");
            }
            markRecoveryInteraction("Death's Office region entry");
        }
        else if (!inRecoveryRegion && wasInRegion)
        {
            log.debug("[death-guard] Left Death's Office region");
        }

        // Clear the widget-open flag after a tick if the widget is no longer
        // actually displayed (RuneLite does not fire a WidgetClosed event).
        if (recoveryWidgetOpen)
        {
            net.runelite.api.widgets.Widget w = client.getWidget(WIDGET_GRAVESTONE, 0);
            if (w == null || w.isHidden())
            {
                recoveryWidgetOpen = false;
            }
        }
    }

    /**
     * Called when the game state changes.  Clears the guard on logout so we
     * do not carry stale state into the next session.
     */
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case LOGIN_SCREEN:
            case HOPPING:
            case CONNECTION_LOST:
                reset();
                break;
            default:
                break;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void triggerDeath(String reason)
    {
        deathDetectedAt = clock.getAsLong();
        lastRecoveryInteractionAt = deathDetectedAt;
        log.debug("[death-guard] Death detected ({}). Suppression active for {} minutes.",
                reason, DEATH_SUPPRESSION_MINUTES);
    }

    private void markRecoveryInteraction(String context)
    {
        lastRecoveryInteractionAt = clock.getAsLong();
        log.debug("[death-guard] Recovery interaction: {}", context);
    }

    private int getCurrentRegionId()
    {
        Player local = client.getLocalPlayer();
        if (local == null) return -1;
        WorldPoint pos = local.getWorldLocation();
        return pos != null ? pos.getRegionID() : -1;
    }

    private static boolean isDeathOfficeRegion(int regionId)
    {
        return regionId == REGION_DEATHS_OFFICE
                || regionId == REGION_DEATHS_OFFICE_ALT1
                || regionId == REGION_DEATHS_OFFICE_ALT2;
    }

    // ── Nested data class ─────────────────────────────────────────────────────

    /** An item that was suppressed (not counted as loot) during recovery mode. */
    public static class IgnoredRecoveryItem
    {
        public final int     itemId;
        public final String  itemName;
        public final int     quantity;
        public final String  reason;
        public final Instant timestamp;
        public final int     regionId;

        IgnoredRecoveryItem(int itemId, String itemName, int quantity,
                String reason, Instant timestamp, int regionId)
        {
            this.itemId    = itemId;
            this.itemName  = itemName;
            this.quantity  = quantity;
            this.reason    = reason;
            this.timestamp = timestamp;
            this.regionId  = regionId;
        }
    }
}
