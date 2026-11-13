package com.runealytics.progress;

import net.runelite.client.util.Filepath;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;

/**
 * Stores progress files under the plugin data directory, in a {@code progress}
 * sub-folder, using the same temp-file + atomic-rename pattern as
 * {@code LootStorageManager#saveData}.
 */
public final class FilepathProgressStore implements ProgressStore
{
    private final Filepath directory;

    public FilepathProgressStore(Filepath pluginDirectory)
    {
        this.directory = pluginDirectory.joinSegment("progress");
    }

    @Override
    public String read(String fileName) throws IOException
    {
        Filepath file = directory.joinSegment(fileName);
        if (!file.exists()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = file.openBufferedReader())
        {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    @Override
    public void write(String fileName, String json) throws IOException
    {
        directory.createDirectories();
        Filepath file = directory.joinSegment(fileName);
        Filepath tmp = directory.joinSegment(fileName + ".tmp");
        try (Writer writer = tmp.openBufferedWriter())
        {
            writer.write(json);
        }
        try
        {
            tmp.moveTo(file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
