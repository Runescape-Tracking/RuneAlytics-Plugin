package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;

/**
 * Draws the players the website says we are allowed to see (delivered in each
 * {@code /plugin/heartbeat} response and cached in {@link RuneAlyticsState}) as
 * dots on the in-game minimap.
 *
 * <p>Only players on the local player's current world and plane that fall inside
 * the loaded scene are drawn — anything outside the minimap's range simply isn't
 * rendered, exactly like RuneLite's own minimap dots. All rendering happens on
 * the client thread, so reading client state here is safe.</p>
 */
@Singleton
public class LiveMapMinimapOverlay extends Overlay
{
    private static final int   DOT_SIZE   = 5;
    private static final Color DOT_COLOR  = new Color(0, 200, 255);   // cyan
    private static final Color DOT_BORDER = new Color(0, 80, 110);
    private static final Color NAME_COLOR = Color.WHITE;

    private final Client client;
    private final RuneAlyticsState state;

    @Inject
    public LiveMapMinimapOverlay(Client client, RuneAlyticsState state)
    {
        this.client = client;
        this.state  = state;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.MED);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        List<MapPlayer> players = state.getVisibleMapPlayers();
        if (players == null || players.isEmpty())
        {
            return null;
        }

        Player local = client.getLocalPlayer();
        if (local == null)
        {
            return null;
        }

        final int localWorld = client.getWorld();
        final int localPlane = client.getPlane();
        final String localName = local.getName();

        for (MapPlayer p : players)
        {
            if (p == null) continue;

            // Don't draw a dot on ourselves, and only show same-world/plane peers.
            if (localName != null && localName.equalsIgnoreCase(p.getUsername())) continue;
            if (p.getWorld() != 0 && p.getWorld() != localWorld) continue;
            if (p.getPlane() != localPlane) continue;

            WorldPoint wp = p.toWorldPoint();
            LocalPoint lp = LocalPoint.fromWorld(client, wp);
            if (lp == null) continue; // outside the loaded scene → off-minimap

            net.runelite.api.Point mm = Perspective.localToMinimap(client, lp);
            if (mm == null) continue;

            drawDot(graphics, mm.getX(), mm.getY(), p.getUsername());
        }

        return null;
    }

    private void drawDot(Graphics2D graphics, int x, int y, String name)
    {
        int half = DOT_SIZE / 2;
        graphics.setColor(DOT_COLOR);
        graphics.fillOval(x - half, y - half, DOT_SIZE, DOT_SIZE);
        graphics.setColor(DOT_BORDER);
        graphics.drawOval(x - half, y - half, DOT_SIZE, DOT_SIZE);

        if (name != null && !name.isEmpty())
        {
            graphics.setColor(NAME_COLOR);
            graphics.drawString(name, x + DOT_SIZE, y);
        }
    }
}
