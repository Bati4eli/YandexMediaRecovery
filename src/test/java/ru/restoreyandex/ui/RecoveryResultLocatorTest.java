package ru.restoreyandex.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет выбор лучшего доступного результата восстановления для Проводника. */
class RecoveryResultLocatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void prefersOriginalOverEveryPreview() throws Exception {
        Path source = Path.of("C:/source/album/photo.jpg");
        Path recoveryFolder = Files.createDirectories(temporaryDirectory.resolve("recovery/album"));
        Path original = Files.writeString(recoveryFolder.resolve("photo.jpg"), "original");
        Files.writeString(recoveryFolder.resolve("WEBPV_photo.webp"), "web preview");
        Files.writeString(recoveryFolder.resolve("PREW_photo.png"), "explorer preview");

        assertEquals(original, RecoveryResultLocator.find(source, recoveryFolder).orElseThrow());
    }

    @Test
    void prefersWebPreviewWithAnyExtensionBeforeExplorerAndMiniPreviews() throws Exception {
        Path source = Path.of("C:/source/album/photo.jpg");
        Path recoveryFolder = Files.createDirectories(temporaryDirectory.resolve("recovery/album"));
        Path webPreview = Files.writeString(recoveryFolder.resolve("WEBPV_photo.webp"), "web preview");
        Files.writeString(recoveryFolder.resolve("PREW_photo.png"), "explorer preview");
        Files.writeString(recoveryFolder.resolve("MINIPV_photo.png"), "mini preview");

        assertEquals(webPreview, RecoveryResultLocator.find(source, recoveryFolder).orElseThrow());
    }

    @Test
    void prefersExplorerPreviewBeforeMiniPreview() throws Exception {
        Path source = Path.of("C:/source/album/photo.jpg");
        Path recoveryFolder = Files.createDirectories(temporaryDirectory.resolve("recovery/album"));
        Path explorerPreview = Files.writeString(recoveryFolder.resolve("PREW_photo.png"), "explorer preview");
        Files.writeString(recoveryFolder.resolve("MINIPV_photo.png"), "mini preview");

        assertEquals(explorerPreview, RecoveryResultLocator.find(source, recoveryFolder).orElseThrow());
    }

    @Test
    void returnsEmptyWhenNoRecoveredResultExists() throws Exception {
        Path recoveryFolder = Files.createDirectories(temporaryDirectory.resolve("recovery/album"));

        assertTrue(RecoveryResultLocator.find(Path.of("C:/source/album/photo.jpg"), recoveryFolder).isEmpty());
    }
}
