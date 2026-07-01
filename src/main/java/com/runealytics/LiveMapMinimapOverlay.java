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
import java.awt.Polygon;
import java.util.List;

/**
 * Draws the players the website says we are allowed to see (delivered in each
 * {@code /plugin/heartbeat} response and cached in {@link RuneAlyticsState}) on
 * both the in-game minimap and the game scene.
 *
 * <p>Only players on the local player's current world and plane that fall inside
 * the loaded scene are drawn — anything outside that range simply isn't rendered,
 * exactly like RuneLite's own minimap dots. All rendering happens on the client
 * thread, so reading client state here is safe. Rendering is gated behind the
 * {@code showMapPlayers} config toggle.</p>
 */
@Singleton
public class LiveMapMinimapOverlay extends Overlay
{
    private static final int   DOT_SIZE    = 5;
    private static final int   SCENE_TEXT_Z = 60;
    private static final Color DOT_COLOR   = new Color(0, 200, 255);   // cyan
    private static final Color DOT_BORDER  = new Color(0, 80, 110);
    private static final Color TILE_COLOR  = new Color(0, 200, 255, 120);
    private static final Color TILE_FILL   = new Color(0, 200, 255, 40);
    private static final Color NAME_COLOR  = Color.WHITE;

    private final Client client;
    private final RuneAlyticsState state;
    private final RunealyticsConfig config;

    @Inject
    public LiveMapMinimapOverlay(Client client, RuneAlyticsState state, RunealyticsConfig config)
    {
        this.client = client;
        this.state  = state;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.MED);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showMapPlayers())
        {
            return null;
        }

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
        final String localName = normalizeRsn(local.getName());

        for (MapPlayer p : players)
        {
            if (p == null) continue;

            // Don't draw on ourselves, and only show same-world/plane peers.
            // Names are normalized because RuneLite uses NBSP (U+00A0) while the
            // server uses regular spaces/underscores. World must match exactly:
            // a player on another world rendered at our scene coords is a ghost.
            if (localName != null && localName.equalsIgnoreCase(normalizeRsn(p.getUsername()))) continue;
            if (p.getWorld() != localWorld) continue;
            if (p.getPlane() != localPlane) continue;

            WorldPoint wp = p.toWorldPoint();
            LocalPoint lp = LocalPoint.fromWorld(client, wp);
            if (lp == null) continue; // outside the loaded scene

            drawSceneMarker(graphics, lp, p.getUsername());
            drawMinimapDot(graphics, lp);
        }

        return null;
    }

    private static String normalizeRsn(String name)
    {
        if (name == null) return null;
        return name.replace('\u00A0', ' ').replace('_', ' ').trim();
    }

    private void drawMinimapDot(Graphics2D graphics, LocalPoint lp)
    {
        net.runelite.api.Point mm = Perspective.localToMinimap(client, lp);
        if (mm == null) return;

        int half = DOT_SIZE / 2;
        graphics.setColor(DOT_COLOR);
        graphics.fillOval(mm.getX() - half, mm.getY() - half, DOT_SIZE, DOT_SIZE);
        graphics.setColor(DOT_BORDER);
        graphics.drawOval(mm.getX() - half, mm.getY() - half, DOT_SIZE, DOT_SIZE);
    }

    private void drawSceneMarker(Graphics2D graphics, LocalPoint lp, String name)
    {
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly != null)
        {
            graphics.setColor(TILE_COLOR);
            graphics.draw(poly);
            graphics.setColor(TILE_FILL);
            graphics.fill(poly);
        }

        if (name != null && !name.isEmpty())
        {
            net.runelite.api.Point textLoc =
                    Perspective.getCanvasTextLocation(client, graphics, lp, name, SCENE_TEXT_Z);
            if (textLoc != null)
            {
                graphics.setColor(NAME_COLOR);
                graphics.drawString(name, textLoc.getX(), textLoc.getY());
            }
        }
    }
}
