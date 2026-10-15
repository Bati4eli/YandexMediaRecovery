package ru.restoreyandex.preview;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет, что браузерный источник не создаёт второй HTTP-клиент. */
class YandexDiskBrowserPreviewExtractorTest {
    @Test
    void keepsOnlyTheBrowserSessionAsTheDownloadChannel() {
        Field[] fields = YandexDiskBrowserPreviewExtractor.class.getDeclaredFields();

        assertTrue(Arrays.stream(fields).noneMatch(field -> field.getType().getName().equals("java.net.http.HttpClient")));
    }
}
