package ru.restoreyandex.media;

import ru.restoreyandex.domain.MediaType;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Классифицирует медиа по имени файла перед безопасной попыткой декодирования. */
public final class MediaExtensions {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "wbmp", "tif", "tiff");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "m4v", "mov", "avi", "mkv", "3gp", "webm");

    private MediaExtensions() {
    }

    /** Определяет тип медиа по расширению имени файла. */
    public static MediaType typeOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length() - 1) {
            return MediaType.UNSUPPORTED;
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return MediaType.IMAGE;
        }
        return VIDEO_EXTENSIONS.contains(extension) ? MediaType.VIDEO : MediaType.UNSUPPORTED;
    }

    /** Строит имя PNG-превью с обязательным префиксом {@code PREW_}. */
    public static String previewName(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String baseName = dot > 0 ? name.substring(0, dot) : name;
        return "PREW_" + baseName + ".png";
    }
}
