package ru.restoreyandex.downloader;

import java.util.Objects;

/**
 * Один внешний файл, который BAT должен положить в каталог {@code app}.
 *
 * @param url HTTPS-адрес файла в репозитории зависимостей
 * @param fileName локальное имя JAR без пути
 */
public record DownloadArtifact(String url, String fileName) {

    /**
     * Проверяет, что артефакт безопасно использовать при формировании BAT.
     */
    public DownloadArtifact {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(fileName, "fileName");
        if (!url.startsWith("https://") || url.contains("\r") || url.contains("\n")) {
            throw new IllegalArgumentException("URL зависимости должен быть HTTPS-адресом без переводов строк");
        }
        if (!fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar")) {
            throw new IllegalArgumentException("Имя зависимости должно быть простым именем JAR-файла");
        }
    }
}
