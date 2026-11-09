package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PrivacyWarningManagerTest
{
    private Client client;
    private RunealyticsConfig config;
    private ChatMessageManager chat;
    private PrivacyWarningManager privacy;
    private Player player;

    private PrivacySetting visibility;
    private boolean warnings;
    private String rememberedCsv;
    private String name;
    private int wilderness;
    private long now;
    /** Syncs the plugin would send (effective visibility at the time). */
    private final List<PrivacyWarningManager.SyncTicket> syncs = new ArrayList<>();

    private static final PrivacyWarningManager.Banner B_NONE = PrivacyWarningManager.Banner.NONE;
    private static final PrivacyWarningManager.WildPanel W_NONE = PrivacyWarningManager.WildPanel.NONE;

    @Before
    public void setUp()
    {
        client = mock(Client.class);
        config = mock(RunealyticsConfig.class);
        chat = mock(ChatMessageManager.class);
        player = mock(Player.class);
        visibility = PrivacySetting.PUBLIC;
        warnings = true;
        rememberedCsv = "";
        name = "Rune Alytics";
        wilderness = 0;
        now = 1_000_000L;
        syncs.clear();

        when(config.playerVisibility()).thenAnswer(i -> visibility);
        when(config.showPublicStatusWarnings()).thenAnswer(i -> warnings);
        when(config.privacyWarningDismissedAccounts()).thenAnswer(i -> rememberedCsv);
        doAnswer(i ->
        {
            visibility = i.getArgument(0);
            privacy.reloadConfig();                 // RuneLite posts ConfigChanged → plugin reloads + syncs
            sync();
            return null;
        }).when(config).playerVisibility(any(PrivacySetting.class));
        doAnswer(i ->
        {
            rememberedCsv = i.getArgument(0);
            return null;
        }).when(config).privacyWarningDismissedAccounts(anyString());
        when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenAnswer(i -> wilderness);
        when(client.getLocalPlayer()).thenAnswer(i -> name == null ? null : player);
        when(player.getName()).thenAnswer(i -> name);

        privacy = newManager();
    }

    private PrivacyWarningManager newManager()
    {
        PrivacyWarningManager m = new PrivacyWarningManager(client, config, chat);
        m.setClock(() -> now);
        privacy = m;
        m.start(() -> { }, this::sync);
        return m;
    }

    /** What RuneAlyticsPlugin.syncPrivacySettings does: send the effective visibility. */
    private void sync()
    {
        syncs.add(privacy.syncStarted(privacy.effectiveVisibility()));
    }

    private PrivacyWarningManager.SyncTicket lastSync()
    {
        return syncs.get(syncs.size() - 1);
    }

    private void ticks(int n)
    {
        for (int i = 0; i < n; i++) privacy.onGameTick();
    }

    private String chatText(int times)
    {
        ArgumentCaptor<QueuedMessage> msg = ArgumentCaptor.forClass(QueuedMessage.class);
        verify(chat, times(times)).queue(msg.capture());
        return msg.getValue().getRuneLiteFormattedMessage();
    }

    @Test
    public void publicShowsEyeBannerAndOneChatLinePerSession()
    {
        ticks(1);
        assertTrue(privacy.showEyeAbovePlayer());
        assertEquals(PrivacyWarningManager.Banner.PUBLIC, privacy.banner());
        assertFalse("corner eye only after Remember dismissal", privacy.showCornerEye());
        assertEquals(W_NONE, privacy.wildernessPanel());
        assertTrue(chatText(1).contains(
                "RuneAlytics — Your online status is public. Open RuneAlytics → Privacy to change it."));

        // Region loads and a relog of the same account in this session: no repeat.
        privacy.onLoggedIn();
        ticks(1);
        privacy.onLoggedOut();
        privacy.onLoggedIn();
        ticks(2);
        verify(chat, times(1)).queue(any(QueuedMessage.class));
    }

    @Test
    public void nothingShowsWhileLoggedOut()
    {
        name = null;
        ticks(1);
        assertFalse(privacy.showEyeAbovePlayer());
        assertEquals(B_NONE, privacy.banner());
        verify(chat, never()).queue(any(QueuedMessage.class));
    }

    @Test
    public void switchingToPublicSendsTheChatLineAgain()
    {
        visibility = PrivacySetting.PRIVATE;
        privacy.reloadConfig();
        ticks(1);
        verify(chat, never()).queue(any(QueuedMessage.class));
        visibility = PrivacySetting.PUBLIC;
        privacy.reloadConfig();
        verify(chat, times(1)).queue(any(QueuedMessage.class));
        assertEquals(PrivacyWarningManager.Banner.PUBLIC, privacy.banner());
    }

    @Test
    public void privateOrFriendsShowsNothing()
    {
        for (PrivacySetting s : new PrivacySetting[]{PrivacySetting.PRIVATE, PrivacySetting.FRIENDS})
        {
            visibility = s;
            privacy.reloadConfig();
            wilderness = 1;
            ticks(1);
            assertFalse(privacy.showEyeAbovePlayer());
            assertFalse(privacy.showCornerEye());
            assertEquals(B_NONE, privacy.banner());
            assertEquals(W_NONE, privacy.wildernessPanel());
            wilderness = 0;
            ticks(PrivacyWarningManager.EXIT_CONFIRM_TICKS);
        }
        verify(chat, never()).queue(any(QueuedMessage.class));
    }

    @Test
    public void warningsOffHidesEverythingButKeepsOnlineStatus()
    {
        warnings = false;
        privacy.reloadConfig();
        wilderness = 1;
        ticks(1);
        assertFalse(privacy.showEyeAbovePlayer());
        assertFalse(privacy.showCornerEye());
        assertEquals(B_NONE, privacy.banner());
        assertEquals(W_NONE, privacy.wildernessPanel());
        verify(chat, never()).queue(any(QueuedMessage.class));
        assertEquals(PrivacySetting.PUBLIC, privacy.effectiveVisibility());
        verify(config, never()).playerVisibility(any(PrivacySetting.class));

        warnings = true;
        privacy.reloadConfig();
        assertEquals("on again: shows immediately", PrivacyWarningManager.WildPanel.PUBLIC, privacy.wildernessPanel());
    }

    @Test
    public void hideSavesPrivateAndWaitsForTheWebsite()
    {
        ticks(1);
        privacy.makePrivate();
        verify(config).playerVisibility(PrivacySetting.PRIVATE);
        assertEquals(PrivacySetting.PRIVATE, lastSync().sent);
        assertEquals(PrivacyWarningManager.Banner.APPLYING, privacy.banner());
        assertFalse(privacy.showEyeAbovePlayer());

        privacy.syncFinished(lastSync(), true);
        assertEquals(PrivacyWarningManager.Banner.HIDDEN, privacy.banner());
        now += PrivacyWarningManager.HIDDEN_SHOW_MS;
        ticks(1);
        assertEquals(B_NONE, privacy.banner());
    }

    @Test
    public void failedSyncShowsRetry()
    {
        ticks(1);
        privacy.makePrivate();
        privacy.syncFinished(lastSync(), false);
        assertEquals(PrivacyWarningManager.Banner.FAILED, privacy.banner());

        privacy.retry();
        assertEquals(PrivacyWarningManager.Banner.APPLYING, privacy.banner());
        assertEquals(2, syncs.size());
        privacy.syncFinished(lastSync(), true);
        assertEquals(PrivacyWarningManager.Banner.HIDDEN, privacy.banner());
    }

    @Test
    public void staleOrOtherAccountResultsAreIgnored()
    {
        ticks(1);
        privacy.makePrivate();
        PrivacyWarningManager.SyncTicket first = lastSync();
        privacy.retry();                               // a newer request supersedes it
        privacy.syncFinished(first, false);
        assertEquals(PrivacyWarningManager.Banner.APPLYING, privacy.banner());

        PrivacyWarningManager.SyncTicket pending = lastSync();
        privacy.onLoggedOut();
        name = "Other Acct";
        privacy.onLoggedIn();
        ticks(1);
        privacy.syncFinished(pending, true);
        assertEquals("result for the old account doesn't show on the new one", B_NONE, privacy.banner());
    }

    @Test
    public void dismissForSessionVersusRememberPerAccount()
    {
        ticks(1);
        privacy.dismiss(false);
        assertEquals(B_NONE, privacy.banner());
        assertTrue("eye above the player stays until Remember dismissal", privacy.showEyeAbovePlayer());
        assertEquals("", rememberedCsv);

        privacy.dismiss(true);
        assertEquals("rune alytics", rememberedCsv);
        assertFalse(privacy.showEyeAbovePlayer());
        assertTrue("top-right eye replaces the other warnings", privacy.showCornerEye());

        // Another account on the same client still gets the warnings.
        privacy.onLoggedOut();
        name = "Second Acct";
        privacy.onLoggedIn();
        ticks(1);
        assertEquals(PrivacyWarningManager.Banner.PUBLIC, privacy.banner());
        assertTrue(privacy.showEyeAbovePlayer());
        assertFalse(privacy.showCornerEye());
    }

    @Test
    public void rememberedDismissalSurvivesRestartAndCanBeReset()
    {
        rememberedCsv = "rune alytics";
        PrivacyWarningManager restarted = newManager();
        ticks(1);
        assertEquals(B_NONE, restarted.banner());
        assertTrue(restarted.showCornerEye());
        verify(chat, never()).queue(any(QueuedMessage.class));

        restarted.resetRememberedDismissals();
        assertEquals("", rememberedCsv);
        assertEquals(PrivacyWarningManager.Banner.PUBLIC, restarted.banner());
        assertTrue(restarted.showEyeAbovePlayer());
        assertFalse(restarted.showCornerEye());
    }

    @Test
    public void wildernessTripHidesUntilAConfirmedExit()
    {
        ticks(1);
        wilderness = 1;
        ticks(1);
        assertEquals(PrivacyWarningManager.WildPanel.PUBLIC, privacy.wildernessPanel());
        assertEquals(PrivacyWarningManager.Banner.WILDERNESS, privacy.banner());

        privacy.hideForTrip();
        assertEquals(PrivacySetting.PRIVATE, privacy.effectiveVisibility());
        assertEquals(PrivacySetting.PRIVATE, lastSync().sent);
        verify(config, never()).playerVisibility(any(PrivacySetting.class));   // saved stays Public
        assertEquals(PrivacyWarningManager.WildPanel.APPLYING, privacy.wildernessPanel());
        privacy.syncFinished(lastSync(), true);
        assertEquals(PrivacyWarningManager.WildPanel.TRIP_HIDDEN, privacy.wildernessPanel());
        assertEquals(PrivacyWarningManager.Banner.TRIP_HIDDEN, privacy.banner());
        assertFalse(privacy.showEyeAbovePlayer());

        // A brief drop of the varbit (teleport / region load) doesn't end the trip.
        wilderness = 0;
        ticks(PrivacyWarningManager.EXIT_CONFIRM_TICKS - 1);
        wilderness = 1;
        ticks(1);
        assertTrue(privacy.isTripHidden());
        int before = syncs.size();

        wilderness = 0;
        ticks(PrivacyWarningManager.EXIT_CONFIRM_TICKS);
        assertFalse(privacy.isTripHidden());
        assertEquals(PrivacySetting.PUBLIC, privacy.effectiveVisibility());
        assertEquals("sharing resumes", before + 1, syncs.size());
        assertEquals(PrivacySetting.PUBLIC, lastSync().sent);
        assertEquals(W_NONE, privacy.wildernessPanel());
    }

    @Test
    public void tripEndDoesNotResumeIfSavedChangedMeanwhile()
    {
        ticks(1);
        wilderness = 1;
        ticks(1);
        privacy.hideForTrip();
        visibility = PrivacySetting.FRIENDS;          // changed in Settings during the trip
        privacy.reloadConfig();
        assertFalse(privacy.isTripHidden());
        int before = syncs.size();
        wilderness = 0;
        ticks(PrivacyWarningManager.EXIT_CONFIRM_TICKS);
        assertEquals(before, syncs.size());
        assertEquals(PrivacySetting.FRIENDS, privacy.effectiveVisibility());
    }

    @Test
    public void restorePublicAndMakePrivateFromTheWilderness()
    {
        ticks(1);
        wilderness = 1;
        ticks(1);
        privacy.hideForTrip();
        privacy.restorePublic();
        assertEquals(PrivacySetting.PUBLIC, lastSync().sent);
        assertEquals(PrivacyWarningManager.WildPanel.PUBLIC, privacy.wildernessPanel());

        privacy.makePrivate();
        assertEquals(PrivacySetting.PRIVATE, visibility);
        privacy.syncFinished(lastSync(), true);
        assertEquals(PrivacyWarningManager.WildPanel.HIDDEN, privacy.wildernessPanel());
        privacy.closeWildernessPanel();
        assertEquals(W_NONE, privacy.wildernessPanel());
    }

    @Test
    public void accountSwitchClearsTheTrip()
    {
        ticks(1);
        wilderness = 1;
        ticks(1);
        privacy.hideForTrip();
        privacy.onLoggedOut();
        name = "Other Acct";
        privacy.onLoggedIn();
        ticks(1);
        assertFalse(privacy.isTripHidden());
        assertEquals(PrivacySetting.PUBLIC, lastSync().sent);
    }

    @Test
    public void parsesRememberedAccounts()
    {
        assertEquals(2, PrivacyWarningManager.parseAccounts(" Rune Alytics ,zezima,, ").size());
        assertTrue(PrivacyWarningManager.parseAccounts("Rune Alytics").contains("rune alytics"));
        assertTrue(PrivacyWarningManager.parseAccounts(null).isEmpty());
    }
}
