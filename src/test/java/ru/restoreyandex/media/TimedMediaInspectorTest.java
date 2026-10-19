package ru.restoreyandex.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.restoreyandex.domain.MediaInspection;
import ru.restoreyandex.domain.MediaType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimedMediaInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void doesNotBlockRecoveryWhenCloudFileReadNeverReturns() throws Exception {
        Path placeholder = Files.writeString(temporaryDirectory.resolve("cloud.jpg"), "placeholder");
        MediaInspector hangingInspector = ignored -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return MediaInspection.readable(MediaType.IMAGE);
        };
        TimedMediaInspector inspector = new TimedMediaInspector(hangingInspector, Duration.ofMillis(20), 1);

        MediaInspection result = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> inspector.inspect(placeholder));

        assertFalse(result.readable());
    }

    @Test
    void waitsForInspectorCapacityInsteadOfImmediatelyRejectingReadableOriginal() throws Exception {
        Path slowFile = Files.writeString(temporaryDirectory.resolve("slow.jpg"), "placeholder");
        Path readableFile = Files.writeString(temporaryDirectory.resolve("readable.jpg"), "image");
        CountDownLatch firstInspectionStarted = new CountDownLatch(1);
        CountDownLatch allowFirstInspectionToFinish = new CountDownLatch(1);
        MediaInspector delegate = file -> {
            if (file.equals(slowFile)) {
                firstInspectionStarted.countDown();
                try {
                    allowFirstInspectionToFinish.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return MediaInspection.unreadable(MediaType.IMAGE);
            }
            return MediaInspection.readable(MediaType.IMAGE);
        };
        TimedMediaInspector inspector = new TimedMediaInspector(delegate, Duration.ofSeconds(1), 1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<MediaInspection> slow = callers.submit(() -> inspector.inspect(slowFile));
            assertTrue(firstInspectionStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            Future<MediaInspection> readable = callers.submit(() -> inspector.inspect(readableFile));

            Thread.sleep(50);
            assertFalse(readable.isDone());
            allowFirstInspectionToFinish.countDown();

            assertFalse(slow.get().readable());
            assertTrue(readable.get().readable());
        } finally {
            allowFirstInspectionToFinish.countDown();
            callers.shutdownNow();
            inspector.close();
        }
    }
}
