package ru.restoreyandex.preview;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет сопоставление локальных путей с адресами веб-клиента Яндекс Диска. */
class YandexDiskUrlBuilderTest {
    @Test
    void buildsViewerUrlForRelativePathWithCyrillicAndSpaces() {
        URI uri = YandexDiskUrlBuilder.viewerUri(Path.of("MEDIA", "ALL_OUR_PHOTOS", "2005-2010", "2005-2007",
                "2006 дача", "PICT0641.JPG"));

        assertEquals("https://disk.yandex.ru/client/disk/MEDIA/ALL_OUR_PHOTOS/2005-2010/2005-2007/2006%20%D0%B4%D0%B0%D1%87%D0%B0"
                        + "?idApp=client&dialog=slider&idDialog=%2Fdisk%2FMEDIA%2FALL_OUR_PHOTOS%2F2005-2010%2F2005-2007%2F2006%20%D0%B4%D0%B0%D1%87%D0%B0%2FPICT0641.JPG",
                uri.toASCIIString());
    }

    @Test
    void buildsViewerUrlForFileInsideConfiguredYandexRoot() {
        URI uri = YandexDiskUrlBuilder.viewerUri(Path.of("C:/Users/user/Yandex.Disk"),
                Path.of("C:/Users/user/Yandex.Disk/MEDIA/2005 дача/photo.jpg"));

        assertEquals("https://disk.yandex.ru/client/disk/MEDIA/2005%20%D0%B4%D0%B0%D1%87%D0%B0"
                        + "?idApp=client&dialog=slider&idDialog=%2Fdisk%2FMEDIA%2F2005%20%D0%B4%D0%B0%D1%87%D0%B0%2Fphoto.jpg",
                uri.toASCIIString());
    }

    @Test
    void requestsLargestPreviewByReplacingOnlySizeParameter() {
        URI preview = URI.create("https://downloader.disk.yandex.ru/preview/token?filename=photo.jpg&size=974x1242&token=x");

        assertEquals("https://downloader.disk.yandex.ru/preview/token?filename=photo.jpg&size=4096x4096&token=x",
                YandexDiskUrlBuilder.largestPreviewUri(preview).toASCIIString());
    }
}
