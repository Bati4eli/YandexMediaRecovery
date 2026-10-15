package ru.restoreyandex.preview;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import ru.restoreyandex.config.ApplicationPaths;
import ru.restoreyandex.logging.ApplicationLogger;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
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
    private static final long PREVIEW_WAIT_MILLIS = 30_000;
    private static final long MINIMUM_REQUEST_INTERVAL_MILLIS = 2_000;
    private final RequestRateLimiter requestRateLimiter = new RequestRateLimiter(MINIMUM_REQUEST_INTERVAL_MILLIS);
    private Playwright playwright;
    private BrowserContext context;
    private Page page;

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
     * Открывает режим просмотра одного файла и возвращает подписанный адрес
     * крупного изображения. Сам файл здесь не скачивается.
     */
    public synchronized Optional<URI> findLargestPreview(Path relativePath) {
        try {
            requestRateLimiter.awaitSlot();
            Page activePage = ensurePage(true);
            activePage.navigate(YandexDiskUrlBuilder.viewerUri(relativePath).toString());
            Locator image = activePage.locator("img.scalable-preview__image").first();
            image.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(PREVIEW_WAIT_MILLIS));
            Object source = image.evaluate("element => element.currentSrc || element.src");
            return source instanceof String value && !value.isBlank()
                    ? Optional.of(YandexDiskUrlBuilder.largestPreviewUri(URI.create(value)))
                    : Optional.empty();
        } catch (PlaywrightException | IllegalArgumentException exception) {
            ApplicationLogger.error("Не удалось получить серверное превью Яндекс Диска", exception);
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ApplicationLogger.error("Ожидание обращения к Яндекс Диску прервано", exception);
            return Optional.empty();
        }
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
                            .setChannel("chrome").setHeadless(headless));
        }
        if (page == null || page.isClosed()) {
            page = context.pages().isEmpty() ? context.newPage() : context.pages().getFirst();
        }
        return page;
    }

    /** Закрывает только управляемое окно и драйвер браузера. */
    @Override
    public synchronized void close() {
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
