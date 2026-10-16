package ru.restoreyandex.preview;

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
    Optional<BrowserPreview> extract(Path relativePath);

    /**
     * Открывает окно браузера для ручного входа. Не поддерживающие авторизацию
     * реализации оставляют метод без действия.
     */
    default YandexAccountInfo openLogin() throws IOException {
        throw new IOException("Вход в Яндекс Диск недоступен для этого источника превью");
    }

    /**
     * Выходит из аккаунта и удаляет только постоянный браузерный профиль
     * приложения. Не поддерживающие авторизацию реализации оставляют метод
     * без действия.
     */
    default void logout() throws IOException {
    }

    /** Включает или выключает видимое окно Chrome для диагностики маршрута файлов. */
    default void configureBrowserDebugMode(boolean enabled) {
    }

    /** Немедленно прекращает текущую работу управляемого браузерного канала. */
    default void close() {
    }
}
