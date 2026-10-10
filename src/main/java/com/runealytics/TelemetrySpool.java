package com.runealytics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded on-disk retry spool for telemetry batches, one directory per
 * account. Each batch is a JSON file named {@code <sequence>-<batchId>.json},
 * written atomically (temp file + move) so a crash never leaves a half batch.
 * Oldest files are evicted when the count or byte budget is exceeded, and
 * files older than the server's backfill window are dropped.
 *
 * <p>Only called from the background executor — never the client thread.</p>
 */
final class TelemetrySpool
{
    static final int MAX_FILES = 500;
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final long MAX_AGE_MS = 14L * 24 * 3600 * 1000;

    private final Path dir;

    TelemetrySpool(Path dir)
    {
        this.dir = dir;
    }

    synchronized long nextSequence() throws IOException
    {
        Files.createDirectories(dir);
        Path seq = dir.resolve("sequence");
        long next = 1;
        if (Files.exists(seq))
        {
            try
            {
                next = Long.parseLong(new String(Files.readAllBytes(seq), StandardCharsets.UTF_8).trim()) + 1;
            }
            catch (NumberFormatException e)
            {
                next = System.currentTimeMillis(); // corrupt counter: stay monotonic
            }
        }
        writeAtomic(seq, Long.toString(next));
        return next;
    }

    synchronized void put(long sequence, String batchId, String json) throws IOException
    {
        Files.createDirectories(dir);
        writeAtomic(dir.resolve(String.format("%020d-%s.json", sequence, batchId)), json);
        enforceBounds(System.currentTimeMillis());
    }

    /** Oldest-first pending batch files. */
    synchronized List<Path> pending() throws IOException
    {
        if (!Files.isDirectory(dir))
        {
            return Collections.emptyList();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json"))
        {
            for (Path p : ds)
            {
                files.add(p);
            }
        }
        Collections.sort(files);
        return files;
    }

    String read(Path file) throws IOException
    {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    synchronized void remove(Path file)
    {
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException ignored)
        {
            // retried on next pump
        }
    }

    synchronized void enforceBounds(long nowMs) throws IOException
    {
        List<Path> files = pending();
        long total = 0;
        for (Path p : files)
        {
            total += Files.size(p);
        }
        int i = 0;
        while (i < files.size() && (files.size() - i > MAX_FILES || total > MAX_BYTES
                || nowMs - Files.getLastModifiedTime(files.get(i)).toMillis() > MAX_AGE_MS))
        {
            total -= Files.size(files.get(i));
            Files.deleteIfExists(files.get(i));
            i++;
        }
    }

    private void writeAtomic(Path target, String content) throws IOException
    {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try
        {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
