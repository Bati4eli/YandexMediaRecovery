package ru.restoreyandex.preview;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Получает качественное превью через уже авторизованный веб-клиент Яндекс Диска.
 * Байты берутся из ответа, уже полученного Chrome, поэтому второй запрос без
 * сессионных cookie и связанная с ним ошибка HTTP 403 исключены.
 */
public final class YandexDiskBrowserPreviewExtractor implements CloudPreviewExtractor {
    private final YandexDiskBrowserSession session;

    /** Создаёт источник с единственной сессией Chrome на всё время работы приложения. */
    public YandexDiskBrowserPreviewExtractor() {
        this(new YandexDiskBrowserSession());
    }

    YandexDiskBrowserPreviewExtractor(YandexDiskBrowserSession session) {
        this.session = Objects.requireNonNull(session);
    }

    /** Открывает пользователю Chrome для входа в сохранённый профиль приложения. */
    @Override
    public YandexAccountInfo openLogin() throws IOException {
        return session.openLogin();
    }

    /** Выполняет фоновый выход из Яндекс Диска через профиль приложения. */
    @Override
    public void logout() throws IOException {
        session.logout();
    }

    /** Настраивает видимость браузера для следующего запроса превью. */
    @Override
    public void configureBrowserDebugMode(boolean enabled) {
        session.setDebugMode(enabled);
    }

    /** Закрывает Chrome и тем самым отменяет текущую загрузку превью. */
    @Override
    public void close() {
        session.close();
    }

    /**
     * Синхронно получает одно изображение. Синхронизация не допускает
     * параллельных открытий просмотрщика и ограничивает запросы одним каналом.
     */
    @Override
    public synchronized Optional<BrowserPreview> extract(Path relativePath) {
        return session.extractLargestPreview(relativePath);
    }
}
