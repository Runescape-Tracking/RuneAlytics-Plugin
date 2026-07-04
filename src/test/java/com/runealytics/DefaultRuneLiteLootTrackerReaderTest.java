package com.runealytics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Coverage for RuneLite Loot Tracker import scoping and parsing, using the
 * package-private file-list overloads so no dependency on RuneLite.RUNELITE_DIR
 * is required. Fixtures are real {@code .properties} files written via
 * {@link Properties#store} so escaping matches production round-trips.
 */
public class DefaultRuneLiteLootTrackerReaderTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final DefaultRuneLiteLootTrackerReader reader = new DefaultRuneLiteLootTrackerReader();

    private File propsFile(String name, Map<String, String> entries) throws Exception
    {
        File file = tmp.newFile(name);
        Properties props = new Properties();
        for (Map.Entry<String, String> e : entries.entrySet())
        {
            props.setProperty(e.getKey(), e.getValue());
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))
        {
            props.store(w, null);
        }
        return file;
    }

    private static Map<String, String> map(String... kv)
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String loot(String type, String name, int kills, String flatDrops)
    {
        return "{\"type\":\"" + type + "\",\"name\":\"" + name + "\",\"kills\":" + kills
                + ",\"drops\":[" + flatDrops + "]}";
    }

    // ── account scoping ──────────────────────────────────────────────────────

    @Test
    public void readForAccount_scopesToMatchingAccountOnly() throws Exception
    {
        File file = propsFile("profiles.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zezima",
                "rsprofile.rsprofile.KEY2.displayName", "Durial321",
                "loottracker.rsprofile.KEY1.drops_NPC_Goblin", loot("NPC", "Goblin", 3, "526,1,995,5"),
                "loottracker.rsprofile.KEY2.drops_NPC_Dragon", loot("NPC", "Dragon", 9, "536,2")));

        Map<String, DefaultRuneLiteLootTrackerReader.SourceTotals> result =
                reader.readForAccount("zezima", Collections.singletonList(file));

        assertTrue(result.containsKey("Goblin"));
        assertFalse(result.containsKey("Dragon"));
        assertEquals(3, result.get("Goblin").killCount);
        assertEquals(Long.valueOf(1), result.get("Goblin").items.get(526));
        assertEquals(Long.valueOf(5), result.get("Goblin").items.get(995));
    }

    @Test
    public void readForAccount_nullOrEmptyKey_returnsEmpty() throws Exception
    {
        File file = propsFile("p.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zezima"));
        assertTrue(reader.readForAccount(null, Collections.singletonList(file)).isEmpty());
        assertTrue(reader.readForAccount("", Collections.singletonList(file)).isEmpty());
    }

    @Test
    public void readForAccount_nullOrEmptyFiles_returnsEmpty()
    {
        assertTrue(reader.readForAccount("zezima", null).isEmpty());
        assertTrue(reader.readForAccount("zezima", new ArrayList<>()).isEmpty());
    }

    @Test
    public void readForAccount_noMatchingProfileKey_returnsEmpty() throws Exception
    {
        File file = propsFile("p.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zezima",
                "loottracker.rsprofile.KEY1.drops_NPC_Goblin", loot("NPC", "Goblin", 1, "526,1")));
        assertTrue(reader.readForAccount("ghost", Collections.singletonList(file)).isEmpty());
    }

    // ── merge / max semantics ─────────────────────────────────────────────────

    @Test
    public void readForAccount_takesMaxAcrossFilesForSameSource() throws Exception
    {
        File file1 = propsFile("a.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zezima",
                "loottracker.rsprofile.KEY1.drops_NPC_Goblin", loot("NPC", "Goblin", 3, "995,5")));
        File file2 = propsFile("b.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zezima",
                "loottracker.rsprofile.KEY1.drops_NPC_Goblin", loot("NPC", "Goblin", 5, "995,2")));

        List<File> files = new ArrayList<>();
        files.add(file1);
        files.add(file2);

        DefaultRuneLiteLootTrackerReader.SourceTotals totals =
                reader.readForAccount("zezima", files).get("Goblin");
        assertEquals(5, totals.killCount);       // max(3,5)
        assertEquals(Long.valueOf(5), totals.items.get(995)); // max(5,2)
    }

    // ── parsing edge cases ────────────────────────────────────────────────────

    @Test
    public void readForAccount_filtersNonPositiveIdsAndQuantities() throws Exception
    {
        File file = propsFile("p.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zezima",
                "loottracker.rsprofile.KEY1.drops_NPC_Goblin",
                loot("NPC", "Goblin", 2, "526,1,0,10,995,-5,-3,7")));

        DefaultRuneLiteLootTrackerReader.SourceTotals totals =
                reader.readForAccount("zezima", Collections.singletonList(file)).get("Goblin");
        assertEquals(1, totals.items.size());
        assertEquals(Long.valueOf(1), totals.items.get(526));
    }

    @Test
    public void readForAccount_ignoresTrailingUnpairedDropId() throws Exception
    {
        File file = propsFile("p.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zezima",
                "loottracker.rsprofile.KEY1.drops_NPC_Goblin", loot("NPC", "Goblin", 1, "526,1,995")));

        DefaultRuneLiteLootTrackerReader.SourceTotals totals =
                reader.readForAccount("zezima", Collections.singletonList(file)).get("Goblin");
        assertEquals(1, totals.items.size());
        assertEquals(Long.valueOf(1), totals.items.get(526));
    }

    @Test
    public void readForAccount_keepsKillOnlySourceButDropsPhantom() throws Exception
    {
        File file = propsFile("p.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zezima",
                // kills>0 but no items → kept
                "loottracker.rsprofile.KEY1.drops_NPC_Fighter", loot("NPC", "Fighter", 4, ""),
                // kills 0 and no usable items → phantom, dropped
                "loottracker.rsprofile.KEY1.drops_NPC_Empty", loot("NPC", "Empty", 0, "")));

        Map<String, DefaultRuneLiteLootTrackerReader.SourceTotals> result =
                reader.readForAccount("zezima", Collections.singletonList(file));
        assertTrue(result.containsKey("Fighter"));
        assertEquals(4, result.get("Fighter").killCount);
        assertFalse(result.containsKey("Empty"));
    }

    @Test
    public void readForAccount_malformedJsonEntryIsSkipped() throws Exception
    {
        File file = propsFile("p.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zezima",
                "loottracker.rsprofile.KEY1.drops_NPC_Broken", "not-json",
                "loottracker.rsprofile.KEY1.drops_NPC_Goblin", loot("NPC", "Goblin", 1, "526,1")));

        Map<String, DefaultRuneLiteLootTrackerReader.SourceTotals> result =
                reader.readForAccount("zezima", Collections.singletonList(file));
        assertFalse(result.containsKey("Broken"));
        assertTrue(result.containsKey("Goblin"));
    }

    @Test
    public void readForAccount_normalizesNbspDisplayName() throws Exception
    {
        File file = propsFile("p.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zez\u00A0Ima",
                "loottracker.rsprofile.KEY1.drops_NPC_Goblin", loot("NPC", "Goblin", 1, "526,1")));

        assertTrue(reader.readForAccount("zez ima", Collections.singletonList(file)).containsKey("Goblin"));
    }

    // ── canImportHistorical ────────────────────────────────────────────────────

    @Test
    public void canImportHistorical_trueWhenDisplayNamePresent() throws Exception
    {
        File file = propsFile("p.properties", map(
                "rsprofile.rsprofile.KEY1.displayName", "Zezima"));
        assertTrue(reader.canImportHistorical(Collections.singletonList(file)));
    }

    @Test
    public void canImportHistorical_falseWhenNoDisplayName() throws Exception
    {
        File file = propsFile("p.properties", map(
                "loottracker.rsprofile.KEY1.drops_NPC_Goblin", loot("NPC", "Goblin", 1, "526,1")));
        assertFalse(reader.canImportHistorical(Collections.singletonList(file)));
    }

    @Test
    public void canImportHistorical_ignoresNestedDisplayNameKeys() throws Exception
    {
        // rsKey "KEY.sub" contains a dot → guarded out, so no importable profile.
        File file = propsFile("p.properties", map(
                "rsprofile.rsprofile.KEY.sub.displayName", "Zezima"));
        assertFalse(reader.canImportHistorical(Collections.singletonList(file)));
    }
}
