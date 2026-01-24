package com.runealytics;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("loottracker")
public interface LootTrackerConfig extends Config
{
    @ConfigItem(
            keyName = "enabled",
            name = "Enable Loot Tracking",
            description = "Enable or disable loot tracking"
    )
    default boolean enabled()
    {
        return true;
    }

    @ConfigItem(
            keyName = "autoSync",
            name = "Auto Sync to Server",
            description = "Automatically sync loot to RuneAlytics server"
    )
    default boolean autoSync()
    {
        return true;
    }

    @ConfigItem(
            keyName = "minValueToTrack",
            name = "Minimum Value to Track",
            description = "Minimum GP value to track items (0 = track all)"
    )
    default int minValueToTrack()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "showNotifications",
            name = "Show Notifications",
            description = "Show notifications for valuable drops"
    )
    default boolean showNotifications()
    {
        return true;
    }

    @ConfigItem(
            keyName = "notificationThreshold",
            name = "Notification Value Threshold",
            description = "Minimum GP value to trigger notifications"
    )
    default int notificationThreshold()
    {
        return 1000000;
    }
}