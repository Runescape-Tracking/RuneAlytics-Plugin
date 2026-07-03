package com.runealytics;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Small rounded progress bar with a blue→green XP accent, matching the
 * RuneAlytics XP Tracker theme. Repaints only when the fraction actually
 * changes, so it is cheap to poll from the panel's refresh timer.
 */
class XpProgressBar extends JComponent
{
    private static final Color TRACK   = new Color(38, 44, 62);
    private static final Color FILL_LO = new Color(88, 132, 240);   // blue
    private static final Color FILL_HI = new Color(105, 220, 140);  // green

    private double fraction = 0.0;

    XpProgressBar()
    {
        setOpaque(false);
        Dimension d = new Dimension(120, 6);
        setPreferredSize(d);
        setMinimumSize(new Dimension(20, 6));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
    }

    /** Sets the fill fraction (0..1). No-op repaint when unchanged. */
    void setFraction(double f)
    {
        double clamped = Math.max(0.0, Math.min(1.0, f));
        if (Math.abs(clamped - fraction) < 0.0005) return;
        fraction = clamped;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int arc = h;

            g2.setColor(TRACK);
            g2.fillRoundRect(0, 0, w, h, arc, arc);

            int fw = (int) Math.round(w * fraction);
            if (fw > 0)
            {
                // Blend the fill colour toward green as the bar fills up.
                Color fill = blend(FILL_LO, FILL_HI, fraction);
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, Math.max(fw, arc), h, arc, arc);
            }
        }
        finally
        {
            g2.dispose();
        }
    }

    private static Color blend(Color a, Color b, double t)
    {
        t = Math.max(0.0, Math.min(1.0, t));
        int r = (int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t);
        return new Color(r, g, bl);
    }
}
