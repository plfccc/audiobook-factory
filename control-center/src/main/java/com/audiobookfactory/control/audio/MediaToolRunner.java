package com.audiobookfactory.control.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 只接受参数数组的外部媒体工具执行器，不经过 shell。 */
public class MediaToolRunner {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_CAPTURE_BYTES = 1024 * 1024;

    private final Duration defaultTimeout;

    public MediaToolRunner() {
        this(DEFAULT_TIMEOUT);
    }

    public MediaToolRunner(Duration defaultTimeout) {
        this.defaultTimeout = validateTimeout(defaultTimeout);
    }

    public CommandResult run(List<String> arguments) throws IOException {
        return run(arguments, defaultTimeout);
    }

    public CommandResult run(List<String> arguments, Duration timeout) throws IOException {
        validateArguments(arguments);
        Duration effectiveTimeout = validateTimeout(timeout);
        Process process = new ProcessBuilder(List.copyOf(arguments))
                .redirectErrorStream(false)
                .start();

        ExecutorService readers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "audiobook-media-tool-reader");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> stdout = readers.submit(() -> readBounded(process.getInputStream()));
        Future<String> stderr = readers.submit(() -> readBounded(process.getErrorStream()));
        try {
            boolean completed = process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroy();
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
                return new CommandResult(-1, result(stdout), result(stderr), true);
            }
            return new CommandResult(process.exitValue(), result(stdout), result(stderr), false);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Media tool execution was interrupted", exception);
        } finally {
            readers.shutdownNow();
        }
    }

    private String result(Future<String> output) throws IOException {
        try {
            return output.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Media tool output reading was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Unable to read media tool output", exception.getCause());
        } catch (TimeoutException exception) {
            output.cancel(true);
            throw new IOException("Media tool output reading timed out", exception);
        }
    }

    private String readBounded(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int remaining = MAX_CAPTURE_BYTES;
            while ((read = source.read(buffer)) != -1) {
                if (remaining > 0) {
                    int copied = Math.min(read, remaining);
                    output.write(buffer, 0, copied);
                    remaining -= copied;
                }
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private void validateArguments(List<String> arguments) {
        if (arguments == null || arguments.isEmpty() || arguments.size() > 256) {
            throw new IllegalArgumentException("Media tool arguments are invalid");
        }
        for (String argument : arguments) {
            if (argument == null || argument.isBlank() || argument.length() > 16_384) {
                throw new IllegalArgumentException("Media tool arguments are invalid");
            }
        }
    }

    private Duration validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.toMillis() <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }

    public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {

        public CommandResult {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }

        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }
}
