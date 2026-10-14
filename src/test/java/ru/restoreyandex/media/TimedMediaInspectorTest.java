package ru.restoreyandex.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.restoreyandex.domain.MediaInspection;
import ru.restoreyandex.domain.MediaType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
}
