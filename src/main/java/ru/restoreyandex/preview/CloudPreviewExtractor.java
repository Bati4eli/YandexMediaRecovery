package ru.restoreyandex.preview;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Получает превью из облачного веб-клиента по относительному пути на Диске. */
@FunctionalInterface
public interface CloudPreviewExtractor {
    /**
     * Пытается получить превью по пути, относительному к корню локального Диска.
     * Реализация не должна записывать файлы назначения.
     */
    Optional<BufferedImage> extract(Path relativePath);

    /**
     * Открывает окно браузера для ручного входа. Не поддерживающие авторизацию
     * реализации оставляют метод без действия.
     */
    default void openLogin() throws IOException {
        throw new IOException("Вход в Яндекс Диск недоступен для этого источника превью");
    }
}
