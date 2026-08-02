package com.runealytics;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.util.List;

/**
 * Lightweight custom-painted XP/hr trend chart for the XP Tracker.
 *
 * <p>Deliberately avoids any charting library — it plots the rolling samples as a
 * filled sparkline (X = time, Y = value, 0→scale). Hovering shows the value at
 * that point via a tooltip and a marker dot. With fewer than two samples it draws
 * a flat baseline so it never looks broken on a fresh session.</p>
 *
 * <p>The Y axis is <b>outlier-robust</b>: rather than scaling to the single
 * largest sample (which lets the big up-front spike when you start skilling
 * flatten everything else), it scales to a high percentile of the recent
 * samples and rounds that up to a "nice" number (1/2/2.5/5 × 10ⁿ). A handful of
 * 400k spikes therefore no longer wreck a 40k–100k frame — the spikes just clip
 * to the top of the chart. The scale is only recomputed on a periodic "pulse"
 * (every few seconds) so the frame stays stable instead of twitching on every
 * new sample. Mouse-wheel zooms the vertical scale; double-click resets it.</p>
 */
class XpSparkline extends JComponent
{
    private static final Color GRID   = new Color(40, 46, 64);
    private static final Color LINE_DEFAULT = new Color(120, 150, 245);
    private static final Color MARKER = new Color(235, 238, 245);
    private static final Color AXIS_TEXT = new Color(120, 130, 155);
    private static final Font  AXIS_FONT = new Font("Calibri", Font.PLAIN, 9);

    // How often (ms) the Y-scale is allowed to re-fit. Between pulses the frame
    // is held steady so it does not jitter on every incoming sample.
    private static final long   SCALE_PULSE_MS = 4000L;
    // Percentile of samples the frame is fitted to (spikes above this clip).
    private static final double FIT_PERCENTILE = 0.90;
    // A little breathing room above the fitted percentile before nice-rounding.
    private static final double HEADROOM = 1.15;

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

    // Pulsed, nice-rounded Y-scale. Recomputed at most every SCALE_PULSE_MS.
    private long cachedNiceMax   = 0L;
    private long lastScalePulseMs = 0L;

    // User zoom on the vertical scale (1.0 = auto-fit). >1 zooms in.
    private double zoom = 1.0;

    XpSparkline()
    {
        setOpaque(false);
        setPreferredSize(new Dimension(200, 90));
        setMinimumSize(new Dimension(120, 60));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        setToolTipText("");

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

            @Override public void mouseClicked(MouseEvent e)
            {
                if (e.getClickCount() >= 2)
                {
                    zoom = 1.0;
                    cachedNiceMax = 0L; // force a re-fit on next paint
                    repaint();
                }
            }
        });
        addMouseWheelListener(new MouseWheelListener()
        {
            @Override public void mouseWheelMoved(MouseWheelEvent e)
            {
                double factor = (e.getWheelRotation() < 0) ? 1.15 : (1.0 / 1.15);
                double next = zoom * factor;
                next = Math.max(0.25, Math.min(8.0, next));
                if (next != zoom) { zoom = next; repaint(); }
            }
        });
    }

    /** Sets the line/fill accent colour (e.g. the skill's colour). */
    void setLineColor(Color c)
    {
        Color next = (c != null) ? c : LINE_DEFAULT;
        if (next.equals(lineColor)) return;
        lineColor = next;
        cachedNiceMax = 0L; // different skill → re-fit the frame immediately
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

    /**
     * The Y-axis maximum in use right now. Refits (via {@link #computeNiceMax()})
     * only once per pulse window so the frame stays stable, then divides by the
     * user zoom so scrolling in tightens the vertical scale.
     */
    private long currentScaleMax(long nowMs)
    {
        if (cachedNiceMax <= 0L || nowMs - lastScalePulseMs >= SCALE_PULSE_MS)
        {
            cachedNiceMax = computeNiceMax();
            lastScalePulseMs = nowMs;
        }
        return Math.max(1L, Math.round(cachedNiceMax / zoom));
    }

    /**
     * Fits the frame to the {@link #FIT_PERCENTILE} of the sample values (so a
     * few big spikes don't dominate), adds {@link #HEADROOM}, and rounds up to a
     * nice number. Falls back to the plain max when there are too few samples.
     */
    private long computeNiceMax()
    {
        List<RuneAlyticsXpSkillState.Sample> data = samples;
        int n = data.size();
        if (n == 0) return 1L;

        long[] vals = new long[n];
        for (int i = 0; i < n; i++) vals[i] = Math.max(0L, data.get(i).totalGained);
        java.util.Arrays.sort(vals);

        // Percentile value: the "typical high" ignoring the top outliers.
        int pIdx = (int) Math.floor(FIT_PERCENTILE * (n - 1));
        if (pIdx < 0) pIdx = 0;
        if (pIdx >= n) pIdx = n - 1;
        long pct = vals[pIdx];
        if (pct <= 0L) pct = vals[n - 1]; // all-zero percentile → use the true max

        return niceRound((long) Math.ceil(pct * HEADROOM));
    }

    /** Rounds a positive value up to the next 1/2/2.5/5 × 10ⁿ "nice" number. */
    private static long niceRound(long v)
    {
        if (v <= 1L) return 1L;
        double mag = Math.pow(10, Math.floor(Math.log10(v)));
        double norm = v / mag; // in [1, 10)
        double nice;
        if (norm <= 1.0) nice = 1.0;
        else if (norm <= 2.0) nice = 2.0;
        else if (norm <= 2.5) nice = 2.5;
        else if (norm <= 5.0) nice = 5.0;
        else nice = 10.0;
        return (long) Math.ceil(nice * mag);
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
            int padL = 30, padR = 6, padT = 6, padB = 6; // left gutter holds Y labels
            int plotW = Math.max(1, w - padL - padR);
            int plotH = Math.max(1, h - padT - padB);
            int baseY = h - padB;

            long nowMs = System.currentTimeMillis();
            long maxY = currentScaleMax(nowMs);

            // Baseline gridlines (quarters) with right-aligned Y-axis labels.
            g2.setStroke(new BasicStroke(1f));
            g2.setFont(AXIS_FONT);
            for (int i = 0; i <= 4; i++)
            {
                int y = padT + (int) Math.round(plotH * (i / 4.0));
                g2.setColor(GRID);
                g2.drawLine(padL, y, w - padR, y);

                // Labels for the top three lines (i = 0,1,2,3); skip the 0 axis.
                if (i <= 3)
                {
                    long val = Math.round(maxY * (1.0 - i / 4.0));
                    String lbl = XpFormat.compactUpper(val);
                    int tw = g2.getFontMetrics().stringWidth(lbl);
                    int ty = y + 3;
                    if (i == 0) ty = y + 8; // nudge the very top label down
                    g2.setColor(AXIS_TEXT);
                    g2.drawString(lbl, padL - 4 - tw, ty);
                }
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

            // Publish geometry for hover math.
            gPadL = padL; gPlotW = plotW; gT0 = t0; gSpan = span;

            GeneralPath line = new GeneralPath();
            Path2D.Float area = new Path2D.Float();
            boolean started = false;
            int lastX = padL;

            for (RuneAlyticsXpSkillState.Sample s : data)
            {
                double fx = (s.timeMs - t0) / (double) span;
                double fy = Math.min(1.0, s.totalGained / (double) maxY); // clip spikes
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
                double fy = Math.min(1.0, s.totalGained / (double) maxY);
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
