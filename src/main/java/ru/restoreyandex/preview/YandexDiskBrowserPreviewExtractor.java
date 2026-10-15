package ru.restoreyandex.preview;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import ru.restoreyandex.logging.ApplicationLogger;

/**
 * Получает качественное превью через уже авторизованный веб-клиент Яндекс Диска.
 * Подписанная ссылка извлекается браузером, а байты загружаются отдельно, без
 * чтения cookie Chrome и без использования неофициального REST API.
 */
public final class YandexDiskBrowserPreviewExtractor implements CloudPreviewExtractor {
    private final YandexDiskBrowserSession session;
    private final HttpClient httpClient;

    /** Создаёт источник с единственной сессией Chrome на всё время работы приложения. */
    public YandexDiskBrowserPreviewExtractor() {
        this(new YandexDiskBrowserSession(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    YandexDiskBrowserPreviewExtractor(YandexDiskBrowserSession session, HttpClient httpClient) {
        this.session = Objects.requireNonNull(session);
        this.httpClient = Objects.requireNonNull(httpClient);
    }

    /** Открывает пользователю Chrome для входа в сохранённый профиль приложения. */
    @Override
    public void openLogin() throws IOException {
        session.openLogin();
    }

    /**
     * Синхронно получает одно изображение. Синхронизация не допускает
     * параллельных открытий просмотрщика и ограничивает запросы одним каналом.
     */
    @Override
    public synchronized Optional<BufferedImage> extract(Path relativePath) {
        return session.findLargestPreview(relativePath).flatMap(this::download);
    }

    private Optional<BufferedImage> download(URI previewUri) {
        HttpRequest request = HttpRequest.newBuilder(previewUri).timeout(Duration.ofSeconds(45)).GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                return Optional.empty();
            }
            try (InputStream body = response.body()) {
                return Optional.ofNullable(ImageIO.read(body));
            }
        } catch (IOException exception) {
            ApplicationLogger.error("Не удалось скачать серверное превью Яндекс Диска", exception);
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ApplicationLogger.error("Скачивание серверного превью Яндекс Диска прервано", exception);
            return Optional.empty();
        }
    }
}
