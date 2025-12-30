package com.runealytics;

import net.runelite.client.ui.ColorScheme;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;

@Singleton
public class RuneAlyticsSettingsPanel extends JPanel
{
    private final JTabbedPane tabbedPane = new JTabbedPane();
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
        tabbedPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        tabbedPane.setForeground(ColorScheme.TEXT_COLOR);

        tabbedPane.addTab("Verification", verificationPanel);

        // future:
        // tabbedPane.addTab("General", generalSettingsPanel);

        add(tabbedPane, BorderLayout.CENTER);
    }

    /** Called externally when login state or context changes */
    public void refreshLoginState()
    {
        verificationPanel.refreshLoginState();
    }
}
