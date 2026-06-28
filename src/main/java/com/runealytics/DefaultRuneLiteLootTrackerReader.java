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
 * <h2>How RuneLite stores loot (and why account scoping matters)</h2>
 * <p>RuneLite keeps all per-account ("RS profile") data inside the shared
 * {@code profiles2/*.properties} files using keys of the form:</p>
 * <pre>
 *   loottracker.rsprofile.&lt;RSPROFILE_KEY&gt;.drops_&lt;TYPE&gt;_&lt;NAME&gt; = { ...json... }
 * </pre>
 * <p>where {@code <RSPROFILE_KEY>} (e.g. {@code "Ro2U8NV6"}) identifies one RS
 * account. A single file therefore contains the loot of <em>every</em> account
 * that has ever logged in on this installation. The mapping from an
 * {@code <RSPROFILE_KEY>} to its RuneScape display name is stored alongside as:</p>
 * <pre>
 *   rsprofile.rsprofile.&lt;RSPROFILE_KEY&gt;.displayName = Zezima
 * </pre>
 *
 * <p>To avoid importing other accounts' loot, this reader:</p>
 * <ol>
 *   <li>Builds a {@code <RSPROFILE_KEY> → normalizedDisplayName} map from every
 *       {@code rsprofile.rsprofile.*.displayName} property it can find.</li>
 *   <li>Resolves the {@code <RSPROFILE_KEY>}(s) whose display name matches the
 *       currently logged-in account.</li>
 *   <li>Reads <b>only</b> the {@code loottracker.rsprofile.<that key>.drops_*}
 *       entries — every other account's loot is ignored.</li>
 * </ol>
 *
 * <p>When the current account has no identifiable RS-profile key (e.g. RuneLite
 * never recorded a display name for it), nothing is imported and the caller
 * should show the "history could not be matched" warning. Forward-sync via live
 * {@code LootReceived} events is always safe and unaffected by this limitation.</p>
 *
 * <h2>Data format</h2>
 * <p>Returns a {@code Map<sourceName, Map<itemId, totalQuantity>>} where
 * {@code totalQuantity} is the <em>absolute</em> total RuneLite has recorded.
 * These values must be treated as absolute totals, never as incremental drops.</p>
 */
@Slf4j
@Singleton
public class DefaultRuneLiteLootTrackerReader
{
    /** Loot entries: {@code loottracker.rsprofile.<KEY>.drops_<TYPE>_<NAME>}. */
    private static final String LOOT_KEY_PREFIX = "loottracker.rsprofile.";
    private static final String DROPS_INFIX     = ".drops_";

    /** Account-name mapping: {@code rsprofile.rsprofile.<KEY>.displayName}. */
    private static final String RSPROFILE_KEY_PREFIX = "rsprofile.rsprofile.";
    private static final String DISPLAY_NAME_SUFFIX  = ".displayName";

    @Inject
    public DefaultRuneLiteLootTrackerReader() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when at least one RS-profile display name can be read
     * from the {@code profiles2} files — i.e. RuneLite has tagged accounts and
     * we are able to scope loot per account. When {@code false}, callers must
     * NOT import historical RuneLite loot and should rely on live events.
     */
    public boolean canImportHistorical()
    {
        return canImportHistorical(listProfileFiles());
    }

    /** Testable core of {@link #canImportHistorical()} over an explicit file list. */
    boolean canImportHistorical(List<File> files)
    {
        for (File f : files)
        {
            if (!readProfileKeyToAccount(loadProperties(f)).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Per-source totals read from RuneLite's own Loot Tracker file: the
     * cumulative kill count RuneLite has recorded for that source, alongside
     * the absolute per-item quantities.
     */
    public static class SourceTotals
    {
        public int killCount;
        public final Map<Integer, Long> items = new HashMap<>();
    }

    /**
     * Reads RuneLite Loot Tracker absolute totals for {@code accountKey}.
     *
     * <p>Only loot stored under the RS-profile key(s) whose display name matches
     * {@code accountKey} is returned. All other accounts' loot is ignored.</p>
     *
     * @param  accountKey  normalized RuneScape username, e.g. {@code "zezima"}
     * @return map of {sourceName → totals}; empty (never null) when no matching data exists
     */
    public Map<String, SourceTotals> readForAccount(String accountKey)
    {
        return readForAccount(accountKey, listProfileFiles());
    }

    /**
     * Testable core of {@link #readForAccount(String)} over an explicit file
     * list, so the per-account scoping can be unit-tested without depending on
     * {@code RuneLite.RUNELITE_DIR}.
     */
    Map<String, SourceTotals> readForAccount(String accountKey, List<File> files)
    {
        if (accountKey == null || accountKey.isEmpty())
        {
            log.warn("[rl-reader] readForAccount called with null/empty accountKey — skipping");
            return Collections.emptyMap();
        }

        if (files == null || files.isEmpty())
        {
            log.warn("[rl-reader] No profiles2/*.properties files found under {} — "
                    + "no RuneLite tracker data to read for '{}'",
                    new File(RuneLite.RUNELITE_DIR, "profiles2"), accountKey);
            return Collections.emptyMap();
        }

        Map<String, SourceTotals> result = new HashMap<>();
        int filesWithLoot     = 0;
        Set<String> allSeenDisplayNames = new HashSet<>();

        for (File propFile : files)
        {
            Properties props = loadProperties(propFile);
            if (props == null) continue;

            // 1. Which RS-profile keys in this file belong to the current account?
            Map<String, String> keyToAccount = readProfileKeyToAccount(props);
            allSeenDisplayNames.addAll(keyToAccount.values());

            Set<String> matchingKeys = new HashSet<>();
            for (Map.Entry<String, String> e : keyToAccount.entrySet())
            {
                if (accountKey.equals(e.getValue())) matchingKeys.add(e.getKey());
            }

            if (matchingKeys.isEmpty())
            {
                log.debug("[rl-reader] {}: no rsprofile key maps to '{}' (keys seen here: {})",
                        propFile.getName(), accountKey, keyToAccount);
                continue;
            }

            log.debug("[rl-reader] {}: account '{}' matched rsprofile key(s) {}",
                    propFile.getName(), accountKey, matchingKeys);

            // 2. Read only loot stored under those keys.
            if (readLootForKeys(propFile.getName(), props, matchingKeys, result)) filesWithLoot++;
        }

        if (result.isEmpty())
        {
            log.warn("[rl-reader] No RuneLite loot matched account '{}' across {} file(s). "
                    + "Display names seen in profiles2: {}. "
                    + "If '{}' isn't in that list, RuneLite has never recorded a displayName for "
                    + "this exact account — check capitalization/spacing.",
                    accountKey, files.size(), allSeenDisplayNames, accountKey);
        }
        else
        {
            for (Map.Entry<String, SourceTotals> e : result.entrySet())
            {
                log.debug("[rl-reader] account '{}' source '{}': killCount={}, {} item(s) -> {}",
                        accountKey, e.getKey(), e.getValue().killCount,
                        e.getValue().items.size(), e.getValue().items);
            }
            log.debug("[rl-reader] Read {} source(s) for account '{}' from {} file(s)",
                    result.size(), accountKey, filesWithLoot);
        }

        return result;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Lists every {@code profiles2/*.properties} file, or empty when absent. */
    private List<File> listProfileFiles()
    {
        File profiles2 = new File(RuneLite.RUNELITE_DIR, "profiles2");
        if (!profiles2.isDirectory()) return Collections.emptyList();

        File[] files = profiles2.listFiles(f -> f.isFile() && f.getName().endsWith(".properties"));
        if (files == null || files.length == 0) return Collections.emptyList();

        // Most-recently-modified first, so a tie on display name prefers the
        // freshest profile data.
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return Arrays.asList(files);
    }

    /**
     * Builds {@code <RSPROFILE_KEY> → normalizedDisplayName} from all
     * {@code rsprofile.rsprofile.<KEY>.displayName} properties in this file.
     */
    private Map<String, String> readProfileKeyToAccount(Properties props)
    {
        if (props == null) return Collections.emptyMap();

        Map<String, String> map = new HashMap<>();
        for (String key : props.stringPropertyNames())
        {
            if (!key.startsWith(RSPROFILE_KEY_PREFIX) || !key.endsWith(DISPLAY_NAME_SUFFIX)) continue;

            String rsKey = key.substring(
                    RSPROFILE_KEY_PREFIX.length(),
                    key.length() - DISPLAY_NAME_SUFFIX.length());
            if (rsKey.isEmpty() || rsKey.contains(".")) continue; // guard nested keys

            String name = props.getProperty(key);
            String normalized = CurrentPlayerIdentityService.normalizeUsername(name);
            if (normalized != null) map.put(rsKey, normalized);
        }
        return map;
    }

    /**
     * Parses {@code loottracker.rsprofile.<KEY>.drops_*} entries whose
     * {@code <KEY>} is in {@code allowedKeys} and merges absolute totals into
     * {@code dest}. The loot value JSON looks like:
     * <pre>{"type":"NPC","name":"Goblin guard","kills":1,"drops":[526,1,995,5]}</pre>
     *
     * @return {@code true} if at least one entry was parsed
     */
    private boolean readLootForKeys(String fileName, Properties props, Set<String> allowedKeys,
            Map<String, SourceTotals> dest)
    {
        int entriesParsed = 0;

        for (String key : props.stringPropertyNames())
        {
            if (!key.startsWith(LOOT_KEY_PREFIX)) continue;

            int dropsIdx = key.indexOf(DROPS_INFIX, LOOT_KEY_PREFIX.length());
            if (dropsIdx < 0) continue;

            String rsKey = key.substring(LOOT_KEY_PREFIX.length(), dropsIdx);
            if (!allowedKeys.contains(rsKey)) continue; // belongs to another account

            String val = props.getProperty(key);
            if (val == null || val.isBlank()) continue;

            try
            {
                JsonObject obj = new JsonParser().parse(val).getAsJsonObject();

                String name  = obj.has("name")  ? obj.get("name").getAsString()  : "Unknown";
                int    kills = obj.has("kills") ? obj.get("kills").getAsInt()    : 0;
                if (!obj.has("drops") || !obj.get("drops").isJsonArray()) continue;

                JsonArray flatDrops = obj.getAsJsonArray("drops");
                SourceTotals totals = dest.computeIfAbsent(name, k -> new SourceTotals());

                // RuneLite stores ABSOLUTE kill counts/quantities. max() guards
                // against the same account's data being split across files or
                // across multiple drops_<TYPE>_<name> entries for one source.
                totals.killCount = Math.max(totals.killCount, kills);

                int itemsBefore = totals.items.size();
                for (int i = 0; i + 1 < flatDrops.size(); i += 2)
                {
                    int  itemId = flatDrops.get(i).getAsInt();
                    long qty    = flatDrops.get(i + 1).getAsLong();
                    if (itemId <= 0 || qty <= 0) continue;

                    totals.items.merge(itemId, qty, Math::max);
                }

                log.debug("[rl-reader] {} | key={} -> source='{}', runelite_kills={}, "
                        + "{} item id/qty pair(s) parsed (raw json: {})",
                        fileName, key, name, kills, totals.items.size() - itemsBefore, val);

                entriesParsed++;
            }
            catch (Exception e)
            {
                log.debug("[rl-reader] Failed to parse entry {}: {}", key, e.getMessage());
            }
        }

        return entriesParsed > 0;
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
