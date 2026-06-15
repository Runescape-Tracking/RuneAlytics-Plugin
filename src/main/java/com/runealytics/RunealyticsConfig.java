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

    // ==================== LOOT TRACKING ====================

    @ConfigSection(
            name = "Loot Tracking",
            description = "Configure loot and boss kill tracking",
            position = 3
    )
    String lootSection = "loot";

    /**
     * Loot tracking is always enabled — it is the core feature of RuneAlytics.
     *
     * <p>The {@code @ConfigItem} annotation is kept so RuneLite's config proxy
     * can generate a valid implementation (removing it causes the proxy to
     * return {@code null}, which crashes with NPE on every call). The item is
     * hidden from the settings UI so users cannot accidentally turn it off.</p>
     */
    @ConfigItem(
            keyName = "enableLootTracking",
            name = "Enable Loot Tracking",
            description = "Always on — loot tracking is a core RuneAlytics feature.",
            section = lootSection,
            position = 0,
            hidden = true
    )
    default boolean enableLootTracking()
    {
        return true;
    }

    @ConfigItem(
            keyName = "minimumLootValue",
            name = "Minimum Loot Value",
            description = "Only track drops worth more than this amount (0 = track all)",
            section = lootSection,
            position = 1
    )
    default int minimumLootValue()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "syncLootToServer",
            name = "Sync to RuneAlytics",
            description = "Automatically sync loot data to RuneAlytics server",
            section = lootSection,
            position = 2
    )
    default boolean syncLootToServer()
    {
        return true;
    }

    @ConfigItem(
            keyName = "trackAllNpcs",
            name = "Track All NPCs",
            description = "Track loot from all NPCs, not just bosses",
            section = lootSection,
            position = 3
    )
    default boolean trackAllNpcs()
    {
        return true;
    }

    /**
     * When enabled, every successful pickpocket / thieving action is recorded
     * as a separate loot entry in the Loot Tracker.  Each success increments
     * the pickpocket counter for that NPC and accumulates the items gained.
     *
     * <p>Stalls (e.g. Bakery Stall, Gem Stall) are also captured because they
     * share the "Pickpocket" menu-option path.</p>
     */
    @ConfigItem(
            keyName = "enablePickpocketTracking",
            name = "Track Pickpocketing",
            description = "Track loot and attempt counts from pickpocketing / thieving",
            section = lootSection,
            position = 4
    )
    default boolean enablePickpocketTracking()
    {
        return true;
    }

    @ConfigItem(
            keyName = "gridMasterMode",
            name = "Grid Master Mode",
            description = "Tags all loot and XP syncs as Grid Master challenge mode. "
                    + "Enable when participating in a Grid Master run so your data is "
                    + "separated from regular and ironman profiles on RuneAlytics.",
            section = lootSection,
            position = 10
    )
    default boolean gridMasterMode()
    {
        return false;
    }

    // ==================== PRIVACY ====================

    @ConfigSection(
            name = "Privacy",
            description = "Control who can see your data on RuneAlytics.com",
            position = 4
    )
    String privacySection = "privacy";

    @ConfigItem(
            keyName = "bankPrivacy",
            name = "Bank Visibility",
            description = "Who can see your bank value and contents on RuneAlytics.com",
            section = privacySection,
            position = 0
    )
    default PrivacySetting bankPrivacy()
    {
        return PrivacySetting.PUBLIC;
    }

    @ConfigItem(
            keyName = "bankPrivacy",
            name = "Bank Visibility",
            description = "Who can see your bank value and contents on RuneAlytics.com",
            section = privacySection,
            position = 0
    )
    void bankPrivacy(PrivacySetting value);

    @ConfigItem(
            keyName = "playerVisibility",
            name = "Online Status",
            description = "Who can see your online status on RuneAlytics.com",
            section = privacySection,
            position = 1
    )
    default PrivacySetting playerVisibility()
    {
        return PrivacySetting.PUBLIC;
    }

    @ConfigItem(
            keyName = "playerVisibility",
            name = "Online Status",
            description = "Who can see your online status on RuneAlytics.com",
            section = privacySection,
            position = 1
    )
    void playerVisibility(PrivacySetting value);

    @ConfigItem(
            keyName = "showMapPlayers",
            name = "Show Tracked Players",
            description = "Draw players shared with you by RuneAlytics on the minimap and game scene",
            section = privacySection,
            position = 2
    )
    default boolean showMapPlayers()
    {
        return true;
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