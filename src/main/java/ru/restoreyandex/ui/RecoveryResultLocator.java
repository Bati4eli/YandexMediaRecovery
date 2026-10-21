package ru.restoreyandex.ui;

import ru.restoreyandex.media.MediaExtensions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** Находит лучший существующий результат восстановления для выбора в Проводнике. */
final class RecoveryResultLocator {
    private RecoveryResultLocator() {
    }

    /**
     * Ищет оригинал, затем серверное, большое Explorer- и маленькое превью.
     * WEBPV_ ищется по имени без привязки к расширению, поскольку сервер
     * Яндекс Диска может вернуть JPEG, PNG или WebP.
     */
    static Optional<Path> find(Path source, Path recoveryFolder) {
        if (!Files.isDirectory(recoveryFolder) || source.getFileName() == null) {
            return Optional.empty();
        }
        Path original = recoveryFolder.resolve(source.getFileName());
        if (Files.isRegularFile(original)) {
            return Optional.of(original);
        }
        Optional<Path> webPreview = findWebPreview(source, recoveryFolder);
        if (webPreview.isPresent()) {
            return webPreview;
        }
        Path explorerPreview = recoveryFolder.resolve(MediaExtensions.previewName(source));
        if (Files.isRegularFile(explorerPreview)) {
            return Optional.of(explorerPreview);
        }
        Path miniPreview = recoveryFolder.resolve(MediaExtensions.miniPreviewName(source));
        return Files.isRegularFile(miniPreview) ? Optional.of(miniPreview) : Optional.empty();
    }

    private static Optional<Path> findWebPreview(Path source, Path recoveryFolder) {
        String previewName = MediaExtensions.webPreviewName(source);
        String prefix = previewName.substring(0, previewName.lastIndexOf('.') + 1);
        try (Stream<Path> files = Files.list(recoveryFolder)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER))
                    .findFirst();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }
}
