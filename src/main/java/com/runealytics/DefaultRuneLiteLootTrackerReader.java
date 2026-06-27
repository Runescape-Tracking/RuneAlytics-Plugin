package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads RuneLite's default Loot Tracker data and returns per-source,
 * per-item absolute quantity totals for the currently logged-in account.
 *
 * <h2>Critical account-scoping limitation</h2>
 * <p>RuneLite's {@code profiles2/*.properties} files do not contain a field
 * that reliably maps a profile ID to a RuneScape display name.  We attempt
 * to read a {@code profile.name} or {@code runelite.profile.name} property
 * from each file; when found, we filter to the profile whose name matches
 * the current account.  When no profile-name property can be found, we
 * {@code SKIP} that file entirely rather than importing unknown data.</p>
 *
 * <p>If no profile with a matching name is found, {@link #canImportHistorical()}
 * returns {@code false} and the caller should show the UI warning defined in
 * Part 9 of the specification.  Forward-sync via live {@code LootReceived}
 * events is always safe and is unaffected by this limitation.</p>
 *
 * <h2>How to use</h2>
 * <pre>
 * DefaultRuneLiteLootTrackerReader reader = ...;
 * if (reader.canImportHistorical()) {
 *     Map&lt;String, Map&lt;Integer, Integer&gt;&gt; loot = reader.readForAccount(username);
 *     // merge loot into plugin state
 * } else {
 *     // show UI warning, use live LootReceived events only
 * }
 * </pre>
 *
 * <h2>Data format</h2>
 * <p>Returns a {@code Map<sourceName, Map<itemId, totalQuantity>>} where
 * {@code totalQuantity} is the <em>absolute</em> total that RuneLite's tracker
 * has recorded.  These values must be treated as absolute totals, never as
 * incremental new drops.</p>
 */
@Slf4j
@Singleton
public class DefaultRuneLiteLootTrackerReader
{
    /**
     * Property keys we search in each .properties profile file to identify
     * the account associated with that profile.
     *
     * RuneLite does not commit to a stable key for this, so we try several
     * candidates in order.  If none matches, the profile is skipped.
     */
    private static final String[] PROFILE_NAME_KEYS = {
        "profile.name",
        "runelite.profile.name",
        "rsprofile.name",
        "runelite.rsprofile.displayName",
    };

    /** Prefix for RuneLite Loot Tracker entries in profile .properties files. */
    private static final String LOOT_KEY_PREFIX = "loottracker.rsprofile.";
    private static final String DROPS_INFIX     = ".drops_";

    @Inject
    public DefaultRuneLiteLootTrackerReader() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if at least one {@code profiles2/*.properties} file
     * with an identifiable profile name exists.  When {@code false}, callers
     * must NOT import historical RuneLite Loot Tracker data and should show the
     * UI warning described in Part 9 of the specification.
     */
    public boolean canImportHistorical()
    {
        File profiles2 = new File(RuneLite.RUNELITE_DIR, "profiles2");
        if (!profiles2.isDirectory()) return false;

        File[] files = profiles2.listFiles(f -> f.isFile() && f.getName().endsWith(".properties"));
        if (files == null || files.length == 0) return false;

        for (File f : files)
        {
            if (readProfileName(f) != null) return true;
        }

        return false;
    }

    /**
     * Reads RuneLite Loot Tracker absolute totals for the given account.
     *
     * <p>Only profiles whose identified name matches
     * {@link CurrentPlayerIdentityService#normalizeUsername(String) normalizeUsername(accountKey)}
     * are imported.  Profiles without a readable name are silently skipped
     * (the caller should check {@link #canImportHistorical()} first).</p>
     *
     * @param  accountKey  normalized RuneScape username, e.g. {@code "zezima"}
     * @return map of {sourceName → {itemId → absoluteQuantity}}.
     *         Returns an empty map — never null — when no matching data exists.
     */
    public Map<String, Map<Integer, Long>> readForAccount(String accountKey)
    {
        if (accountKey == null || accountKey.isEmpty())
        {
            log.warn("[rl-reader] readForAccount called with null/empty accountKey — skipping");
            return Collections.emptyMap();
        }

        File profiles2 = new File(RuneLite.RUNELITE_DIR, "profiles2");
        if (!profiles2.isDirectory())
        {
            log.debug("[rl-reader] No profiles2 directory found — no RuneLite tracker data to read");
            return Collections.emptyMap();
        }

        File[] files = profiles2.listFiles(f -> f.isFile() && f.getName().endsWith(".properties"));
        if (files == null || files.length == 0)
        {
            log.debug("[rl-reader] profiles2 directory is empty");
            return Collections.emptyMap();
        }

        // Sort by last-modified descending so the most-recently-used profile
        // wins when multiple profiles have the same name (shouldn't happen
        // normally but is a safe tie-break).
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        Map<String, Map<Integer, Long>> result = new HashMap<>();
        int skippedProfiles  = 0;
        int matchedProfiles  = 0;

        for (File propFile : files)
        {
            String profileName = readProfileName(propFile);
            if (profileName == null)
            {
                // Cannot determine which account owns this profile — skip it.
                // This is the intended safe-default behaviour per the spec.
                skippedProfiles++;
                log.debug("[rl-reader] Skipping profile '{}' — no profile name property found",
                        propFile.getName());
                continue;
            }

            String normalizedProfileName = CurrentPlayerIdentityService.normalizeUsername(profileName);
            if (!accountKey.equals(normalizedProfileName))
            {
                log.debug("[rl-reader] Skipping profile '{}' (account='{}') — not current account '{}'",
                        propFile.getName(), normalizedProfileName, accountKey);
                continue;
            }

            matchedProfiles++;
            log.debug("[rl-reader] Reading profile '{}' for account '{}'",
                    propFile.getName(), accountKey);

            readLootFromProfile(propFile, result);
        }

        log.debug("[rl-reader] Read complete: {} matching profiles, {} skipped (no name), {} sources found",
                matchedProfiles, skippedProfiles, result.size());

        if (matchedProfiles == 0 && skippedProfiles > 0)
        {
            log.warn("[rl-reader] Found {} profile(s) but none had a readable name. "
                    + "Historical RuneLite Loot Tracker data will NOT be imported. "
                    + "Forward-sync via live LootReceived events is still active.",
                    skippedProfiles);
        }

        return result;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Attempts to read a profile display name from the given .properties file.
     *
     * @return the raw profile name, or {@code null} if none of the candidate
     *         property keys are found in this file.
     */
    private String readProfileName(File propFile)
    {
        Properties props = loadProperties(propFile);
        if (props == null) return null;

        for (String key : PROFILE_NAME_KEYS)
        {
            String val = props.getProperty(key);
            if (val != null && !val.isBlank()) return val.trim();
        }

        return null;
    }

    /**
     * Parses all {@code loottracker.rsprofile.*.drops_*} entries from a
     * .properties file and merges the absolute totals into {@code dest}.
     *
     * <p>The value format is a JSON object with:</p>
     * <pre>
     * {
     *   "name":  "Vorkath",
     *   "type":  "NPC",
     *   "kills": 42,
     *   "drops": [itemId, quantity, itemId, quantity, ...]
     * }
     * </pre>
     */
    private void readLootFromProfile(File propFile,
            Map<String, Map<Integer, Long>> dest)
    {
        Properties props = loadProperties(propFile);
        if (props == null) return;

        int entriesParsed = 0;
        int entriesSkipped = 0;

        for (String key : props.stringPropertyNames())
        {
            if (!key.startsWith(LOOT_KEY_PREFIX)) continue;
            if (!key.contains(DROPS_INFIX))       continue;

            String val = props.getProperty(key);
            if (val == null || val.isBlank()) continue;

            try
            {
                JsonObject obj = new JsonParser().parse(val).getAsJsonObject();

                String name = obj.has("name") ? obj.get("name").getAsString() : "Unknown";
                if (!obj.has("drops") || !obj.get("drops").isJsonArray())
                {
                    entriesSkipped++;
                    continue;
                }

                JsonArray flatDrops = obj.getAsJsonArray("drops");
                Map<Integer, Long> sourceMap = dest.computeIfAbsent(name, k -> new HashMap<>());

                for (int i = 0; i + 1 < flatDrops.size(); i += 2)
                {
                    int  itemId = flatDrops.get(i).getAsInt();
                    long qty    = flatDrops.get(i + 1).getAsLong();
                    if (itemId <= 0 || qty <= 0) continue;

                    // RuneLite loot tracker stores ABSOLUTE totals.
                    // Use max so multiple profiles for the same account don't
                    // accidentally under-count (in case data is split across
                    // profile files after a client migration).
                    sourceMap.merge(itemId, qty, Math::max);
                }

                entriesParsed++;
            }
            catch (Exception e)
            {
                entriesSkipped++;
                log.debug("[rl-reader] Failed to parse entry {}: {}", key, e.getMessage());
            }
        }

        log.debug("[rl-reader] {} — parsed {} entries, skipped {}", propFile.getName(),
                entriesParsed, entriesSkipped);
    }

    private Properties loadProperties(File propFile)
    {
        Properties props = new Properties();
        try (InputStreamReader isr = new InputStreamReader(
                new FileInputStream(propFile), StandardCharsets.UTF_8))
        {
            props.load(isr);
            return props;
        }
        catch (Exception e)
        {
            log.debug("[rl-reader] Failed to load properties from {}: {}", propFile.getName(), e.getMessage());
            return null;
        }
    }
}
