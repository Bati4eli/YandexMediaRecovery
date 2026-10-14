package ru.restoreyandex.config;

import java.nio.file.Path;

/** Определяет расположение вспомогательных файлов рядом с запущенной программой. */
public final class ApplicationPaths {
    private ApplicationPaths() {
    }

    /**
     * Возвращает рабочую папку процесса. Gradle, сгенерированный стартовый
     * сценарий и упакованное приложение используют её как папку программы.
     */
    public static Path programDirectory() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
