package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.chat.ChatMessageManager;
import org.junit.Before;
import org.junit.Test;

import javax.inject.Singleton;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Left-clicks on the top-right Wilderness panel reach the right action. */
public class PrivacyStatusOverlayClickTest
{
    private PrivacyWarningManager privacy;
    private PrivacyStatusOverlay overlay;
    private PrivacyOverlayMouseListener mouse;
    private final JPanel source = new JPanel();

    @Before
    public void setUp()
    {
        Client client = mock(Client.class);
        RunealyticsConfig config = mock(RunealyticsConfig.class);
        Player me = mock(Player.class);
        when(me.getName()).thenReturn("Rune Alytics");
        when(client.getLocalPlayer()).thenReturn(me);
        when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
        when(config.playerVisibility()).thenReturn(PrivacySetting.PUBLIC);
        when(config.showPublicStatusWarnings()).thenReturn(true);
        when(config.privacyWarningDismissedAccounts()).thenReturn("");
        privacy = new PrivacyWarningManager(client, config, mock(ChatMessageManager.class));
        privacy.start(() -> { }, () -> { });
        privacy.onGameTick();
        overlay = new PrivacyStatusOverlay(client, privacy);
        mouse = new PrivacyOverlayMouseListener(overlay);
    }

    /** What OverlayRenderer does each frame: place the overlay, render it, size its bounds. */
    private List<PrivacyStatusOverlay.Hit> renderAt(int x, int y)
    {
        BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        overlay.getBounds().setLocation(x, y);
        Dimension d = overlay.render(g);
        g.dispose();
        overlay.getBounds().setSize(d != null ? d : new Dimension());
        return overlay.hits();
    }

    private MouseEvent leftPress(Rectangle area, int ox, int oy)
    {
        return new MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK,
                ox + (int) area.getCenterX(), oy + (int) area.getCenterY(), 1, false, MouseEvent.BUTTON1);
    }

    @Test
    public void overlayIsASingletonSoTheListenerSeesTheRenderedOne()
    {
        assertNotNull(PrivacyStatusOverlay.class.getAnnotation(Singleton.class));
        assertNotNull(PrivacyWarningManager.class.getAnnotation(Singleton.class));
    }

    @Test
    public void hideForThisTripButtonWorks()
    {
        List<PrivacyStatusOverlay.Hit> hits = renderAt(600, 8);
        MouseEvent e = mouse.mousePressed(leftPress(hits.get(1).area, 600, 8));   // [close, Hide for this trip, Make private, box]
        assertTrue("click is taken so the player doesn't walk", e.isConsumed());
        assertTrue(privacy.isTripHidden());
        assertEquals(PrivacyWarningManager.WildPanel.APPLYING, privacy.wildernessPanel());
    }

    @Test
    public void closeButtonWorks()
    {
        List<PrivacyStatusOverlay.Hit> hits = renderAt(600, 8);
        mouse.mousePressed(leftPress(hits.get(0).area, 600, 8));
        assertEquals(PrivacyWarningManager.WildPanel.NONE, privacy.wildernessPanel());
        assertEquals(PrivacyWarningManager.Banner.NONE, privacy.banner());
        // Nothing drawn now → clicks there go to the game again.
        renderAt(600, 8);
        MouseEvent e = mouse.mousePressed(leftPress(new Rectangle(5, 5, 2, 2), 600, 8));
        assertFalse(e.isConsumed());
    }

    @Test
    public void clicksOutsideThePanelAreLeftAlone()
    {
        renderAt(600, 8);
        MouseEvent e = mouse.mousePressed(leftPress(new Rectangle(100, 100, 2, 2), 0, 0));
        assertFalse(e.isConsumed());
        assertFalse(privacy.isTripHidden());
    }
}
