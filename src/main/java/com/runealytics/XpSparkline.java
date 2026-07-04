package com.runealytics;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.util.List;

class XpSparkline extends JComponent
{
    private static final Color GRID = new Color(95, 95, 95, 120);
    private static final Color GOLD_LABEL = new Color(198, 166, 52);
    private static final Color LINE_DEFAULT = new Color(120, 90, 255);
    private static final Color MARKER = new Color(235, 238, 245);

    private static final int GRID_LINES = 5;

    private static final long MIN_INCREMENT = 25_000L;
    private static final long MID_INCREMENT = 50_000L;
    private static final long HIGH_INCREMENT = 100_000L;

    private Color lineColor = LINE_DEFAULT;
    private List<RuneAlyticsXpSkillState.Sample> samples = java.util.Collections.emptyList();

    private int lastSize = -1;
    private long lastSig = Long.MIN_VALUE;

    private int gPadL, gPlotW;
    private long gT0, gSpan;

    private int hoverIdx = -1;

    XpSparkline()
    {
        setOpaque(false);
        setPreferredSize(new Dimension(200, 90));
        setMinimumSize(new Dimension(120, 60));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

        ToolTipManager.sharedInstance().registerComponent(this);

        addMouseMotionListener(new MouseMotionAdapter()
        {
            @Override public void mouseMoved(MouseEvent e)
            {
                int idx = nearestIndex(e.getX());
                if (idx != hoverIdx)
                {
                    hoverIdx = idx;
                    repaint();
                }
            }
        });

        addMouseListener(new MouseAdapter()
        {
            @Override public void mouseExited(MouseEvent e)
            {
                if (hoverIdx != -1)
                {
                    hoverIdx = -1;
                    repaint();
                }
            }
        });
    }

    void setLineColor(Color c)
    {
        Color next = c != null ? c : LINE_DEFAULT;
        if (next.equals(lineColor))
        {
            return;
        }

        lineColor = next;
        repaint();
    }

    void setSamples(List<RuneAlyticsXpSkillState.Sample> s)
    {
        List<RuneAlyticsXpSkillState.Sample> next =
                s != null ? s : java.util.Collections.emptyList();

        samples = next;

        int size = next.size();
        long sig = 0L;

        if (size > 0)
        {
            RuneAlyticsXpSkillState.Sample last = next.get(size - 1);
            sig = last.timeMs * 31L + last.totalGained;
        }

        if (hoverIdx >= size)
        {
            hoverIdx = -1;
        }

        if (size == lastSize && sig == lastSig && hoverIdx < 0)
        {
            return;
        }

        lastSize = size;
        lastSig = sig;
        repaint();
    }

    private int nearestIndex(int mx)
    {
        List<RuneAlyticsXpSkillState.Sample> data = samples;

        if (data.size() < 2 || gSpan <= 0)
        {
            return -1;
        }

        int best = -1;
        int bestDist = Integer.MAX_VALUE;

        for (int i = 0; i < data.size(); i++)
        {
            double fx = (data.get(i).timeMs - gT0) / (double) gSpan;
            int x = gPadL + (int) Math.round(fx * gPlotW);
            int d = Math.abs(x - mx);

            if (d < bestDist)
            {
                bestDist = d;
                best = i;
            }
        }

        return best;
    }

    @Override
    public String getToolTipText(MouseEvent e)
    {
        int idx = nearestIndex(e.getX());

        if (idx < 0 || idx >= samples.size())
        {
            return null;
        }

        RuneAlyticsXpSkillState.Sample s = samples.get(idx);
        String when = XpFormat.ago(System.currentTimeMillis() - s.timeMs);

        return "<html><b>" + XpFormat.compactUpper(s.totalGained) + "</b> xp/hr<br>"
                + "<span style='color:#9aa2b2'>" + when + "</span></html>";
    }

    private static long incrementFor(long maxValue)
    {
        if (maxValue > 250_000L)
        {
            return HIGH_INCREMENT;
        }

        if (maxValue > 100_000L)
        {
            return MID_INCREMENT;
        }

        return MIN_INCREMENT;
    }

    private static long roundUpToIncrement(long value, long increment)
    {
        long top = increment * (GRID_LINES - 1);

        while (top < value)
        {
            top += increment;
        }

        return Math.max(top, increment);
    }

    private static String axisLabel(long value)
    {
        if (value >= 1_000_000L)
        {
            double m = value / 1_000_000.0;
            return m == Math.floor(m)
                    ? String.format("%.0fM", m)
                    : String.format("%.1fM", m);
        }

        if (value >= 1_000L)
        {
            return (value / 1_000L) + "k";
        }

        return Long.toString(value);
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        Graphics2D g2 = (Graphics2D) g.create();

        try
        {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            int padL = 6;
            int padR = 6;
            int padT = 6;
            int padB = 6;

            int plotW = Math.max(1, w - padL - padR);
            int plotH = Math.max(1, h - padT - padB);
            int baseY = h - padB;

            List<RuneAlyticsXpSkillState.Sample> data = samples;

            long rawMax = 1L;
            for (RuneAlyticsXpSkillState.Sample s : data)
            {
                rawMax = Math.max(rawMax, s.totalGained);
            }

            long increment = incrementFor(rawMax);
            long maxY = roundUpToIncrement(rawMax, increment);

            Font originalFont = g2.getFont();
            Font labelFont = originalFont.deriveFont(Font.BOLD, 8.5f);
            g2.setFont(labelFont);
            FontMetrics fm = g2.getFontMetrics();

            int widestLabel = 0;
            for (int i = 0; i < GRID_LINES; i++)
            {
                widestLabel = Math.max(widestLabel, fm.stringWidth(axisLabel(i * increment)));
            }

            g2.setStroke(new BasicStroke(
                    1f,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL,
                    0,
                    new float[]{5f, 6f},
                    0
            ));

            for (int i = 0; i < GRID_LINES; i++)
            {
                long value = i * increment;
                double fy = value / (double) maxY;
                int y = padT + (int) Math.round((1.0 - fy) * plotH);

                String label = axisLabel(value);

                int labelX = padL + widestLabel - fm.stringWidth(label);
                int labelY = y - 2;
                int lineStartX = padL + widestLabel + 8;

                if (i == 0)
                {
                    labelY = y - 3;
                }
                else if (i == GRID_LINES - 1)
                {
                    labelY = y + fm.getAscent();
                }

                g2.setColor(GOLD_LABEL);
                g2.drawString(label, labelX, labelY);

                g2.setColor(GRID);
                g2.drawLine(lineStartX, y, w - padR, y);
            }

            g2.setFont(originalFont);

            Color lineCol = lineColor;
            Color fillTop = new Color(lineCol.getRed(), lineCol.getGreen(), lineCol.getBlue(), 80);
            Color fillBot = new Color(lineCol.getRed(), lineCol.getGreen(), lineCol.getBlue(), 0);

            if (data.size() < 2)
            {
                gSpan = 0L;

                g2.setColor(lineCol);
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(padL, baseY, w - padR, baseY);
                return;
            }

            long t0 = data.get(0).timeMs;
            long t1 = data.get(data.size() - 1).timeMs;
            long span = Math.max(1L, t1 - t0);

            gPadL = padL;
            gPlotW = plotW;
            gT0 = t0;
            gSpan = span;

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