package com.runealytics;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.util.List;

/**
 * Lightweight custom-painted XP/hr trend chart for the XP Tracker.
 *
 * <p>Deliberately avoids any charting library — it plots the rolling samples as a
 * filled sparkline (X = time, Y = value, 0→max). Hovering shows the value at that
 * point via a tooltip and a marker dot. With fewer than two samples it draws a
 * flat baseline so it never looks broken on a fresh session.</p>
 */
class XpSparkline extends JComponent
{
    private static final Color GRID   = new Color(40, 46, 64);
    private static final Color LINE_DEFAULT = new Color(120, 150, 245);
    private static final Color MARKER = new Color(235, 238, 245);

    private Color lineColor = LINE_DEFAULT;

    private List<RuneAlyticsXpSkillState.Sample> samples = java.util.Collections.emptyList();

    // Cheap signature of the last-painted data, so we skip repaints (and the
    // resulting visual churn) when the samples have not actually changed.
    private int  lastSize = -1;
    private long lastSig  = Long.MIN_VALUE;

    // Plot geometry captured on the last paint, so hover math maps mouse→sample.
    private int  gPadL, gPlotW;
    private long gT0, gSpan;

    private int hoverIdx = -1;

    XpSparkline()
    {
        setOpaque(false);
        setPreferredSize(new Dimension(200, 90));
        setMinimumSize(new Dimension(120, 60));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

        // Enable dynamic tooltips (see getToolTipText below).
        ToolTipManager.sharedInstance().registerComponent(this);

        addMouseMotionListener(new MouseMotionAdapter()
        {
            @Override public void mouseMoved(MouseEvent e)
            {
                int idx = nearestIndex(e.getX());
                if (idx != hoverIdx) { hoverIdx = idx; repaint(); }
            }
        });
        addMouseListener(new MouseAdapter()
        {
            @Override public void mouseExited(MouseEvent e)
            {
                if (hoverIdx != -1) { hoverIdx = -1; repaint(); }
            }
        });
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
        if (hoverIdx >= size) hoverIdx = -1;
        if (size == lastSize && sig == lastSig && hoverIdx < 0) return; // no visual change
        lastSize = size;
        lastSig  = sig;
        repaint();
    }

    /** Index of the sample nearest the given pixel X, or {@code -1}. */
    private int nearestIndex(int mx)
    {
        List<RuneAlyticsXpSkillState.Sample> data = samples;
        if (data.size() < 2 || gSpan <= 0) return -1;
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < data.size(); i++)
        {
            double fx = (data.get(i).timeMs - gT0) / (double) gSpan;
            int x = gPadL + (int) Math.round(fx * gPlotW);
            int d = Math.abs(x - mx);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    @Override
    public String getToolTipText(MouseEvent e)
    {
        int idx = nearestIndex(e.getX());
        if (idx < 0 || idx >= samples.size()) return null;
        RuneAlyticsXpSkillState.Sample s = samples.get(idx);
        String when = XpFormat.ago(System.currentTimeMillis() - s.timeMs);
        return "<html><b>" + XpFormat.compactUpper(s.totalGained) + "</b> xp/hr<br>"
                + "<span style='color:#9aa2b2'>" + when + "</span></html>";
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
            int baseY = h - padB;

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
                gSpan = 0L; // disable hover mapping until we have a trend
                g2.setColor(lineCol);
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(padL, baseY, w - padR, baseY);
                return;
            }

            long t0 = data.get(0).timeMs;
            long t1 = data.get(data.size() - 1).timeMs;
            long span = Math.max(1L, t1 - t0);
            long maxY = 1L;
            for (RuneAlyticsXpSkillState.Sample s : data) maxY = Math.max(maxY, s.totalGained);

            // Publish geometry for hover math.
            gPadL = padL; gPlotW = plotW; gT0 = t0; gSpan = span;

            GeneralPath line = new GeneralPath();
            Path2D.Float area = new Path2D.Float();
            boolean started = false;
            int lastX = padL;

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

            // Hover marker: vertical guide + dot at the nearest sample.
            if (hoverIdx >= 0 && hoverIdx < data.size())
            {
                RuneAlyticsXpSkillState.Sample s = data.get(hoverIdx);
                double fx = (s.timeMs - t0) / (double) span;
                double fy = s.totalGained / (double) maxY;
                int x = padL + (int) Math.round(fx * plotW);
                int y = padT + (int) Math.round((1.0 - fy) * plotH);

                g2.setColor(new Color(255, 255, 255, 45));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(x, padT, x, baseY);

                g2.setColor(lineCol);
                g2.fillOval(x - 3, y - 3, 6, 6);
                g2.setColor(MARKER);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(x - 3, y - 3, 6, 6);
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
