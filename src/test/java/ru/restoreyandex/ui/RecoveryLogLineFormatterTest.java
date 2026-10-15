package ru.restoreyandex.ui;

import org.junit.jupiter.api.Test;
import ru.restoreyandex.domain.RecoveryEvent;
import ru.restoreyandex.domain.RecoveryOutcome;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет компактное отображение пути в экранном журнале. */
class RecoveryLogLineFormatterTest {
    @Test
    void showsOnlyPathRelativeToConfiguredSourceFolder() {
        Path sourceRoot = Path.of("C:/media/source").toAbsolutePath();
        Path source = sourceRoot.resolve("2005/Дача/IMG_0198.jpg");
        RecoveryEvent event = new RecoveryEvent(1, 1, source, RecoveryOutcome.WEB_PREVIEW_SAVED,
                "Превью сохранено: D:/backup/2005/Дача/WEBPV_IMG_0198.png");

        assertEquals("🌐 WEBPV_SAVED — 2005\\Дача\\IMG_0198.jpg",
                RecoveryLogLineFormatter.format(event, sourceRoot));
    }

    @Test
    void keepsNormalizedSourcePathWhenEventIsOutsideSourceFolder() {
        Path sourceRoot = Path.of("C:/media/source").toAbsolutePath();
        Path source = Path.of("C:/other/IMG_0198.jpg").toAbsolutePath();
        RecoveryEvent event = new RecoveryEvent(1, 1, source, RecoveryOutcome.NO_FILE, "Ошибка");

        assertEquals("❌ NO_FILE — " + source.normalize(), RecoveryLogLineFormatter.format(event, sourceRoot));
    }
}
