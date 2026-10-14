package ru.restoreyandex.preview;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Optional;

/** Извлекает настоящее превью, не принимая стандартную иконку файла за фотографию. */
@FunctionalInterface
public interface PreviewExtractor {
    /** Возвращает доступное превью или пустой результат, если его нельзя восстановить. */
    Optional<BufferedImage> extract(Path file);
}
