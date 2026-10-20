package ru.restoreyandex.media;

import org.junit.jupiter.api.Test;
import ru.restoreyandex.domain.MediaType;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет распознавание форматов фото для запасного пути с превью. */
class MediaExtensionsTest {
    @Test
    void recognizesHeicAndWebpAsImages() {
        assertEquals(MediaType.IMAGE, MediaExtensions.typeOf(Path.of("phone-photo.HEIC")));
        assertEquals(MediaType.IMAGE, MediaExtensions.typeOf(Path.of("image.webp")));
    }
}
