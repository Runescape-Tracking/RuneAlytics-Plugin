package com.runealytics;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import net.runelite.client.util.Filepath;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Disk coverage for loot persistence: files are written inside the plugin data
 * directory, and loot files from older releases are migrated out of the root
 * of {@code .runelite}.
 */
public class LootStorageManagerFileTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File legacyDir;
    private File pluginDir;
    private Filepath pluginPath;

    @Before
    public void setUp() throws Exception
    {
        legacyDir = tmp.newFolder("dot-runelite");
        pluginDir = new File(tmp.getRoot(), "plugin-data");
        pluginDir.mkdirs();
        pluginPath = Filepath.Unchecked.getRooted(pluginDir.toPath()).joinSegment("runealytics").rooted();
    }

    private LootStorageManager managerFor(String username)
    {
        RuneAlyticsState state = mock(RuneAlyticsState.class);
        when(state.getVerifiedUsername()).thenReturn(username);
        return new LootStorageManager(state, new Gson());
    }

    private static void write(File file, String content) throws Exception
    {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception
    {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void saveAndLoad_roundTripInsidePluginDirectory()
    {
        LootStorageManager manager = managerFor("Some Player");
        manager.setDataDirectory(pluginPath);
        manager.loadData();
        manager.addKill("Zulrah", 2042, 100, 1, 330, 0, Collections.emptyList());
        manager.saveData();

        File saved = new File(pluginDir, "runealytics/loot-some_player.json");
        assertTrue(saved.isFile());
        assertFalse(new File(pluginDir, "runealytics/loot-some_player.json.tmp").exists());

        LootStorageManager reloaded = managerFor("Some Player");
        reloaded.setDataDirectory(pluginPath);
        LootStorageData data = reloaded.loadData();
        assertNotNull(data.getBossKills().get("Zulrah"));
        assertEquals(1, data.getBossKills().get("Zulrah").getKillCount());
    }

    @Test
    public void noDataDirectory_skipsDiskAndKeepsDataInMemory()
    {
        LootStorageManager manager = managerFor("player");
        assertNull(manager.getStorageFile("player"));

        manager.loadData();
        manager.addKill("Zulrah", 2042, 100, 1, 330, 0, Collections.emptyList());
        manager.saveData();

        assertEquals(1, manager.getCurrentData().getBossKills().get("Zulrah").getKillCount());
    }

    @Test
    public void migrate_movesLegacyFilesIntoPluginDirectory() throws Exception
    {
        File legacy = new File(legacyDir, "runealytics-loot-player_one.json");
        write(legacy, "{\"username\":\"player one\"}");

        LootStorageManager.migrateLegacyFiles(legacyDir, pluginPath);

        File migrated = new File(pluginDir, "runealytics/loot-player_one.json");
        assertTrue(migrated.isFile());
        assertEquals("{\"username\":\"player one\"}", read(migrated));
        assertFalse(legacy.exists());
        assertFalse(new File(pluginDir, "runealytics/loot-player_one.json.tmp").exists());
    }

    @Test
    public void migrate_neverOverwritesExistingFile() throws Exception
    {
        File legacy = new File(legacyDir, "runealytics-loot-player.json");
        write(legacy, "old");
        File existing = new File(pluginDir, "runealytics/loot-player.json");
        existing.getParentFile().mkdirs();
        write(existing, "new");

        LootStorageManager.migrateLegacyFiles(legacyDir, pluginPath);

        assertEquals("new", read(existing));
        assertTrue(legacy.exists());
    }

    @Test
    public void migrate_removesLegacyTempFilesAndIgnoresUnrelatedFiles() throws Exception
    {
        File legacyTmp = new File(legacyDir, "runealytics-loot-player.json.tmp");
        write(legacyTmp, "partial");
        File unrelated = new File(legacyDir, "settings.properties");
        write(unrelated, "keep");

        LootStorageManager.migrateLegacyFiles(legacyDir, pluginPath);

        assertFalse(legacyTmp.exists());
        assertTrue(unrelated.exists());
        assertFalse(new File(pluginDir, "runealytics").exists());
    }

    @Test
    public void migrate_toleratesMissingLegacyDirectory()
    {
        LootStorageManager.migrateLegacyFiles(new File(tmp.getRoot(), "missing"), pluginPath);
        LootStorageManager.migrateLegacyFiles(null, pluginPath);
        assertFalse(new File(pluginDir, "runealytics").exists());
    }

    @Test
    public void setDataDirectory_doesNotMigrateLegacyFiles() throws Exception
    {
        File legacy = new File(legacyDir, "runealytics-loot-player.json");
        write(legacy, "{\"username\":\"player\",\"revision\":7}");

        LootStorageManager manager = managerFor("player");
        manager.setDataDirectory(pluginPath);

        assertTrue(legacy.isFile());
        assertFalse(new File(pluginDir, "runealytics/loot-player.json").exists());
    }

    @Test
    public void migrateLegacyLootFiles_movesIntoPluginDirectory() throws Exception
    {
        File legacy = new File(legacyDir, "runealytics-loot-player.json");
        write(legacy, "{\"username\":\"player\",\"revision\":3}");

        LootStorageManager manager = managerFor("player");
        manager.setDataDirectory(pluginPath);
        manager.migrateLegacyLootFiles(legacyDir);

        assertFalse(legacy.exists());
        assertEquals(3L, manager.loadData().getRevision());
    }

    @Test
    public void save_afterLoadingLegacy_movesFileAndKeepsRevision() throws Exception
    {
        LootStorageManager manager = managerFor("player");
        manager.setDataDirectory(pluginPath);
        manager.migrateLegacyLootFiles(legacyDir);

        File legacy = new File(legacyDir, "runealytics-loot-player.json");
        write(legacy, "{\"username\":\"player\",\"revision\":9}");

        assertEquals(9L, manager.loadData().getRevision());
        manager.saveData();

        assertFalse(legacy.exists());
        File saved = new File(pluginDir, "runealytics/loot-player.json");
        assertTrue(saved.isFile());

        LootStorageManager reloaded = managerFor("player");
        reloaded.setDataDirectory(pluginPath);
        assertEquals(9L, reloaded.loadData().getRevision());
    }

    @Test
    public void save_doesNotCreateNewFileWhileLegacyMoveFails() throws Exception
    {
        File legacy = new File(legacyDir, "runealytics-loot-player.json");
        write(legacy, "{\"username\":\"player\",\"revision\":42}");
        Assume.assumeTrue("legacy file must be unreadable to force the move to fail",
                legacy.setReadable(false));

        try
        {
            LootStorageManager manager = managerFor("player");
            manager.setDataDirectory(pluginPath);
            manager.migrateLegacyLootFiles(legacyDir);
            manager.loadData();
            manager.saveData();

            assertFalse(new File(pluginDir, "runealytics/loot-player.json").exists());
            assertTrue(legacy.isFile());
        }
        finally
        {
            legacy.setReadable(true);
        }
    }
}
