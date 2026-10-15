package ru.restoreyandex.preview;

import java.util.Locale;
import java.util.Objects;

/**
 * Исходный файл предпросмотра, уже загруженный вкладкой Chrome.
 *
 * <p>Байты не декодируются и не перекодируются: результат записывается в
 * резервную копию в точности в том виде, в каком его отдал Яндекс Диск.</p>
 */
public record BrowserPreview(byte[] bytes, String extension) {
    /** Создаёт неизменяемое описание сетевого изображения. */
    public BrowserPreview {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Предпросмотр не должен быть пустым");
        }
        Objects.requireNonNull(extension, "extension");
        extension = normalizeExtension(extension);
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    private static String normalizeExtension(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.matches("[a-z0-9]+")) {
            throw new IllegalArgumentException("Недопустимое расширение предпросмотра: " + value);
        }
        return normalized;
    }
}
