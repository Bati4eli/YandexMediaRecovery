package ru.restoreyandex.preview;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет ограничения навигации и правило сохранения исходного ресурса Chrome. */
class YandexDiskBrowserSessionTest {
    @Test
    void usesBoundedDomContentLoadedNavigationForPreviewPage() {
        Page.NavigateOptions options = YandexDiskBrowserSession.previewNavigationOptions();

        assertEquals(WaitUntilState.DOMCONTENTLOADED, options.waitUntil);
        assertEquals(30_000D, options.timeout);
    }

    @Test
    void waitsForLoadedPreviewImageWithinTheSameTimeout() {
        Page.WaitForFunctionOptions options = YandexDiskBrowserSession.previewImageWaitOptions();

        assertEquals(30_000D, options.timeout);
    }

    @Test
    void startsBrowserPreviewRequestsNoMoreOftenThanEveryTwoHundredMilliseconds() {
        assertEquals(200L, YandexDiskBrowserSession.previewRequestIntervalMillis());
    }

    @Test
    void keepsTheServerImageFormatWhenWritingTheBrowserResource() {
        assertEquals("jpg", YandexDiskBrowserSession.previewExtension("image/jpeg; charset=binary"));
        assertEquals("webp", YandexDiskBrowserSession.previewExtension("image/webp"));
        assertEquals("bin", YandexDiskBrowserSession.previewExtension(null));
    }

    @Test
    void neverContainsScreenshotFallbackForWebPreview() {
        assertThrows(NoSuchMethodException.class,
                () -> YandexDiskBrowserSession.class.getDeclaredMethod("screenshotVisiblePreview", Page.class, Path.class));
    }

    @Test
    void matchesOnlyThePreviewResponseForTheCurrentFileEvenWhenTheNameIsEncoded() {
        String fileName = "2007 Мотовыставка.JPG";
        String url = "https://1.downloader.disk.yandex.ru/preview/token?filename="
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8);

        assertTrue(YandexDiskBrowserSession.isPreviewResponseUrl(url, fileName));
        assertTrue(!YandexDiskBrowserSession.isPreviewResponseUrl(url, "DSC00100.JPG"));
    }
}
