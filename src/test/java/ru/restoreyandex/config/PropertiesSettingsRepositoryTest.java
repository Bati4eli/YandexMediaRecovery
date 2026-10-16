package ru.restoreyandex.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет UTF-8 YAML и перенос старых пользовательских настроек. */
class PropertiesSettingsRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsPreviewSettingsAndParallelismInYaml() throws Exception {
        Path yamlFile = temporaryDirectory.resolve("settings.yaml");
        RecoverySettings settings = new RecoverySettings(Path.of("C:/source"), Path.of("D:/destination"), true, false, 7,
                true, List.of(), "user@example.org", true);

        new PropertiesSettingsRepository(yamlFile).save(settings);
        RecoverySettings loaded = new PropertiesSettingsRepository(yamlFile).load();

        assertTrue(loaded.overwritePreviews());
        assertEquals(false, loaded.deleteLowerQualityPreviews());
        assertEquals(7, loaded.parallelism());
        assertTrue(loaded.browserDebugMode());
        assertEquals("user@example.org", loaded.yandexAccountEmail());
        assertTrue(loaded.yandexAuthenticationConfirmed());
        assertTrue(Files.readString(yamlFile, StandardCharsets.UTF_8).contains("sourceFolder:"));
    }

    @Test
    void migratesSelectedFoldersFromPreviousPropertiesFormat() throws Exception {
        Path yamlFile = temporaryDirectory.resolve("settings.yaml");
        Path legacy = temporaryDirectory.resolve("yandex-media-recovery.properties");
        Path source = Path.of("C:/source");
        Files.writeString(legacy, "source.folder=C:/source\n"
                + "destination.folder=D:/destination\n"
                + "folder.selection.count=2\n"
                + "folder.selection.0.path=.\n"
                + "folder.selection.0.included=false\n"
                + "folder.selection.1.path=MEDIA/\\u0421\\u0435\\u043c\\u044c\\u044f\n"
                + "folder.selection.1.included=true\n", StandardCharsets.UTF_8);

        RecoverySettings loaded = new PropertiesSettingsRepository(yamlFile, legacy).load();

        assertEquals(List.of(source.resolve("MEDIA/Семья")), loaded.selectedFolders());
        assertTrue(Files.isRegularFile(yamlFile));
    }

    @Test
    void persistsSelectedFoldersRelativeToDiskRootUsingReadableUtf8() throws Exception {
        Path yamlFile = temporaryDirectory.resolve("settings.yaml");
        Path source = temporaryDirectory.resolve("disk");
        Path selected = source.resolve("MEDIA/Семья/Свадьба");
        RecoverySettings settings = new RecoverySettings(source, temporaryDirectory.resolve("backup"), false, true, 1,
                false, List.of(selected));

        new PropertiesSettingsRepository(yamlFile).save(settings);
        RecoverySettings loaded = new PropertiesSettingsRepository(yamlFile).load();
        String stored = Files.readString(yamlFile, StandardCharsets.UTF_8);

        assertEquals(List.of(selected), loaded.selectedFolders());
        assertTrue(stored.contains("selectedFolders:"));
        assertTrue(stored.contains("MEDIA/Семья/Свадьба"));
        assertTrue(!stored.contains("\\u"));
    }

    @Test
    void createsYamlFileWhenItDoesNotExistYet() {
        Path yamlFile = temporaryDirectory.resolve("settings.yaml");

        new PropertiesSettingsRepository(yamlFile).load();

        assertTrue(Files.isRegularFile(yamlFile));
    }
}
