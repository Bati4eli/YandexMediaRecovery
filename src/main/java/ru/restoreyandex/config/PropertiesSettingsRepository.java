package ru.restoreyandex.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Сохраняет выбранные пользователем папки в Java properties рядом с программой. */
public final class PropertiesSettingsRepository {
    private static final String SOURCE_KEY = "source.folder";
    private static final String DESTINATION_KEY = "destination.folder";
    private final Path file;

    /** Использует {@code yandex-media-recovery.properties} рядом с программой. */
    public PropertiesSettingsRepository() {
        this(ApplicationPaths.programDirectory().resolve("yandex-media-recovery.properties"));
    }

    PropertiesSettingsRepository(Path file) {
        this.file = file;
    }

    /** Загружает сохранённые значения или возвращает значения по умолчанию. */
    public RecoverySettings load() {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return new RecoverySettings(
                    pathOrDefault(properties.getProperty(SOURCE_KEY), RecoverySettings.DEFAULT_SOURCE),
                    pathOrDefault(properties.getProperty(DESTINATION_KEY), RecoverySettings.DEFAULT_DESTINATION));
        } catch (IOException | IllegalArgumentException exception) {
            return defaults();
        }
    }

    /** Сохраняет пути сразу после их выбора или подтверждения пользователем. */
    public void save(RecoverySettings settings) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(SOURCE_KEY, settings.sourceFolder().toAbsolutePath().normalize().toString());
        properties.setProperty(DESTINATION_KEY, settings.destinationFolder().toAbsolutePath().normalize().toString());
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "Yandex media recovery folders");
        }
    }

    private RecoverySettings defaults() {
        return new RecoverySettings(RecoverySettings.DEFAULT_SOURCE, RecoverySettings.DEFAULT_DESTINATION);
    }

    private Path pathOrDefault(String value, Path fallback) {
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }
}
