package com.runealytics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * The "you are visible" eye, drawn once in code (no image resources) and cached
 * by callers. A white almond with an amber iris and a dark outline, so it reads on
 * any game background; the crossed-out variant (hidden) has a grey iris and a
 * slash.
 */
final class PrivacyEyeIcon
{
    static final Color AMBER = new Color(255, 176, 32);
    private static final Color IRIS_HIDDEN = new Color(140, 140, 140);
    private static final Color SLASH = new Color(235, 235, 235);
    private static final Color OUTLINE = new Color(0, 0, 0, 210);

    private PrivacyEyeIcon()
    {
    }

    /** An amber eye {@code width} pixels wide (height is about 60% of that). */
    static BufferedImage create(int width)
    {
        return create(width, false);
    }

    /** As {@link #create(int)}; {@code crossed} draws the "hidden" eye with a slash. */
    static BufferedImage create(int width, boolean crossed)
    {
        int w = Math.max(12, width);
        int h = Math.max(8, Math.round(w * 0.6f));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            float pad = Math.max(1.5f, w / 16f);
            float left = pad;
            float right = w - pad;
            float mid = h / 2f;
            float lid = (h / 2f) - pad;

            Path2D.Float almond = new Path2D.Float();
            almond.moveTo(left, mid);
            almond.quadTo(w / 2f, mid - lid * 2f, right, mid);
            almond.quadTo(w / 2f, mid + lid * 2f, left, mid);
            almond.closePath();

            g.setColor(Color.WHITE);
            g.fill(almond);

            float irisR = lid * 0.95f;
            g.setColor(crossed ? IRIS_HIDDEN : AMBER);
            g.fill(new Ellipse2D.Float(w / 2f - irisR, mid - irisR, irisR * 2, irisR * 2));
            float pupilR = irisR * 0.45f;
            g.setColor(Color.BLACK);
            g.fill(new Ellipse2D.Float(w / 2f - pupilR, mid - pupilR, pupilR * 2, pupilR * 2));

            g.setColor(OUTLINE);
            g.setStroke(new BasicStroke(Math.max(1.5f, w / 14f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(almond);

            if (crossed)
            {
                float sw = Math.max(1.5f, w / 12f);
                g.setStroke(new BasicStroke(sw + 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(OUTLINE);
                g.draw(new Line2D.Float(left + pad, h - pad, right - pad, pad));
                g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(SLASH);
                g.draw(new Line2D.Float(left + pad, h - pad, right - pad, pad));
            }
        }
        finally
        {
            g.dispose();
        }
        return img;
    }
}
