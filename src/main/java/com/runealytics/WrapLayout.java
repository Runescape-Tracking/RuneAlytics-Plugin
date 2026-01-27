package com.runealytics;

import java.awt.*;
import javax.swing.*;

public class WrapLayout extends FlowLayout
{
    public WrapLayout(int align, int hgap, int vgap)
    {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target)
    {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target)
    {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred)
    {
        synchronized (target.getTreeLock())
        {
            int targetWidth = target.getWidth();
            if (targetWidth == 0)
            {
                targetWidth = Integer.MAX_VALUE;
            }

            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();

            int maxWidth = targetWidth - (insets.left + insets.right + hgap * 2);

            Dimension dim = new Dimension(0, 0);

            int rowWidth = 0;
            int rowHeight = 0;

            for (Component comp : target.getComponents())
            {
                if (!comp.isVisible())
                {
                    continue;
                }

                Dimension d = preferred ? comp.getPreferredSize() : comp.getMinimumSize();

                if (rowWidth + d.width > maxWidth)
                {
                    dim.width = Math.max(dim.width, rowWidth);
                    dim.height += rowHeight + vgap;
                    rowWidth = 0;
                    rowHeight = 0;
                }

                if (rowWidth != 0)
                {
                    rowWidth += hgap;
                }

                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }

            dim.width = Math.max(dim.width, rowWidth);
            dim.height += rowHeight;

            dim.width += insets.left + insets.right;
            dim.height += insets.top + insets.bottom;

            return dim;
        }
    }
}
