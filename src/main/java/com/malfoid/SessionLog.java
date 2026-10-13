package com.malfoid;

import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.runelite.client.util.Filepath;

/** Small scoped rolling log. No filesystem operations occur in the tick pipeline. */
final class SessionLog
{
    private static final long MAX_BYTES = 256 * 1024;
    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(256);
    private final Filepath file;
    private final Filepath previous;
    private final Consumer<Throwable> onFailure;
    private final ScheduledFuture<?> pump;
    private volatile boolean broken;

    SessionLog(Filepath directory, ScheduledExecutorService executor, Consumer<Throwable> onFailure)
        throws IOException
    {
        directory.createDirectories();
        this.file = directory.joinSegment("malfoid.log");
        this.previous = directory.joinSegment("malfoid.previous.log");
        this.onFailure = onFailure;
        pump = executor.scheduleWithFixedDelay(this::flush, 1, 2, TimeUnit.SECONDS);
    }

    void record(String text)
    {
        String safe = text.replace('\r', ' ').replace('\n', ' ');
        if (safe.length() > 700)
        {
            safe = safe.substring(0, 700);
        }
        String line = Instant.now() + " " + safe + "\n";
        if (!pending.offer(line))
        {
            pending.poll();
            pending.offer(line);
        }
    }

    synchronized void flush()
    {
        if (broken || pending.isEmpty())
        {
            return;
        }
        StringBuilder batch = new StringBuilder();
        String line;
        while ((line = pending.poll()) != null)
        {
            batch.append(line);
        }
        try
        {
            if (file.exists() && file.size() >= MAX_BYTES)
            {
                file.moveTo(previous, StandardCopyOption.REPLACE_EXISTING);
            }
            file.write(batch.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Throwable failure)
        {
            broken = true;
            onFailure.accept(failure);
        }
    }

    void close(ScheduledExecutorService executor)
    {
        pump.cancel(false);
        executor.execute(this::flush);
    }
}
