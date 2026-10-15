package ru.restoreyandex.preview;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет ограничения навигации, без запуска настоящего браузера. */
class YandexDiskBrowserSessionTest {
    @Test
    void usesBoundedDomContentLoadedNavigationForPreviewPage() {
        Page.NavigateOptions options = YandexDiskBrowserSession.previewNavigationOptions();

        assertEquals(WaitUntilState.DOMCONTENTLOADED, options.waitUntil);
        assertEquals(30_000D, options.timeout);
    }

    @Test
    void waitsForDecodedPreviewImageWithinTheSameTimeout() {
        Page.WaitForFunctionOptions options = YandexDiskBrowserSession.previewImageWaitOptions();

        assertEquals(30_000D, options.timeout);
    }

    @Test
    void decodesTheBytesReturnedByChromeWithoutASecondHttpRequest() throws IOException {
        BufferedImage expected = new BufferedImage(37, 19, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(expected, "png", bytes);

        BufferedImage actual = YandexDiskBrowserSession.decodeBrowserPreviewBytes(bytes.toByteArray()).orElseThrow();

        assertEquals(37, actual.getWidth());
        assertEquals(19, actual.getHeight());
    }

    @Test
    void rejectsNonImageBytesReturnedByBrowser() {
        assertTrue(YandexDiskBrowserSession.decodeBrowserPreviewBytes(new byte[]{1, 2, 3}).isEmpty());
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
