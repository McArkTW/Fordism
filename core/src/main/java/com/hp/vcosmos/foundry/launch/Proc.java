package com.hp.vcosmos.foundry.launch;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Minimal blocking process runner (docker CLI, chmod). */
public final class Proc {
    private Proc() {}

    public static ProcessResult run(List<String> cmd, int timeoutSeconds) throws IOException {
        Process process = new ProcessBuilder(cmd).start();
        // Drain both pipes on their own threads BEFORE waiting. Waiting first deadlocks the moment
        // a child fills the ~64KB pipe buffer: it blocks writing, we block waiting, and a merely
        // talkative command comes back as a timeout it never had.
        CompletableFuture<String> out = readAsync(process.getInputStream());
        CompletableFuture<String> err = readAsync(process.getErrorStream());
        try {
            boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return new ProcessResult(-1, out.getNow(""), "timeout");
            }
            return new ProcessResult(process.exitValue(), out.get(), err.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e);
        }
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = stream) {
                return new String(in.readAllBytes());
            } catch (IOException e) {
                return "";
            }
        });
    }
}
