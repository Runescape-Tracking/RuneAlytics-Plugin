package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages local storage of loot tracking data
 */
@Singleton
public class LootStorageManager
{
    private static final Logger log = LoggerFactory.getLogger(LootStorageManager.class);
    private static final String STORAGE_FILE = "runealytics-loot.json";

    private final Gson gson;
    private final File storageFile;

    @Inject
    public LootStorageManager()
    {
        this.gson = new GsonBuilder().setPrettyPrinting().create();

        // Store in RuneLite config directory
        File runeliteDir = new File(System.getProperty("user.home"), ".runelite");
        this.storageFile = new File(runeliteDir, STORAGE_FILE);
    }

    /**
     * Save loot data to local storage
     */
    public void saveLootData(Map<String, BossKillStats> bossStats, int currentPrestige)
    {
        try
        {
            LootStorageData data = new LootStorageData();
            data.bossStats = bossStats;
            data.currentPrestige = currentPrestige;
            data.lastSaved = System.currentTimeMillis();

            try (FileWriter writer = new FileWriter(storageFile))
            {
                gson.toJson(data, writer);
            }

            log.info("Saved loot data to local storage (Prestige: {})", currentPrestige);
        }
        catch (Exception e)
        {
            log.error("Failed to save loot data", e);
        }
    }

    /**
     * Load loot data from local storage
     */
    public LootStorageData loadLootData()
    {
        if (!storageFile.exists())
        {
            log.info("No local loot data found");
            return null;
        }

        try (FileReader reader = new FileReader(storageFile))
        {
            LootStorageData data = gson.fromJson(reader, LootStorageData.class);
            log.info("Loaded loot data from local storage (Prestige: {})", data.currentPrestige);
            return data;
        }
        catch (Exception e)
        {
            log.error("Failed to load loot data", e);
            return null;
        }
    }

    /**
     * Delete local storage file
     */
    public void clearStorage()
    {
        if (storageFile.exists())
        {
            storageFile.delete();
            log.info("Cleared local loot storage");
        }
    }

    /**
     * Container for stored loot data
     */
    public static class LootStorageData
    {
        public Map<String, BossKillStats> bossStats;
        public int currentPrestige;
        public long lastSaved;
    }
}