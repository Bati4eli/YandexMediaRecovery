package ru.restoreyandex.preview;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Строит адреса веб-клиента Яндекс Диска из относительных путей Windows. */
public final class YandexDiskUrlBuilder {
    private static final String CLIENT_ROOT = "https://disk.yandex.ru/client/disk";
    private static final String MAXIMUM_PREVIEW_SIZE = "4096x4096";

    private YandexDiskUrlBuilder() {
    }

    /**
     * Строит URL режима просмотра одного файла. Каждый сегмент кодируется
     * отдельно, поэтому пробелы и кириллица не искажают структуру папок.
     */
    public static URI viewerUri(Path relativePath) {
        Objects.requireNonNull(relativePath);
        if (relativePath.isAbsolute()) {
            throw new IllegalArgumentException("Ожидается относительный путь: " + relativePath);
        }
        List<String> segments = new ArrayList<>();
        for (Path segment : relativePath) {
            String value = segment.toString();
            if (value.equals(".") || value.equals("..") || value.isBlank()) {
                throw new IllegalArgumentException("Недопустимый сегмент пути: " + relativePath);
            }
            segments.add(value);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Путь к файлу не задан");
        }
        String diskPath = "/disk/" + String.join("/", segments);
        String encodedFolderPath = segments.subList(0, segments.size() - 1).stream()
                .map(YandexDiskUrlBuilder::encode).reduce((left, right) -> left + "/" + right).orElse("");
        return URI.create(CLIENT_ROOT + (encodedFolderPath.isEmpty() ? "" : "/" + encodedFolderPath)
                + "?idApp=client&dialog=slider&idDialog=" + encode(diskPath));
    }

    /** Заменяет размер в уже подписанном URL превью, не меняя остальные параметры. */
    public static URI largestPreviewUri(URI previewUri) {
        Objects.requireNonNull(previewUri);
        String value = previewUri.toASCIIString();
        String updated = value.matches(".*[?&]size=[^&]*.*")
                ? value.replaceFirst("([?&]size=)[^&]*", "$1" + MAXIMUM_PREVIEW_SIZE)
                : value + (value.contains("?") ? "&" : "?") + "size=" + MAXIMUM_PREVIEW_SIZE;
        return URI.create(updated);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
