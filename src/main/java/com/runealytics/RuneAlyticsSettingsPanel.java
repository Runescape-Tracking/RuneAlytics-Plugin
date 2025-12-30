package com.runealytics;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;

@Singleton
public class RuneAlyticsSettingsPanel extends JPanel
{
    // Display area where MaterialTabGroup will put the selected tab's content
    private final JPanel display = new JPanel(new BorderLayout());

    // MaterialTabGroup manages the tab strip and swaps content into `display`
    private final MaterialTabGroup tabGroup = new MaterialTabGroup(display);

    private final RuneAlyticsVerificationPanel verificationPanel;

    @Inject
    public RuneAlyticsSettingsPanel(RuneAlyticsVerificationPanel verificationPanel)
    {
        this.verificationPanel = verificationPanel;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setOpaque(true);

        buildTabs();
    }

    private void buildTabs()
    {
        // Style the tab strip
        tabGroup.setLayout(new BoxLayout(tabGroup, BoxLayout.X_AXIS));
        tabGroup.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tabGroup.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        tabGroup.setOpaque(true);

        // Style the display area (content panel)
        display.setBackground(ColorScheme.DARK_GRAY_COLOR);
        display.setOpaque(true);

        // Create the "Verification" tab and associate it with the verification panel
        MaterialTab verificationTab = new MaterialTab("Verification", tabGroup, verificationPanel);
        tabGroup.addTab(verificationTab);

        // IMPORTANT: select via the *group*, so it swaps the content into `display`
        tabGroup.select(verificationTab);

        add(tabGroup, BorderLayout.NORTH);
        add(display, BorderLayout.CENTER);
    }

    /** Called externally when login state or context changes */
    public void refreshLoginState()
    {
        verificationPanel.refreshLoginState();
    }
}
