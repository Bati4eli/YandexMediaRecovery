package ru.restoreyandex.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import ru.restoreyandex.logging.ApplicationLogger;

/** Сохраняет выбранные пользователем папки в Java properties рядом с программой. */
public final class PropertiesSettingsRepository {
    private static final String LEGACY_FILE_NAME = "yandex-preview-recovery.properties";
    private static final String SOURCE_KEY = "source.folder";
    private static final String DESTINATION_KEY = "destination.folder";
    private static final String OVERWRITE_PREVIEWS_KEY = "previews.overwrite";
    private static final String DELETE_LOWER_QUALITY_PREVIEWS_KEY = "previews.delete-lower-quality";
    private static final String PARALLELISM_KEY = "processing.parallelism";
    private static final String BROWSER_DEBUG_MODE_KEY = "browser.debug-mode";
    private final Path file;
    private final Path legacyFile;

    /** Использует {@code yandex-media-recovery.properties} рядом с программой. */
    public PropertiesSettingsRepository() {
        this(ApplicationPaths.programDirectory().resolve("yandex-media-recovery.properties"),
                ApplicationPaths.programDirectory().resolve(LEGACY_FILE_NAME));
    }

    PropertiesSettingsRepository(Path file) {
        this(file, null);
    }

    PropertiesSettingsRepository(Path file, Path legacyFile) {
        this.file = file;
        this.legacyFile = legacyFile;
    }

    /** Загружает сохранённые значения или возвращает значения по умолчанию. */
    public RecoverySettings load() {
        Properties properties = new Properties();
        Path source = settingsFileToLoad();
        if (source == null) {
            return defaults();
        }
        try (InputStream input = Files.newInputStream(source)) {
            properties.load(input);
            return new RecoverySettings(
                    pathOrDefault(properties.getProperty(SOURCE_KEY), RecoverySettings.DEFAULT_SOURCE),
                    pathOrDefault(properties.getProperty(DESTINATION_KEY), RecoverySettings.DEFAULT_DESTINATION),
                    Boolean.parseBoolean(properties.getProperty(OVERWRITE_PREVIEWS_KEY, "false")),
                    Boolean.parseBoolean(properties.getProperty(DELETE_LOWER_QUALITY_PREVIEWS_KEY, "true")),
                    parallelismOrDefault(properties.getProperty(PARALLELISM_KEY)),
                    Boolean.parseBoolean(properties.getProperty(BROWSER_DEBUG_MODE_KEY, "false")));
        } catch (IOException | IllegalArgumentException exception) {
            ApplicationLogger.error("Не удалось загрузить настройки восстановления", exception);
            return defaults();
        }
    }

    /** Сохраняет пути сразу после их выбора или подтверждения пользователем. */
    public void save(RecoverySettings settings) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(SOURCE_KEY, settings.sourceFolder().toAbsolutePath().normalize().toString());
        properties.setProperty(DESTINATION_KEY, settings.destinationFolder().toAbsolutePath().normalize().toString());
        properties.setProperty(OVERWRITE_PREVIEWS_KEY, Boolean.toString(settings.overwritePreviews()));
        properties.setProperty(DELETE_LOWER_QUALITY_PREVIEWS_KEY,
                Boolean.toString(settings.deleteLowerQualityPreviews()));
        properties.setProperty(PARALLELISM_KEY, Integer.toString(settings.parallelism()));
        properties.setProperty(BROWSER_DEBUG_MODE_KEY, Boolean.toString(settings.browserDebugMode()));
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "Yandex media recovery folders");
        }
    }

    private RecoverySettings defaults() {
        return new RecoverySettings(RecoverySettings.DEFAULT_SOURCE, RecoverySettings.DEFAULT_DESTINATION,
                false, true, RecoverySettings.DEFAULT_PARALLELISM, false);
    }

    private Path settingsFileToLoad() {
        if (Files.isRegularFile(file)) {
            return file;
        }
        return legacyFile != null && Files.isRegularFile(legacyFile) ? legacyFile : null;
    }

    private Path pathOrDefault(String value, Path fallback) {
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    private int parallelismOrDefault(String value) {
        try {
            int parallelism = value == null ? RecoverySettings.DEFAULT_PARALLELISM : Integer.parseInt(value);
            return parallelism >= 1 && parallelism <= 10 ? parallelism : RecoverySettings.DEFAULT_PARALLELISM;
        } catch (NumberFormatException exception) {
            ApplicationLogger.error("Некорректное число потоков в настройках", exception);
            return RecoverySettings.DEFAULT_PARALLELISM;
        }
    }
}
