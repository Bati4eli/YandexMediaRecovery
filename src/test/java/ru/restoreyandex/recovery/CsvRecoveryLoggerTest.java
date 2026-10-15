package ru.restoreyandex.recovery;

import ru.restoreyandex.domain.RecoveryEvent;
import ru.restoreyandex.domain.RecoveryOutcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvRecoveryLoggerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesQuotedPathNameAndRequiredStatusForEveryEvent() throws Exception {
        Path source = Path.of("C:/Фото, архив/свадьба.jpg");
        try (CsvRecoveryLogger logger = new CsvRecoveryLogger(temporaryDirectory)) {
            logger.onStarted(1);
            logger.onFileProcessed(new RecoveryEvent(1, 1, source, RecoveryOutcome.WEB_PREVIEW_SAVED, "saved"));

            List<String> lines = Files.readAllLines(logger.file());
            assertEquals("\"path\",\"file_name\",\"status\"", lines.get(0));
            assertEquals("\"" + source.toAbsolutePath().normalize() + "\",\"свадьба.jpg\",\"WEBPV_SAVED\"", lines.get(1));
        }
    }

    @Test
    void doesNotWriteAnEntryWhenExistingTargetWasNotChanged() throws Exception {
        try (CsvRecoveryLogger logger = new CsvRecoveryLogger(temporaryDirectory)) {
            logger.onFileProcessed(new RecoveryEvent(1, 1, Path.of("C:/photo.jpg"),
                    RecoveryOutcome.ALREADY_OK, "already exists"));

            assertEquals(1, Files.readAllLines(logger.file()).size());
        }
    }
}
