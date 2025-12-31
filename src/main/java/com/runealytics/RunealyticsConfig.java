package com.runealytics;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("runealytics")
public interface RunealyticsConfig extends Config
{
    @ConfigSection(
            name = "Authentication",
            description = "Authentication settings",
            position = 0
    )
    String authSection = "authentication";

    @ConfigSection(
            name = "Sync Settings",
            description = "Data synchronization settings",
            position = 1
    )
    String syncSection = "sync";

    @ConfigSection(
            name = "Advanced",
            description = "Advanced configuration options",
            position = 2
    )
    String advancedSection = "advanced";

    // ==================== AUTHENTICATION ====================

    @ConfigItem(
            keyName = "authToken",
            name = "Auth Token",
            description = "Managed by the RuneAlytics plugin when you link your account.",
            section = authSection,
            position = 0,
            secret = true
    )
    default String authToken()
    {
        return "";
    }

    @ConfigItem(
            keyName = "authToken",
            name = "Auth Token",
            description = "Managed by the RuneAlytics plugin when you link your account.",
            section = authSection,
            position = 0,
            secret = true
    )
    void authToken(String token);

    @ConfigItem(
            keyName = "apiUrl",
            name = "API URL",
            description = "Runealytics API endpoint (change only if using custom server)",
            section = authSection,
            hidden = true,
            position = 1
    )
    default String apiUrl()
    {
        return "https://runealytics.com/api";
    }

    // ==================== SYNC SETTINGS ====================

    @ConfigItem(
            keyName = "enableXpTracking",
            name = "Enable XP Tracking",
            description = "Automatically sync XP gains to Runealytics",
            section = syncSection,
            position = 0
    )
    default boolean enableXpTracking()
    {
        return true;
    }

    @ConfigItem(
            keyName = "minXpGain",
            name = "Minimum XP Gain",
            description = "Only record XP gains above this amount (0 = record all gains)",
            section = syncSection,
            position = 1
    )
    default int minXpGain()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "enableBankSync",
            name = "Enable Bank Sync",
            description = "Automatically sync bank data when opening bank",
            section = syncSection,
            position = 2
    )
    default boolean enableBankSync()
    {
        return true;
    }

    @ConfigItem(
            keyName = "showSyncNotifications",
            name = "Show Sync Notifications",
            description = "Show in-game notifications when data is synced",
            section = syncSection,
            position = 3
    )
    default boolean showSyncNotifications()
    {
        return false;
    }

    // ==================== ADVANCED ====================

    @ConfigItem(
            keyName = "syncTimeout",
            name = "Sync Timeout (seconds)",
            description = "Timeout for API requests in seconds",
            section = advancedSection,
            position = 0
    )
    default int syncTimeout()
    {
        return 30;
    }

    @ConfigItem(
            keyName = "retryFailedSyncs",
            name = "Retry Failed Syncs",
            description = "Automatically retry failed syncs",
            section = advancedSection,
            position = 1
    )
    default boolean retryFailedSyncs()
    {
        return true;
    }

    @ConfigItem(
            keyName = "maxRetryAttempts",
            name = "Max Retry Attempts",
            description = "Maximum number of retry attempts for failed syncs",
            section = advancedSection,
            position = 2
    )
    default int maxRetryAttempts()
    {
        return 3;
    }

    @ConfigItem(
            keyName = "debugMode",
            name = "Debug Mode",
            description = "Enable detailed logging for troubleshooting",
            section = advancedSection,
            position = 3
    )
    default boolean debugMode()
    {
        return false;
    }

    @ConfigItem(
            keyName = "enableAutoVerification",
            name = "Auto Verification Check",
            description = "Automatically check verification status on login",
            section = advancedSection,
            position = 4
    )
    default boolean enableAutoVerification()
    {
        return true;
    }
}
