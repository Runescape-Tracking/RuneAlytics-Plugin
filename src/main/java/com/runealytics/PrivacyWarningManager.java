package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.util.ColorUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * State behind the public Online Status warnings. The saved setting is
 * {@link RunealyticsConfig#playerVisibility()}; this class never changes it except
 * through {@link #makePrivate()} (which saves Private through the normal settings
 * path, so the plugin's existing privacy sync sends it to the website).
 *
 * <h3>What is shown (only while "Show public status warnings" is on and an account is logged in)</h3>
 * <ul>
 *   <li><b>One red chat line</b> per client session per account, and again whenever the
 *       saved setting is switched to Public: {@link #CHAT_WARNING}.</li>
 * </ul>
 * Everything else appears <b>only in the Wilderness</b>, while effectively public:
 * <ul>
 *   <li><b>Amber eye above the player</b>, unless this account ticked "Remember dismissal".</li>
 *   <li><b>Sidebar banner</b> ({@link #banner()}): Hide for this trip / Make private /
 *       Dismiss (+ Remember dismissal), and the Applying… / Hidden / failed states.</li>
 *   <li><b>Top-right Wilderness panel</b> ({@link #wildernessPanel()}): Hide for this trip /
 *       Make private / close, then the crossed-out eye with Restore public.</li>
 *   <li><b>Top-right eye</b> ({@link #showCornerEye()}) instead of the above after Remember
 *       dismissal; clicking it opens the privacy controls.</li>
 * </ul>
 * Dismiss (sidebar) and the close X (panel) hide the warning until the next time the
 * player enters the Wilderness.
 *
 * <h3>Wilderness trip</h3>
 * "Hide for this trip" is a temporary override: {@link #effectiveVisibility()} reports
 * Private (the heartbeat and privacy sync use it) while the saved setting stays
 * Public. It ends once the player has been out of the Wilderness for
 * {@link #EXIT_CONFIRM_TICKS} ticks in a row (so a teleport or region load can't end
 * it early); sharing then resumes only if the saved setting is still Public.
 *
 * <h3>Account scoping</h3>
 * Remembered dismissals are saved per account (normalized RSN). A sync result is
 * applied only if it is the latest one and the same account is still logged in;
 * a different account logging in clears the trip and any pending state.
 *
 * <p>Performance: overlays and the banner only read volatile fields. Per game tick
 * this is one varbit read and one player-name read.</p>
 */
@Slf4j
@Singleton
public class PrivacyWarningManager
{
    static final String CHAT_WARNING =
            "RuneAlytics — Your online status is public. Open RuneAlytics → Privacy to change it.";
    static final Color WARNING_RED = new Color(255, 70, 70);
    /** Consecutive game ticks outside the Wilderness before a trip counts as over (~3 s). */
    static final int EXIT_CONFIRM_TICKS = 5;
    /** How long "Hidden" stays up after the website confirmed. */
    static final long HIDDEN_SHOW_MS = 6_000L;

    /** What the sidebar banner shows. */
    public enum Banner { NONE, WILDERNESS, TRIP_HIDDEN, APPLYING, HIDDEN, FAILED }

    /** What the top-right Wilderness panel shows. */
    public enum WildPanel { NONE, PUBLIC, TRIP_HIDDEN, APPLYING, HIDDEN, FAILED }

    private enum Update { NONE, APPLYING, HIDDEN, FAILED }

    /** One privacy sync request; results of older tickets are ignored. */
    static final class SyncTicket
    {
        final long seq;
        final String account;
        final PrivacySetting sent;

        SyncTicket(long seq, String account, PrivacySetting sent)
        {
            this.seq = seq;
            this.account = account;
            this.sent = sent;
        }
    }

    private final Client client;
    private final RunealyticsConfig config;
    private final ChatMessageManager chatMessageManager;
    private LongSupplier clock = System::currentTimeMillis;

    // Read by overlays / banner (any thread); written under the lock.
    private volatile boolean warningsOn = true;
    private volatile PrivacySetting saved = PrivacySetting.PUBLIC;
    private volatile String account;
    private volatile Set<String> remembered = Collections.emptySet();
    private volatile boolean inWilderness;
    private volatile boolean tripHidden;
    private volatile boolean wildPanelClosed;
    private volatile Update update = Update.NONE;
    private volatile long hiddenAt;

    private final Set<String> chatWarnedThisSession = new HashSet<>();
    private String tripAccount;
    private int ticksOutside;
    private boolean loginPending;
    private long syncSeq;

    private volatile Runnable openSettings = () -> { };
    private volatile Runnable resync = () -> { };
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    @Inject
    public PrivacyWarningManager(Client client, RunealyticsConfig config, ChatMessageManager chatMessageManager)
    {
        this.client = client;
        this.config = config;
        this.chatMessageManager = chatMessageManager;
    }

    void setClock(LongSupplier clock)
    {
        this.clock = clock;
    }

    // ── Lifecycle / events (called by the plugin) ────────────────────────────

    /**
     * Plugin startUp.
     *
     * @param openSettings opens the RuneAlytics sidebar on Settings (run on the EDT)
     * @param resync       re-sends the privacy settings using {@link #effectiveVisibility()}
     */
    public synchronized void start(Runnable openSettings, Runnable resync)
    {
        this.openSettings = openSettings != null ? openSettings : () -> { };
        this.resync = resync != null ? resync : () -> { };
        chatWarnedThisSession.clear();
        account = null;
        inWilderness = false;
        tripHidden = false;
        tripAccount = null;
        wildPanelClosed = false;
        update = Update.NONE;
        ticksOutside = 0;
        loginPending = true;
        loadConfig();
        fireChanged();
    }

    /** Plugin shutDown. */
    public void stop()
    {
        listeners.clear();
        openSettings = () -> { };
        resync = () -> { };
    }

    /** GameState LOGGED_IN (also fires on region loads). */
    public synchronized void onLoggedIn()
    {
        if (account == null) loginPending = true;
    }

    /** GameState LOGIN_SCREEN. Pending results for this account are dropped; a trip survives a relog. */
    public synchronized void onLoggedOut()
    {
        account = null;
        inWilderness = false;
        update = Update.NONE;
        loginPending = true;
        ticksOutside = 0;
        fireChanged();
    }

    /** Client thread, once per game tick. */
    public void onGameTick()
    {
        Player me = client.getLocalPlayer();
        String name = me != null ? CurrentPlayerIdentityService.normalizeUsername(me.getName()) : null;
        boolean rawWild = client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1;
        boolean changed = false;
        boolean needResync = false;
        String chat = null;

        synchronized (this)
        {
            if (name != null && !name.equals(account))
            {
                account = name;
                changed = true;
                update = Update.NONE;
                wildPanelClosed = false;
                if (tripHidden && !name.equals(tripAccount))
                {
                    endTrip();
                    needResync = true;
                }
            }
            if (account != null && loginPending)
            {
                loginPending = false;
                if (warningsOn && isSavedPublic() && !remembered.contains(account)
                        && chatWarnedThisSession.add(account))
                {
                    chat = CHAT_WARNING;
                }
            }

            if (account != null)
            {
                if (rawWild)
                {
                    ticksOutside = 0;
                    if (!inWilderness)
                    {
                        inWilderness = true;
                        wildPanelClosed = false;
                        changed = true;
                    }
                }
                else if (++ticksOutside >= EXIT_CONFIRM_TICKS)
                {
                    if (inWilderness)
                    {
                        inWilderness = false;
                        changed = true;
                    }
                    if (tripHidden)
                    {
                        endTrip();
                        if (update != Update.FAILED) update = Update.NONE;
                        needResync = isSavedPublic();
                        changed = true;
                        log.debug("[Privacy] left the Wilderness: trip override ended");
                    }
                }
            }

            if (update == Update.HIDDEN && clock.getAsLong() - hiddenAt >= HIDDEN_SHOW_MS)
            {
                update = Update.NONE;
                changed = true;
            }
        }

        if (chat != null) sendChat(chat);
        if (needResync) resync.run();
        if (changed) fireChanged();
    }

    /** A RuneAlytics config key changed (Online Status, warnings toggle, remembered dismissals). */
    public void reloadConfig()
    {
        boolean switchedToPublic;
        synchronized (this)
        {
            boolean wasPublic = isSavedPublic();
            loadConfig();
            switchedToPublic = !wasPublic && isSavedPublic() && account != null && warningsOn;
            if (!isSavedPublic() && tripHidden)
            {
                endTrip();          // saved setting no longer public: nothing to override
            }
        }
        if (switchedToPublic) sendChat(CHAT_WARNING);
        fireChanged();
    }

    private void loadConfig()
    {
        PrivacySetting v = config.playerVisibility();
        saved = v != null ? v : PrivacySetting.PUBLIC;
        warningsOn = config.showPublicStatusWarnings();
        remembered = parseAccounts(config.privacyWarningDismissedAccounts());
    }

    // ── Privacy sync bookkeeping (plugin) ────────────────────────────────────

    /**
     * What the website should be told right now: the saved setting, or Private
     * while a Wilderness trip override is active.
     */
    public PrivacySetting effectiveVisibility()
    {
        PrivacySetting s = saved;
        return tripHidden && s == PrivacySetting.PUBLIC ? PrivacySetting.PRIVATE : s;
    }

    /** The plugin is about to send {@code sent} to the website. */
    public synchronized SyncTicket syncStarted(PrivacySetting sent)
    {
        return new SyncTicket(++syncSeq, account, sent);
    }

    /** Result of a privacy sync ({@code ok} = website accepted it). Any thread. */
    public void syncFinished(SyncTicket t, boolean ok)
    {
        synchronized (this)
        {
            if (t == null || t.seq != syncSeq || !Objects.equals(t.account, account)) return;
            if (update != Update.APPLYING && update != Update.FAILED) return;
            if (t.sent == PrivacySetting.PUBLIC)
            {
                update = Update.NONE;
            }
            else if (ok)
            {
                update = Update.HIDDEN;
                hiddenAt = clock.getAsLong();
            }
            else
            {
                update = Update.FAILED;
                log.debug("[Privacy] could not confirm the private status with the website");
            }
        }
        fireChanged();
    }

    // ── Actions (banner, overlay clicks / menus) ─────────────────────────────

    /** "Hide" (banner) / "Make private": saves Online Status = Private through the normal settings + sync. */
    public void makePrivate()
    {
        synchronized (this)
        {
            update = Update.APPLYING;
            endTrip();
        }
        fireChanged();
        if (saved == PrivacySetting.PRIVATE)
        {
            resync.run();           // already saved: just confirm with the website
        }
        else
        {
            config.playerVisibility(PrivacySetting.PRIVATE);   // ConfigChanged → plugin syncs
        }
    }

    /** "Hide for this trip": temporary Private until the player has left the Wilderness. */
    public void hideForTrip()
    {
        synchronized (this)
        {
            if (!inWilderness || !isSavedPublic() || account == null) return;
            tripHidden = true;
            tripAccount = account;
            update = Update.APPLYING;
        }
        fireChanged();
        resync.run();
    }

    /** "Restore public": ends the trip override now (sharing resumes only if saved is still Public). */
    public void restorePublic()
    {
        synchronized (this)
        {
            if (!tripHidden) return;
            endTrip();
            update = Update.NONE;
        }
        fireChanged();
        resync.run();
    }

    /** "Retry" after a failed sync. */
    public void retry()
    {
        synchronized (this)
        {
            update = Update.APPLYING;
        }
        fireChanged();
        resync.run();
    }

    /** Close (X) on the Wilderness panel: panel and sidebar box hidden until the player next enters. */
    public void closeWildernessPanel()
    {
        dismiss(false);
    }

    /**
     * "Dismiss" on the banner (and the panel's X): hidden for this Wilderness trip.
     * {@code remember} = "Remember dismissal" (saved for this account): from then on
     * only the small top-right eye shows in the Wilderness.
     */
    public void dismiss(boolean remember)
    {
        String acct;
        synchronized (this)
        {
            acct = account;
            if (acct == null) return;
            wildPanelClosed = true;
            if (update == Update.HIDDEN || update == Update.FAILED) update = Update.NONE;
        }
        if (remember)
        {
            Set<String> next = new LinkedHashSet<>(remembered);
            if (next.add(acct))
            {
                remembered = Collections.unmodifiableSet(next);
                config.privacyWarningDismissedAccounts(String.join(",", next));
            }
        }
        fireChanged();
    }

    /** Settings → "Reset remembered dismissal": every account sees the warnings again. */
    public void resetRememberedDismissals()
    {
        synchronized (this)
        {
            remembered = Collections.emptySet();
            wildPanelClosed = false;
        }
        config.privacyWarningDismissedAccounts("");
        fireChanged();
    }

    /** Opens the RuneAlytics sidebar on Settings (Privacy). */
    public void openPrivacySettings()
    {
        SwingUtilities.invokeLater(openSettings);
    }

    // ── State read by the overlays / banner ──────────────────────────────────

    private boolean active()
    {
        return warningsOn && account != null;
    }

    private boolean isSavedPublic()
    {
        return saved == PrivacySetting.PUBLIC;
    }

    private boolean effectivelyPublic()
    {
        return isSavedPublic() && !tripHidden;
    }

    private boolean isRemembered()
    {
        String a = account;
        return a != null && remembered.contains(a);
    }

    public boolean hasRememberedDismissals()
    {
        return !remembered.isEmpty();
    }

    public boolean isInWilderness()
    {
        return inWilderness;
    }

    public boolean isTripHidden()
    {
        return tripHidden;
    }

    /** Amber eye above the local player: in the Wilderness, until Remember dismissal. */
    public boolean showEyeAbovePlayer()
    {
        return active() && inWilderness && effectivelyPublic() && !isRemembered();
    }

    /** Top-right "Public status" eye: in the Wilderness after Remember dismissal (when the panel isn't up). */
    public boolean showCornerEye()
    {
        return active() && inWilderness && effectivelyPublic() && isRemembered()
                && wildernessPanel() == WildPanel.NONE;
    }

    /** The top-right Wilderness panel (nothing outside the Wilderness). */
    public WildPanel wildernessPanel()
    {
        if (!active() || !inWilderness || wildPanelClosed) return WildPanel.NONE;
        Update u = update;
        if (u == Update.APPLYING) return WildPanel.APPLYING;
        if (u == Update.FAILED) return WildPanel.FAILED;
        if (tripHidden) return WildPanel.TRIP_HIDDEN;
        if (u == Update.HIDDEN) return WildPanel.HIDDEN;
        return isSavedPublic() && !isRemembered() ? WildPanel.PUBLIC : WildPanel.NONE;
    }

    /** The sidebar banner (nothing outside the Wilderness). */
    public Banner banner()
    {
        if (!active() || !inWilderness) return Banner.NONE;
        Update u = update;
        if (u == Update.APPLYING) return Banner.APPLYING;
        if (u == Update.FAILED) return Banner.FAILED;
        if (tripHidden) return Banner.TRIP_HIDDEN;
        if (u == Update.HIDDEN) return Banner.HIDDEN;
        if (!isSavedPublic() || isRemembered() || wildPanelClosed) return Banner.NONE;
        return Banner.WILDERNESS;
    }

    /** Called (on any thread) whenever what should be shown may have changed. */
    public void addListener(Runnable r)
    {
        listeners.add(r);
    }

    private void endTrip()
    {
        tripHidden = false;
        tripAccount = null;
    }

    private void fireChanged()
    {
        for (Runnable r : listeners) r.run();
    }

    private void sendChat(String text)
    {
        chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(ColorUtil.wrapWithColorTag(text, WARNING_RED))
                .build());
    }

    static Set<String> parseAccounts(String csv)
    {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(","))
        {
            String n = CurrentPlayerIdentityService.normalizeUsername(part);
            if (n != null) out.add(n);
        }
        return Collections.unmodifiableSet(out);
    }
}
