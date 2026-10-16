package ru.restoreyandex.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.representer.Representer;
import ru.restoreyandex.logging.ApplicationLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Хранит настройки в читаемом пользователем UTF-8 YAML рядом с программой.
 *
 * <p>Старые файлы {@code .properties} читаются один раз и переносятся в YAML.
 * Список выбранных каталогов хранится только относительно корня Яндекс Диска,
 * поэтому в нём нет персональных абсолютных путей.</p>
 */
public final class PropertiesSettingsRepository {
    private static final String YAML_FILE_NAME = "yandex-media-recovery.yaml";
    private static final String PREVIOUS_PROPERTIES_FILE_NAME = "yandex-media-recovery.properties";
    private static final String OLDEST_PROPERTIES_FILE_NAME = "yandex-preview-recovery.properties";
    private static final String SOURCE_KEY = "sourceFolder";
    private static final String DESTINATION_KEY = "destinationFolder";
    private static final String OVERWRITE_PREVIEWS_KEY = "overwritePreviews";
    private static final String DELETE_LOWER_QUALITY_PREVIEWS_KEY = "deleteLowerQualityPreviews";
    private static final String PARALLELISM_KEY = "parallelism";
    private static final String BROWSER_DEBUG_MODE_KEY = "browserDebugMode";
    private static final String SELECTED_FOLDERS_KEY = "selectedFolders";
    private static final String YANDEX_ACCOUNT_EMAIL_KEY = "yandexAccountEmail";
    private final Path yamlFile;
    private final Path legacyPropertiesFile;

    /** Использует {@code yandex-media-recovery.yaml} рядом с программой. */
    public PropertiesSettingsRepository() {
        this(ApplicationPaths.programDirectory().resolve(YAML_FILE_NAME),
                ApplicationPaths.programDirectory().resolve(PREVIOUS_PROPERTIES_FILE_NAME));
    }

    PropertiesSettingsRepository(Path yamlFile) {
        this(yamlFile, null);
    }

    PropertiesSettingsRepository(Path yamlFile, Path legacyPropertiesFile) {
        this.yamlFile = yamlFile;
        this.legacyPropertiesFile = legacyPropertiesFile;
    }

    /** Загружает YAML, переносит старый properties либо создаёт новый YAML со значениями по умолчанию. */
    public RecoverySettings load() {
        if (Files.isRegularFile(yamlFile)) {
            return loadYaml();
        }
        Path legacy = legacyPropertiesFile != null && Files.isRegularFile(legacyPropertiesFile)
                ? legacyPropertiesFile
                : ApplicationPaths.programDirectory().resolve(OLDEST_PROPERTIES_FILE_NAME);
        if (Files.isRegularFile(legacy)) {
            RecoverySettings migrated = loadLegacyProperties(legacy);
            saveSafely(migrated, "Не удалось перенести старые настройки в YAML");
            return migrated;
        }
        RecoverySettings defaults = defaults();
        saveSafely(defaults, "Не удалось создать YAML-настройки восстановления");
        return defaults;
    }

    /** Записывает настройки в YAML в UTF-8 сразу после изменения пользователем. */
    public void save(RecoverySettings settings) throws IOException {
        Path sourceRoot = settings.sourceFolder().toAbsolutePath().normalize();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SOURCE_KEY, sourceRoot.toString());
        values.put(DESTINATION_KEY, settings.destinationFolder().toAbsolutePath().normalize().toString());
        values.put(OVERWRITE_PREVIEWS_KEY, settings.overwritePreviews());
        values.put(DELETE_LOWER_QUALITY_PREVIEWS_KEY, settings.deleteLowerQualityPreviews());
        values.put(PARALLELISM_KEY, settings.parallelism());
        values.put(BROWSER_DEBUG_MODE_KEY, settings.browserDebugMode());
        values.put(SELECTED_FOLDERS_KEY, settings.selectedFolders().stream()
                .filter(folder -> folder.toAbsolutePath().normalize().startsWith(sourceRoot))
                .map(folder -> portableRelativePath(sourceRoot.relativize(folder.toAbsolutePath().normalize())))
                .toList());
        values.put(YANDEX_ACCOUNT_EMAIL_KEY, settings.yandexAccountEmail());

        Path parent = yamlFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer output = Files.newBufferedWriter(yamlFile, StandardCharsets.UTF_8)) {
            yaml().dump(values, output);
        }
    }

    private RecoverySettings loadYaml() {
        try (Reader input = Files.newBufferedReader(yamlFile, StandardCharsets.UTF_8)) {
            Object loaded = yaml().load(input);
            if (!(loaded instanceof Map<?, ?> values)) {
                throw new IOException("Корень YAML-настроек должен быть объектом");
            }
            Path source = pathOrDefault(values.get(SOURCE_KEY), RecoverySettings.DEFAULT_SOURCE);
            return new RecoverySettings(source,
                    pathOrDefault(values.get(DESTINATION_KEY), RecoverySettings.DEFAULT_DESTINATION),
                    booleanValue(values.get(OVERWRITE_PREVIEWS_KEY), false),
                    booleanValue(values.get(DELETE_LOWER_QUALITY_PREVIEWS_KEY), true),
                    parallelismOrDefault(values.get(PARALLELISM_KEY)),
                    booleanValue(values.get(BROWSER_DEBUG_MODE_KEY), false),
                    selectedFolders(values.get(SELECTED_FOLDERS_KEY), source),
                    emailOrEmpty(values.get(YANDEX_ACCOUNT_EMAIL_KEY)));
        } catch (Exception exception) {
            ApplicationLogger.error("Не удалось загрузить YAML-настройки восстановления", exception);
            return defaults();
        }
    }

    /** Читает предыдущий формат только для миграции уже созданных пользователем настроек. */
    private RecoverySettings loadLegacyProperties(Path legacyFile) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(legacyFile)) {
            properties.load(input);
            Path source = pathOrDefault(properties.getProperty("source.folder"), RecoverySettings.DEFAULT_SOURCE);
            return new RecoverySettings(source,
                    pathOrDefault(properties.getProperty("destination.folder"), RecoverySettings.DEFAULT_DESTINATION),
                    Boolean.parseBoolean(properties.getProperty("previews.overwrite", "false")),
                    Boolean.parseBoolean(properties.getProperty("previews.delete-lower-quality", "true")),
                    parallelismOrDefault(properties.getProperty("processing.parallelism")),
                    Boolean.parseBoolean(properties.getProperty("browser.debug-mode", "false")),
                    migratedSelectedFolders(properties, source), "");
        } catch (Exception exception) {
            ApplicationLogger.error("Не удалось прочитать старые properties-настройки", exception);
            return defaults();
        }
    }

    /** Преобразует прежнее правило «корень выключен, эта ветка включена» в список выбранных папок. */
    private List<Path> migratedSelectedFolders(Properties properties, Path sourceFolder) {
        int count = folderSelectionCount(properties.getProperty("folder.selection.count"));
        boolean rootExcluded = false;
        List<Path> selected = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String base = "folder.selection." + index;
            String storedPath = properties.getProperty(base + ".path");
            boolean included = Boolean.parseBoolean(properties.getProperty(base + ".included", "false"));
            if (storedPath == null) {
                continue;
            }
            if (".".equals(storedPath.strip())) {
                rootExcluded = !included;
                continue;
            }
            Path relative = safeRelativePath(storedPath);
            if (relative == null) {
                continue;
            }
            if (included && relative.getNameCount() > 0) {
                selected.add(sourceFolder.toAbsolutePath().normalize().resolve(relative));
            }
        }
        return rootExcluded ? List.copyOf(selected) : List.of();
    }

    private List<Path> selectedFolders(Object rawValue, Path sourceFolder) {
        if (!(rawValue instanceof List<?> values)) {
            return List.of();
        }
        Path sourceRoot = sourceFolder.toAbsolutePath().normalize();
        List<Path> selected = new ArrayList<>();
        for (Object value : values) {
            Path relative = value instanceof String text ? safeRelativePath(text) : null;
            if (relative != null && relative.getNameCount() > 0) {
                selected.add(sourceRoot.resolve(relative));
            }
        }
        return List.copyOf(selected);
    }

    private Path safeRelativePath(String text) {
        try {
            Path relative = Path.of(text).normalize();
            if (relative.isAbsolute() || relative.startsWith("..")) {
                ApplicationLogger.info("Пропускаю недопустимый путь выбранной папки: " + text);
                return null;
            }
            return relative;
        } catch (IllegalArgumentException exception) {
            ApplicationLogger.error("Не удалось прочитать путь выбранной папки: " + text, exception);
            return null;
        }
    }

    private String portableRelativePath(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private Path pathOrDefault(Object value, Path fallback) {
        if (!(value instanceof String text) || text.isBlank()) {
            return fallback;
        }
        return Path.of(text);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private int parallelismOrDefault(Object value) {
        try {
            int parallelism = value == null ? RecoverySettings.DEFAULT_PARALLELISM : Integer.parseInt(value.toString());
            return parallelism >= 1 && parallelism <= 10 ? parallelism : RecoverySettings.DEFAULT_PARALLELISM;
        } catch (NumberFormatException exception) {
            ApplicationLogger.error("Некорректное число потоков в настройках", exception);
            return RecoverySettings.DEFAULT_PARALLELISM;
        }
    }

    private String emailOrEmpty(Object value) {
        if (!(value instanceof String email)) {
            return "";
        }
        String normalized = email.strip();
        return normalized.matches("(?i)^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") ? normalized : "";
    }

    private int folderSelectionCount(String value) {
        try {
            return Math.max(0, Integer.parseInt(value == null ? "0" : value));
        } catch (NumberFormatException exception) {
            ApplicationLogger.error("Некорректное количество выбранных папок в старых настройках", exception);
            return 0;
        }
    }

    private RecoverySettings defaults() {
        return new RecoverySettings(RecoverySettings.DEFAULT_SOURCE, RecoverySettings.DEFAULT_DESTINATION,
                false, true, RecoverySettings.DEFAULT_PARALLELISM, false, List.of(), "");
    }

    private void saveSafely(RecoverySettings settings, String message) {
        try {
            save(settings);
        } catch (IOException exception) {
            ApplicationLogger.error(message, exception);
        }
    }

    private Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        return new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(options), options);
    }
}
