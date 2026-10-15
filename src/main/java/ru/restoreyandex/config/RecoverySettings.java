package ru.restoreyandex.config;

import java.nio.file.Path;

/** Последние папки и параметры, выбранные пользователем для восстановления. */
public record RecoverySettings(Path sourceFolder, Path destinationFolder, boolean overwritePreviews,
                               boolean deleteLowerQualityPreviews, int parallelism) {
    /** Создаёт настройки со включённым удалением превью худшего качества. */
    public RecoverySettings(Path sourceFolder, Path destinationFolder, boolean overwritePreviews, int parallelism) {
        this(sourceFolder, destinationFolder, overwritePreviews, true, parallelism);
    }
    /** Типовой путь Яндекс Диска внутри домашней папки текущего пользователя. */
    public static final Path DEFAULT_SOURCE = Path.of(System.getProperty("user.home"), "Yandex.Disk");
    public static final Path DEFAULT_DESTINATION = Path.of("D:\\YandexDisk-BACKUP");
    public static final int DEFAULT_PARALLELISM = 1;

    /** Проверяет допустимый диапазон пользовательской настройки потоков. */
    public RecoverySettings {
        if (parallelism < 1 || parallelism > 10) {
            throw new IllegalArgumentException("Количество потоков должно быть от 1 до 10");
        }
    }
}
