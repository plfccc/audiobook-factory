package com.audiobookfactory.control.audio;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaToolRunnerTest {

    @Test
    void capsCapturedOutputWithoutUsingACommandShell() throws Exception {
        MediaToolRunner.CommandResult result = runJava(OutputMain.class, Duration.ofSeconds(5));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).hasSizeLessThanOrEqualTo(1024 * 1024);
    }

    @Test
    void terminatesACommandThatExceedsTheTimeout() throws Exception {
        MediaToolRunner.CommandResult result = runJava(SleepMain.class, Duration.ofMillis(100));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
    }

    private MediaToolRunner.CommandResult runJava(Class<?> mainClass, Duration timeout) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new MediaToolRunner().run(List.of(
                java, "-cp", System.getProperty("java.class.path"), mainClass.getName()), timeout);
    }

    public static final class OutputMain {
        public static void main(String[] args) {
            System.out.print("x".repeat(2 * 1024 * 1024));
        }
    }

    public static final class SleepMain {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(5_000);
        }
    }
}
