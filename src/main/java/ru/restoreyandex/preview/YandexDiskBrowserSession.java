package ru.restoreyandex.preview;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitUntilState;
import ru.restoreyandex.config.ApplicationPaths;
import ru.restoreyandex.logging.ApplicationLogger;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Единственная управляемая сессия Chrome для Яндекс Диска.
 *
 * <p>Сессия использует отдельный постоянный профиль рядом с приложением. Это
 * позволяет пользователю один раз войти в Яндекс в обычном окне Chrome, а
 * затем не передавать программе пароль, OAuth-токен или cookie. Все обращения
 * синхронизированы: параллельные рабочие потоки восстановления не открывают
 * несколько вкладок и не создают всплеск запросов к Диску.</p>
 */
public final class YandexDiskBrowserSession implements AutoCloseable {
    private static final String DISK_ROOT = "https://disk.yandex.ru/client/disk";
    private static final String PREVIEW_IMAGE_SELECTOR = "img.scalable-preview__image";
    private static final long PREVIEW_WAIT_MILLIS = 30_000;
    private static final long MINIMUM_REQUEST_INTERVAL_MILLIS = 200;
    private final RequestRateLimiter requestRateLimiter = new RequestRateLimiter(MINIMUM_REQUEST_INTERVAL_MILLIS);
    private volatile Playwright playwright;
    private volatile BrowserContext context;
    private volatile Page page;
    private volatile boolean debugMode;

    /** Задаёт видимость Chrome для следующего открытия сессии восстановления. */
    public void setDebugMode(boolean enabled) {
        debugMode = enabled;
    }

    /** Открывает Chrome на Диске, чтобы пользователь выполнил вход вручную. */
    public synchronized void openLogin() throws IOException {
        close();
        try {
            Page loginPage = ensurePage(false);
            loginPage.navigate(DISK_ROOT);
            waitForAuthenticatedDiskRoot(loginPage);
        } catch (PlaywrightException | IOException exception) {
            ApplicationLogger.error("Не удалось открыть Chrome для входа в Яндекс Диск", exception);
            throw new IOException("Не удалось запустить Chrome для входа в Яндекс Диск", exception);
        } finally {
            close();
        }
    }

    /**
     * Открывает файл в просмотрщике Яндекс Диска и возвращает байты большого
     * превью, которые уже получил Chrome.
     *
     * <p>Последовательность намеренно выполняется под одним монитором:
     * открывается URL конкретного файла, ожидается <em>видимое</em> и уже
     * готовое изображение, затем читается тело того же сетевого ответа
     * через Playwright. Отдельный {@code HttpClient} здесь запрещён: ссылка
     * предпросмотра может быть привязана к сессии Chrome и второй запрос
     * получает HTTP 403. Скрытые изображения предыдущего слайда исключаются
     * селектором {@code :visible}.</p>
     *
     * @param relativePath путь к файлу относительно корня Яндекс Диска
     * @return исходные байты большого превью или пустой результат при ошибке
     */
    public synchronized Optional<BrowserPreview> extractLargestPreview(Path relativePath) {
        try {
            requestRateLimiter.awaitSlot();
            Page browserPage = ensurePage(true);
            var viewerUri = YandexDiskUrlBuilder.viewerUri(relativePath);
            ApplicationLogger.info("Браузер: открываю просмотр " + relativePath + " — " + viewerUri);
            Response previewResponse = browserPage.waitForResponse(
                    response -> isPreviewResponseForFile(response, relativePath.getFileName().toString()),
                    new Page.WaitForResponseOptions().setTimeout(PREVIEW_WAIT_MILLIS),
                    () -> openViewerAndWaitForVisiblePreview(browserPage, viewerUri.toString()));
            if (previewResponse.status() / 100 != 2) {
                ApplicationLogger.info("Браузер получил HTTP " + previewResponse.status()
                        + " для большого превью: " + relativePath);
                return Optional.empty();
            }
            ApplicationLogger.info("Браузер: получаю уже загруженное превью " + previewResponse.url());
            byte[] bytes = previewResponse.body();
            if (bytes.length == 0) {
                ApplicationLogger.info("Chrome получил пустой ресурс превью: " + relativePath);
                return Optional.empty();
            }
            String extension = previewExtension(previewResponse.headerValue("content-type"));
            ApplicationLogger.info("Большое превью получено из Chrome без перекодирования: %d байт, .%s"
                    .formatted(bytes.length, extension));
            return Optional.of(new BrowserPreview(bytes, extension));
        } catch (PlaywrightException | IllegalArgumentException exception) {
            ApplicationLogger.error("Не удалось получить большое превью из Chrome", exception);
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ApplicationLogger.error("Ожидание обращения к Яндекс Диску прервано", exception);
            return Optional.empty();
        }
    }

    /** Открывает просмотрщик и ожидает пиксели именно видимой фотографии, а не скрытый прошлый слайд SPA. */
    private void openViewerAndWaitForVisiblePreview(Page activePage, String viewerUrl) {
        activePage.navigate(viewerUrl, previewNavigationOptions());
        activePage.waitForFunction("""
                selector => Array.from(document.querySelectorAll(selector)).some(image => {
                    const style = window.getComputedStyle(image);
                    const visible = style.display !== 'none' && style.visibility !== 'hidden'
                            && image.getClientRects().length > 0;
                    return visible && image.complete && image.naturalWidth > 0 && image.naturalHeight > 0;
                })
                """, PREVIEW_IMAGE_SELECTOR, previewImageWaitOptions());
    }

    /**
     * Проверяет, что ответ принадлежит текущему файлу, а не иконке или слайду,
     * который Яндекс Диск оставил в DOM после предыдущей навигации.
     */
    static boolean isPreviewResponseForFile(Response response, String fileName) {
        return isPreviewResponseUrl(response.url(), fileName);
    }

    /** Сопоставляет URL ответа с именем открытого файла после декодирования параметра {@code filename}. */
    static boolean isPreviewResponseUrl(String responseUrl, String fileName) {
        String decodedUrl = URLDecoder.decode(responseUrl, StandardCharsets.UTF_8);
        return decodedUrl.contains("filename=" + fileName);
    }

    /** Сопоставляет Content-Type ответа с расширением, не меняя скачанный формат. */
    static String previewExtension(String contentType) {
        if (contentType == null) {
            return "bin";
        }
        String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("image/jpeg")) {
            return "jpg";
        }
        if (normalized.startsWith("image/png")) {
            return "png";
        }
        if (normalized.startsWith("image/webp")) {
            return "webp";
        }
        if (normalized.startsWith("image/gif")) {
            return "gif";
        }
        return "bin";
    }

    /**
     * Ограничивает навигацию состоянием DOM, потому что страница Диска как SPA
     * может бесконечно ждать событие {@code load} из-за фоновых запросов.
     */
    static Page.NavigateOptions previewNavigationOptions() {
        return new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(PREVIEW_WAIT_MILLIS);
    }

    /**
     * Не отдаёт очередь следующему файлу, пока браузер не декодировал пиксели
     * превью. Это исключает чтение промежуточного {@code src} у SPA Яндекс Диска.
     */
    static Page.WaitForFunctionOptions previewImageWaitOptions() {
        return new Page.WaitForFunctionOptions().setTimeout(PREVIEW_WAIT_MILLIS);
    }

    /** Возвращает минимальный интервал между запусками последовательных запросов к просмотру Яндекс Диска. */
    static long previewRequestIntervalMillis() {
        return MINIMUM_REQUEST_INTERVAL_MILLIS;
    }

    /** Ожидает ручного входа и появления корня файлов Диска без ввода учётных данных программой. */
    private void waitForAuthenticatedDiskRoot(Page loginPage) throws IOException {
        Locator filesTab = loginPage.getByRole(AriaRole.TAB,
                new Page.GetByRoleOptions().setName("Файлы").setExact(true));
        try {
            while (!loginPage.isClosed()) {
                if (loginPage.url().startsWith(DISK_ROOT) && filesTab.count() > 0 && filesTab.first().isVisible()) {
                    ApplicationLogger.info("Вход в Яндекс Диск подтверждён; окно Chrome закрывается.");
                    return;
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Ожидание входа в Яндекс Диск прервано", exception);
        }
        throw new IOException("Окно Chrome было закрыто до завершения входа в Яндекс Диск");
    }

    private Page ensurePage(boolean headless) {
        if (context == null) {
            playwright = Playwright.create();
            Path profile = ApplicationPaths.programDirectory().resolve("yandex-media-recovery-browser-profile");
            context = playwright.chromium().launchPersistentContext(profile,
                    new com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions()
                            .setChannel("chrome").setHeadless(debugMode ? false : headless));
        }
        if (page == null || page.isClosed()) {
            page = context.pages().isEmpty() ? context.newPage() : context.pages().getFirst();
        }
        return page;
    }

    /** Закрывает только управляемое окно и драйвер браузера. */
    @Override
    public void close() {
        BrowserContext closingContext = context;
        Playwright closingPlaywright = playwright;
        context = null;
        page = null;
        playwright = null;
        if (closingContext != null) {
            try {
                closingContext.close();
            } catch (PlaywrightException exception) {
                ApplicationLogger.error("Не удалось штатно закрыть окно Chrome", exception);
            }
        }
        if (closingPlaywright != null) {
            try {
                closingPlaywright.close();
            } catch (PlaywrightException exception) {
                ApplicationLogger.error("Не удалось штатно завершить драйвер Chrome", exception);
            }
        }
    }
}
