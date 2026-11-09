package com.runealytics;

import net.runelite.client.input.MouseAdapter;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

/**
 * Left-clicks on {@link PrivacyStatusOverlay}'s buttons. A left press inside the
 * drawn box is consumed (with its release/click) so the game doesn't also walk
 * there; right-clicks pass through so the overlay's right-click menu still opens.
 * Nothing is consumed while the overlay draws nothing.
 */
public class PrivacyOverlayMouseListener extends MouseAdapter
{
    private final PrivacyStatusOverlay overlay;
    private boolean swallowing;

    @Inject
    public PrivacyOverlayMouseListener(PrivacyStatusOverlay overlay)
    {
        this.overlay = overlay;
    }

    @Override
    public MouseEvent mousePressed(MouseEvent e)
    {
        swallowing = false;
        if (!SwingUtilities.isLeftMouseButton(e) || e.isAltDown()) return e;   // alt = RuneLite overlay drag
        Rectangle b = overlay.getBounds();
        if (b == null || b.isEmpty() || !b.contains(e.getPoint())) return e;
        Point p = new Point(e.getX() - b.x, e.getY() - b.y);
        for (PrivacyStatusOverlay.Hit h : overlay.hits())
        {
            if (h.area.contains(p))
            {
                swallowing = true;
                e.consume();
                if (h.action != null) h.action.run();
                break;
            }
        }
        return e;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent e)
    {
        if (swallowing && SwingUtilities.isLeftMouseButton(e)) e.consume();
        return e;
    }

    @Override
    public MouseEvent mouseClicked(MouseEvent e)
    {
        if (swallowing && SwingUtilities.isLeftMouseButton(e))
        {
            e.consume();
            swallowing = false;
        }
        return e;
    }
}
