package com.runealytics;

import java.awt.*;
import javax.swing.*;

/**
 * A FlowLayout that wraps components and enforces a fixed number of columns per row.
 * Items keep their preferred size and spacing.
 */
public class FixedColumnsWrapLayout extends FlowLayout
{
    private final int columns;

    public FixedColumnsWrapLayout(int columns, int hgap, int vgap)
    {
        super(FlowLayout.LEFT, hgap, vgap);
        this.columns = columns;
    }

    @Override
    public Dimension preferredLayoutSize(Container target)
    {
        synchronized (target.getTreeLock())
        {
            int nComponents = target.getComponentCount();
            if (nComponents == 0)
                return new Dimension(0, 0);

            int width = 0;
            int height = getVgap();
            int rowWidth = 0;
            int rowHeight = 0;
            int count = 0;

            for (int i = 0; i < nComponents; i++)
            {
                Component comp = target.getComponent(i);
                if (!comp.isVisible())
                    continue;

                Dimension d = comp.getPreferredSize();

                rowWidth += d.width + getHgap();
                rowHeight = Math.max(rowHeight, d.height);
                count++;

                if (count == columns)
                {
                    width = Math.max(width, rowWidth);
                    height += rowHeight + getVgap();
                    rowWidth = 0;
                    rowHeight = 0;
                    count = 0;
                }
            }

            // Add last row
            if (count > 0)
            {
                width = Math.max(width, rowWidth);
                height += rowHeight + getVgap();
            }

            Insets insets = target.getInsets();
            width += insets.left + insets.right;
            height += insets.top + insets.bottom;

            return new Dimension(width, height);
        }
    }

    @Override
    public Dimension minimumLayoutSize(Container target)
    {
        return preferredLayoutSize(target);
    }
}
