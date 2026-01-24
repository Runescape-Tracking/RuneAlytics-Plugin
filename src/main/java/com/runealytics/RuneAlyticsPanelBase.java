package com.runealytics;

import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import java.awt.*;

public abstract class RuneAlyticsPanelBase extends JPanel
{
    protected RuneAlyticsPanelBase()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);
        setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    protected void addSectionTitle(String title)
    {
        add(RuneAlyticsUi.titleLabel(title));
        add(RuneAlyticsUi.vSpace(4));
    }

    protected void addSubtitle(String text)
    {
        add(RuneAlyticsUi.subtitleLabel(text));
        add(RuneAlyticsUi.vSpace(8));
    }

    /**
     * Called when data is refreshed
     * Subclasses should override this to update their display
     */
    public abstract void onDataRefresh();
}