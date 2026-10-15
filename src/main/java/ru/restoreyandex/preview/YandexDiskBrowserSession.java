package ru.restoreyandex.preview;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import ru.restoreyandex.config.ApplicationPaths;
import ru.restoreyandex.logging.ApplicationLogger;

import java.io.IOException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
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
    private static final String VISIBLE_PREVIEW_IMAGE_SELECTOR = PREVIEW_IMAGE_SELECTOR + ":visible";
    private static final long PREVIEW_WAIT_MILLIS = 30_000;
    private static final long MINIMUM_REQUEST_INTERVAL_MILLIS = 2_000;
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
     * декодированное изображение, затем читается тело того же сетевого ответа
     * через Playwright. Отдельный {@code HttpClient} здесь запрещён: ссылка
     * предпросмотра может быть привязана к сессии Chrome и второй запрос
     * получает HTTP 403. Скрытые изображения предыдущего слайда исключаются
     * селектором {@code :visible}.</p>
     *
     * <p>Если Chrome взял ресурс из кэша и не сообщил Playwright новый ответ,
     * метод сохраняет PNG-снимок уже показанного элемента. Это запасной путь
     * без сетевого запроса; его разрешение может быть ниже исходного ответа.</p>
     *
     * @param relativePath путь к файлу относительно корня Яндекс Диска
     * @return декодированное большое превью или пустой результат при ошибке
     */
    public synchronized Optional<BufferedImage> extractLargestPreview(Path relativePath) {
        Page activePage = null;
        try {
            requestRateLimiter.awaitSlot();
            Page browserPage = ensurePage(true);
            activePage = browserPage;
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
            Optional<BufferedImage> preview = decodeBrowserPreviewBytes(previewResponse.body());
            if (preview.isEmpty()) {
                ApplicationLogger.info("Браузер вернул не изображение: " + relativePath);
                return screenshotVisiblePreview(browserPage, relativePath);
            }
            BufferedImage image = preview.orElseThrow();
            ApplicationLogger.info("Большое превью получено из Chrome: %dx%d"
                    .formatted(image.getWidth(), image.getHeight()));
            return preview;
        } catch (PlaywrightException | IllegalArgumentException exception) {
            ApplicationLogger.error("Не удалось получить большое превью из Chrome", exception);
            return screenshotVisiblePreview(activePage, relativePath);
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
        visiblePreviewImage(activePage).waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(PREVIEW_WAIT_MILLIS));
    }

    /** Находит единственную открытую фотографию; старые слайды просмотрщика остаются в DOM, но скрыты. */
    private Locator visiblePreviewImage(Page activePage) {
        return activePage.locator(VISIBLE_PREVIEW_IMAGE_SELECTOR).last();
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

    /** Декодирует тело ответа Chrome; метод выделен для проверки без настоящей авторизации в Яндекс Диске. */
    static Optional<BufferedImage> decodeBrowserPreviewBytes(byte[] bytes) {
        try {
            return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(bytes)));
        } catch (IOException exception) {
            ApplicationLogger.error("Не удалось декодировать байты превью, полученные Chrome", exception);
            return Optional.empty();
        }
    }

    /**
     * Создаёт PNG-снимок уже отображённого элемента только при отсутствии
     * доступного сетевого ответа Chrome (например, при чтении из кэша).
     */
    private Optional<BufferedImage> screenshotVisiblePreview(Page activePage, Path relativePath) {
        if (activePage == null || activePage.isClosed()) {
            return Optional.empty();
        }
        try {
            Locator imageElement = visiblePreviewImage(activePage);
            Object source = imageElement.evaluate("image => image.currentSrc || image.src");
            if (!(source instanceof String imageUrl)
                    || !isPreviewResponseUrl(imageUrl, relativePath.getFileName().toString())) {
                ApplicationLogger.info("Chrome не показал превью нужного файла; снимок не сохраняется: " + relativePath);
                return Optional.empty();
            }
            Optional<BufferedImage> screenshot = decodeBrowserPreviewBytes(imageElement.screenshot());
            if (screenshot.isPresent()) {
                BufferedImage image = screenshot.orElseThrow();
                ApplicationLogger.info("Большое превью сохранено снимком из Chrome: %dx%d"
                        .formatted(image.getWidth(), image.getHeight()));
            }
            return screenshot;
        } catch (PlaywrightException exception) {
            ApplicationLogger.error("Не удалось снять уже открытое превью Chrome: " + relativePath, exception);
            return Optional.empty();
        }
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
