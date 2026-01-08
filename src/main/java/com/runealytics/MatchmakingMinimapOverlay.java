package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;
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

@Singleton
public class MatchmakingMinimapOverlay extends Overlay
{
    private static final int ARROW_SIZE = 6;

    private final Client client;
    private final MatchmakingManager matchmakingManager;
    private final Color arrowColor = ColorScheme.BRAND_ORANGE;

    @Inject
    public MatchmakingMinimapOverlay(
            Client client,
            MatchmakingManager matchmakingManager
    )
    {
        this.client = client;
        this.matchmakingManager = matchmakingManager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        WorldPoint target = matchmakingManager.getMinimapTarget();
        if (target == null)
        {
            return null;
        }

        LocalPoint localTarget = LocalPoint.fromWorld(client, target);
        if (localTarget == null)
        {
            return null;
        }

        net.runelite.api.Point minimapPoint = Perspective.localToMinimap(client, localTarget);
        if (minimapPoint == null)
        {
            return null;
        }

        drawArrow(graphics, minimapPoint.getX(), minimapPoint.getY());
        return null;
    }

    private void drawArrow(Graphics2D graphics, int x, int y)
    {
        Polygon arrow = new Polygon();
        arrow.addPoint(x, y - ARROW_SIZE);
        arrow.addPoint(x - ARROW_SIZE, y + ARROW_SIZE);
        arrow.addPoint(x + ARROW_SIZE, y + ARROW_SIZE);

        graphics.setColor(arrowColor);
        graphics.fill(arrow);
        graphics.setColor(arrowColor.darker());
        graphics.draw(arrow);
    }
}
