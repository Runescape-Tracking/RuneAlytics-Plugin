package com.runealytics.progress;

import java.io.IOException;

/**
 * Per-account local storage for {@link ProgressOutbox} files. Implementations are
 * called only from background threads.
 */
public interface ProgressStore
{
    /** @return the stored JSON, or {@code null} when there is none */
    String read(String fileName) throws IOException;

    /** Atomically replaces the stored JSON. */
    void write(String fileName, String json) throws IOException;
}
