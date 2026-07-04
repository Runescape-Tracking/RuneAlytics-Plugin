package com.runealytics;

import net.runelite.client.ui.ColorScheme;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * A skill icon drawn centered on a filled circle so the dark RuneScape skill
 * sprites stay legible against the dark XP Tracker background.
 *
 * <p>The circle uses {@link ColorScheme#MEDIUM_GRAY_COLOR} and the sprite is
 * inset with a little padding.</p>
 */
class XpSkillIcon extends JComponent
{
    private static final Color CIRCLE_BG = ColorScheme.MEDIUM_GRAY_COLOR;

    private final int diameter;
    private final int pad;
    private BufferedImage image;

    XpSkillIcon(int diameter)
    {
        this.diameter = diameter;
        this.pad = Math.max(3, Math.round(diameter * 0.18f));
        Dimension d = new Dimension(diameter, diameter);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        setOpaque(false);
    }

    /** Sets (or clears, with {@code null}) the skill sprite. */
    void setImage(BufferedImage img)
    {
        this.image = img;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            g2.setColor(CIRCLE_BG);
            g2.fillOval(0, 0, diameter - 1, diameter - 1);

            if (image != null)
            {
                int avail = diameter - pad * 2;
                int iw = image.getWidth();
                int ih = image.getHeight();
                double scale = Math.min(1.0, Math.min(avail / (double) iw, avail / (double) ih));
                int w = (int) Math.round(iw * scale);
                int h = (int) Math.round(ih * scale);
                int x = (diameter - w) / 2;
                int y = (diameter - h) / 2;
                g2.drawImage(image, x, y, w, h, null);
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
