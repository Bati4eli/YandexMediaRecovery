package ru.restoreyandex.preview;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Последовательно получает превью сначала из веб-клиента Яндекс Диска, затем
 * из локального кэша Windows Shell.
 */
public final class CloudFirstPreviewExtractor implements PreviewExtractor {
    private final Path sourceRoot;
    private final CloudPreviewExtractor cloudExtractor;
    private final PreviewExtractor shellExtractor;

    /** Создаёт резервную цепочку без изменения исходного или целевого файла. */
    public CloudFirstPreviewExtractor(Path sourceRoot, CloudPreviewExtractor cloudExtractor,
                                      PreviewExtractor shellExtractor) {
        this.sourceRoot = Objects.requireNonNull(sourceRoot).toAbsolutePath().normalize();
        this.cloudExtractor = Objects.requireNonNull(cloudExtractor);
        this.shellExtractor = Objects.requireNonNull(shellExtractor);
    }

    /**
     * Возвращает первое доступное настоящее изображение. Веб-источник получает
     * только относительный путь, поэтому локальная структура каталогов не
     * раскрывается за пределами корня Диска.
     */
    @Override
    public Optional<BufferedImage> extract(Path file) {
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(sourceRoot)) {
            return shellExtractor.extract(file);
        }
        Optional<BufferedImage> cloudPreview = cloudExtractor.extract(sourceRoot.relativize(normalizedFile));
        return cloudPreview.isPresent() ? cloudPreview : shellExtractor.extract(file);
    }
}
