package ru.restoreyandex.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesSettingsRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsPreviewSettingsAndParallelism() throws Exception {
        Path propertiesFile = temporaryDirectory.resolve("settings.properties");
        RecoverySettings settings = new RecoverySettings(Path.of("C:/source"), Path.of("D:/destination"), true, false, 7);

        new PropertiesSettingsRepository(propertiesFile).save(settings);
        RecoverySettings loaded = new PropertiesSettingsRepository(propertiesFile).load();

        assertTrue(loaded.overwritePreviews());
        assertEquals(false, loaded.deleteLowerQualityPreviews());
        assertEquals(7, loaded.parallelism());
    }

    @Test
    void loadsLegacyPropertiesWhenTheNewFileIsNotCreatedYet() throws Exception {
        Path current = temporaryDirectory.resolve("yandex-media-recovery.properties");
        Path legacy = temporaryDirectory.resolve("yandex-preview-recovery.properties");
        Files.writeString(legacy, "source.folder=C:/source\ndestination.folder=D:/destination\n");

        RecoverySettings loaded = new PropertiesSettingsRepository(current, legacy).load();

        assertEquals(Path.of("C:/source"), loaded.sourceFolder());
        assertEquals(RecoverySettings.DEFAULT_PARALLELISM, loaded.parallelism());
    }
}
