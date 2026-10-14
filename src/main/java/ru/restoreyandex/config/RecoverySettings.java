package ru.restoreyandex.config;

import java.nio.file.Path;

/** Последние папки, выбранные пользователем для восстановления. */
public record RecoverySettings(Path sourceFolder, Path destinationFolder) {
    /** Типовой путь Яндекс Диска внутри домашней папки текущего пользователя. */
    public static final Path DEFAULT_SOURCE = Path.of(System.getProperty("user.home"), "Yandex.Disk");
    public static final Path DEFAULT_DESTINATION = Path.of("D:\\YandexDisk-BACKUP");
}
