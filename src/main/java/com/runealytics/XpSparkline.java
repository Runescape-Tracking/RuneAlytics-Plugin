package com.runealytics;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.util.List;

/**
 * Lightweight custom-painted "XP gain over time" chart for the XP Tracker.
 *
 * <p>Deliberately avoids any charting library — it just plots the cumulative
 * gained-XP samples as a filled sparkline. The X axis is time (first→last
 * sample), the Y axis is cumulative XP (0→max). With fewer than two samples it
 * draws a flat baseline so it never looks broken on a fresh session.</p>
 */
class XpSparkline extends JComponent
{
    private static final Color GRID   = new Color(40, 46, 64);
    private static final Color LINE_DEFAULT = new Color(120, 150, 245);

    private Color lineColor = LINE_DEFAULT;

    private List<RuneAlyticsXpSkillState.Sample> samples = java.util.Collections.emptyList();

    // Cheap signature of the last-painted data, so we skip repaints (and the
    // resulting visual churn) when the samples have not actually changed.
    private int  lastSize = -1;
    private long lastSig  = Long.MIN_VALUE;

    XpSparkline()
    {
        setOpaque(false);
        setPreferredSize(new Dimension(200, 90));
        setMinimumSize(new Dimension(120, 60));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
    }

    /** Sets the line/fill accent colour (e.g. the skill's colour). */
    void setLineColor(Color c)
    {
        Color next = (c != null) ? c : LINE_DEFAULT;
        if (next.equals(lineColor)) return;
        lineColor = next;
        repaint();
    }

    void setSamples(List<RuneAlyticsXpSkillState.Sample> s)
    {
        List<RuneAlyticsXpSkillState.Sample> next = (s != null) ? s : java.util.Collections.emptyList();
        this.samples = next;

        int size = next.size();
        long sig = 0L;
        if (size > 0)
        {
            RuneAlyticsXpSkillState.Sample last = next.get(size - 1);
            sig = last.timeMs * 31L + last.totalGained;
        }
        if (size == lastSize && sig == lastSig) return; // no visual change → no repaint
        lastSize = size;
        lastSig  = sig;
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
            int padL = 6, padR = 6, padT = 6, padB = 6;
            int plotW = Math.max(1, w - padL - padR);
            int plotH = Math.max(1, h - padT - padB);

            // Baseline gridlines (quarters).
            g2.setColor(GRID);
            g2.setStroke(new BasicStroke(1f));
            for (int i = 0; i <= 4; i++)
            {
                int y = padT + (int) Math.round(plotH * (i / 4.0));
                g2.drawLine(padL, y, w - padR, y);
            }

            Color lineCol = lineColor;
            Color fillTop = new Color(lineCol.getRed(), lineCol.getGreen(), lineCol.getBlue(), 90);
            Color fillBot = new Color(lineCol.getRed(), lineCol.getGreen(), lineCol.getBlue(), 0);

            List<RuneAlyticsXpSkillState.Sample> data = samples;
            if (data.size() < 2)
            {
                // Flat baseline until we have a trend to draw.
                g2.setColor(lineCol);
                g2.setStroke(new BasicStroke(2f));
                int y = h - padB;
                g2.drawLine(padL, y, w - padR, y);
                return;
            }

            long t0 = data.get(0).timeMs;
            long t1 = data.get(data.size() - 1).timeMs;
            long span = Math.max(1L, t1 - t0);
            long maxY = 1L;
            for (RuneAlyticsXpSkillState.Sample s : data) maxY = Math.max(maxY, s.totalGained);

            GeneralPath line = new GeneralPath();
            Path2D.Float area = new Path2D.Float();
            boolean started = false;
            int lastX = padL, baseY = h - padB;

            for (RuneAlyticsXpSkillState.Sample s : data)
            {
                double fx = (s.timeMs - t0) / (double) span;
                double fy = s.totalGained / (double) maxY;
                int x = padL + (int) Math.round(fx * plotW);
                int y = padT + (int) Math.round((1.0 - fy) * plotH);

                if (!started)
                {
                    line.moveTo(x, y);
                    area.moveTo(x, baseY);
                    area.lineTo(x, y);
                    started = true;
                }
                else
                {
                    line.lineTo(x, y);
                    area.lineTo(x, y);
                }
                lastX = x;
            }
            area.lineTo(lastX, baseY);
            area.closePath();

            g2.setPaint(new GradientPaint(0, padT, fillTop, 0, baseY, fillBot));
            g2.fill(area);

            g2.setColor(lineCol);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(line);
        }
        finally
        {
            g2.dispose();
        }
    }
}
