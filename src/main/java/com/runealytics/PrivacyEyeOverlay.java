package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Draws a red eye above the local player while their RuneAlytics Online Status
 * is public (see {@link PrivacyWarningManager#showEyeAbovePlayer()}).
 *
 * <p>Per frame this is two volatile reads and, when shown, one cached image draw.</p>
 */
@Singleton
public class PrivacyEyeOverlay extends Overlay
{
    private static final int Z_OFFSET = 30;

    private final Client client;
    private final PrivacyWarningManager privacy;
    private final BufferedImage eye = PrivacyEyeIcon.create(24);

    @Inject
    public PrivacyEyeOverlay(Client client, PrivacyWarningManager privacy)
    {
        this.client = client;
        this.privacy = privacy;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!privacy.showEyeAbovePlayer()) return null;
        Player me = client.getLocalPlayer();
        if (me == null) return null;
        Point at = me.getCanvasImageLocation(eye, me.getLogicalHeight() + Z_OFFSET);
        if (at != null)
        {
            OverlayUtil.renderImageLocation(graphics, at, eye);
        }
        return null;
    }
}
