package com.runealytics;

import com.google.gson.JsonObject;
import net.runelite.api.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;

@Singleton
public class XpTrackerManager
{
    private static final Logger log = LoggerFactory.getLogger(XpTrackerManager.class);

    private final RunealyticsApiClient apiClient;

    @Inject
    public XpTrackerManager(RunealyticsApiClient apiClient)
    {
        this.apiClient = apiClient;
    }

    public void recordXpGain(
            String token,
            String username,
            Skill skill,
            int xpGained,
            int totalXp,
            int currentLevel
    )
    {
        if (token == null || token.isEmpty())
        {
            log.debug("No auth token available, skipping XP record");
            return;
        }

        if (username == null || username.isEmpty())
        {
            log.warn("Username is null/empty, skipping XP record");
            return;
        }

        if (xpGained <= 0)
        {
            log.debug("XP gain is 0 or negative, skipping record");
            return;
        }

        try
        {
            JsonObject xpData = buildXpData(username, skill, xpGained, totalXp, currentLevel);

            log.info("Recording {} XP gain in {} for user: {} (Total: {}, Level: {})",
                    xpGained, skill.getName(), username, totalXp, currentLevel);

            boolean success = apiClient.recordXpGain(token, xpData);

            if (success)
            {
                log.debug("XP gain recorded successfully for {} in {}", username, skill.getName());
            }
            else
            {
                log.error("Failed to record XP gain for {} in {}", username, skill.getName());
            }
        }
        catch (Exception e)
        {
            log.error("Error recording XP gain for {} in {}: {}",
                    username, skill.getName(), e.getMessage(), e);
        }
    }

    private JsonObject buildXpData(
            String username,
            Skill skill,
            int xpGained,
            int totalXp,
            int currentLevel
    )
    {
        JsonObject data = new JsonObject();
        data.addProperty("username", username);
        data.addProperty("skill", skill.getName().toLowerCase());
        data.addProperty("xp_gained", xpGained);
        data.addProperty("total_xp", totalXp);
        data.addProperty("current_level", currentLevel);
        data.addProperty("timestamp", Instant.now().getEpochSecond());

        log.debug("Built XP data for {} in {} (+{} XP)", username, skill.getName(), xpGained);

        return data;
    }
}
