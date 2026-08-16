package com.runealytics;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Server-provided logging configuration.
 *
 * <p>Specifies which categories of logs should be enabled for this player.
 * By default, all categories are enabled. The server can send a
 * {@code log_config} object in the heartbeat response to override defaults.</p>
 *
 * <h2>Configuration Format (from server heartbeat response)</h2>
 * <pre>
 * {
 *   "log_config": {
 *     "loot_tracker": true,
 *     "xp_tracker": true,
 *     "matchmaking": false,
 *     "loot_sync": true,
 *     "api_client": true,
 *     "death_recovery": true,
 *     "live_map": false,
 *     "skill_economy": true
 *   }
 * }
 * </pre>
 */
@Slf4j
public class LogConfiguration
{
    /**
     * Known log categories that can be enabled/disabled.
     */
    public enum LogCategory
    {
        LOOT_TRACKER("loot_tracker"),
        XP_TRACKER("xp_tracker"),
        MATCHMAKING("matchmaking"),
        LOOT_SYNC("loot_sync"),
        API_CLIENT("api_client"),
        DEATH_RECOVERY("death_recovery"),
        LIVE_MAP("live_map"),
        SKILL_ECONOMY("skill_economy"),
        HEARTBEAT("heartbeat"),
        WIDGET_LOADING("widget_loading"),
        LOOT_PENDING("loot_pending"),
        INVENTORY_DIFF("inventory_diff"),
        CHAT_MESSAGES("chat_messages");

        public final String key;

        LogCategory(String key)
        {
            this.key = key;
        }
    }

    /**
     * Default enabled categories. By default, all logging is enabled unless
     * the server specifies otherwise.
     */
    private static final Set<LogCategory> DEFAULT_ENABLED = ImmutableSet.copyOf(
            java.util.Arrays.asList(LogCategory.values())
    );

    /**
     * Per-category enable/disable state.
     */
    private final Map<LogCategory, Boolean> categoryState = new HashMap<>();

    public LogConfiguration()
    {
        // Initialize with defaults (all enabled)
        for (LogCategory cat : LogCategory.values())
        {
            categoryState.put(cat, true);
        }
    }

    /**
     * Parses log configuration from a server heartbeat response.
     * If the response contains a {@code log_config} object, applies those
     * settings. Missing categories keep their current state.
     *
     * @param responseJson the full JSON response from the heartbeat endpoint
     */
    public void updateFromHeartbeatResponse(String responseJson)
    {
        if (responseJson == null || responseJson.isEmpty()) return;

        try
        {
            JsonObject root = com.google.gson.JsonParser.parse(responseJson).getAsJsonObject();
            if (!root.has("log_config")) return;

            JsonObject logConfig = root.getAsJsonObject("log_config");

            for (LogCategory cat : LogCategory.values())
            {
                if (logConfig.has(cat.key))
                {
                    boolean enabled = logConfig.get(cat.key).getAsBoolean();
                    categoryState.put(cat, enabled);
                }
            }

            log.debug("Updated log configuration from heartbeat response");
        }
        catch (Exception e)
        {
            log.debug("Failed to parse log_config from heartbeat: {}", e.getMessage());
        }
    }

    /**
     * Checks if a log category is enabled.
     *
     * @param category the category to check
     * @return true if logging for this category is enabled
     */
    public boolean isEnabled(LogCategory category)
    {
        return categoryState.getOrDefault(category, true);
    }

    /**
     * Sets the enabled state for a category (for testing/debugging).
     *
     * @param category the category
     * @param enabled  whether to enable or disable
     */
    public void setEnabled(LogCategory category, boolean enabled)
    {
        categoryState.put(category, enabled);
    }

    /**
     * Returns a debug string showing the current configuration.
     */
    public String debugString()
    {
        StringBuilder sb = new StringBuilder("LogConfiguration: ");
        for (LogCategory cat : LogCategory.values())
        {
            sb.append(cat.key).append("=").append(isEnabled(cat) ? "on" : "off").append(" ");
        }
        return sb.toString();
    }
}
