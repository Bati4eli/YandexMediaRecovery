package ru.restoreyandex.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет безопасную очистку сохранённых приложением превью. */
class DestinationPreviewCleanerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void deletesOnlyRecognizedPreviewsRecursively() throws Exception {
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination/2020/trip"));
        Path root = destination.getParent().getParent();
        Path explorerPreview = Files.writeString(destination.resolve("PREW_photo.png"), "preview");
        Path webPreview = Files.writeString(destination.resolve("WEBPV_photo.jpg"), "preview");
        Path binaryWebPreview = Files.writeString(destination.resolve("WEBPV_unknown.bin"), "preview");
        Path miniPreview = Files.writeString(destination.resolve("MINIPV_photo.png"), "preview");
        Path original = Files.writeString(destination.resolve("photo.jpg"), "original");
        Path unrelatedPrew = Files.writeString(destination.resolve("PREW_notes.txt"), "keep");
        Path unrelatedMini = Files.writeString(destination.resolve("MINIPV_notes.jpg"), "keep");
        Path unrelatedWeb = Files.writeString(destination.resolve("WEBPV_notes.txt"), "keep");
        Path similarlyNamedDirectory = Files.createDirectories(destination.resolve("PREW_folder"));
        Path fileInDirectory = Files.writeString(similarlyNamedDirectory.resolve("photo.png"), "keep");

        PreviewCleanupReport report = new DestinationPreviewCleaner().deletePreviews(root);

        assertEquals(4, report.deleted());
        assertEquals(0, report.failed());
        assertFalse(Files.exists(explorerPreview));
        assertFalse(Files.exists(webPreview));
        assertFalse(Files.exists(binaryWebPreview));
        assertFalse(Files.exists(miniPreview));
        assertTrue(Files.exists(original));
        assertTrue(Files.exists(unrelatedPrew));
        assertTrue(Files.exists(unrelatedMini));
        assertTrue(Files.exists(unrelatedWeb));
        assertTrue(Files.isDirectory(similarlyNamedDirectory));
        assertTrue(Files.exists(fileInDirectory));
    }

    @Test
    void rejectsMissingDestinationFolder() {
        Path missingFolder = temporaryDirectory.resolve("missing");

        assertThrows(IOException.class, () -> new DestinationPreviewCleaner().deletePreviews(missingFolder));
    }
}
