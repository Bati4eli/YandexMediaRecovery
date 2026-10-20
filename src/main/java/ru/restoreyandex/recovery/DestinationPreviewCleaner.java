package ru.restoreyandex.recovery;

import ru.restoreyandex.logging.ApplicationLogger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Рекурсивно удаляет только файлы предпросмотра, созданные приложением в папке восстановления.
 *
 * <p>Обход не следует символьным ссылкам, поэтому действие не выходит за пределы выбранной папки.
 * Ошибка доступа к одному файлу записывается в журнал и не останавливает очистку остальных.</p>
 */
public final class DestinationPreviewCleaner {
    private static final Set<String> WEB_PREVIEW_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif", "bin");

    /**
     * Удаляет все распознанные файлы превью из папки назначения и её подпапок.
     *
     * @param destinationRoot существующая папка назначения
     * @return количество удалённых файлов и ошибок доступа
     * @throws IOException если папка назначения не существует или не является каталогом
     */
    public PreviewCleanupReport deletePreviews(Path destinationRoot) throws IOException {
        if (!Files.isDirectory(destinationRoot)) {
            throw new IOException("Папка назначения недоступна: " + destinationRoot);
        }
        AtomicInteger deleted = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Files.walkFileTree(destinationRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile() || !isApplicationPreview(file)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    if (Files.deleteIfExists(file)) {
                        deleted.incrementAndGet();
                    }
                } catch (IOException exception) {
                    failed.incrementAndGet();
                    ApplicationLogger.error("Не удалось удалить превью: " + file, exception);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                failed.incrementAndGet();
                ApplicationLogger.error("Не удалось проверить файл при очистке превью: " + file, exception);
                return FileVisitResult.CONTINUE;
            }
        });
        return new PreviewCleanupReport(deleted.get(), failed.get());
    }

    /** Определяет, является ли файл одним из форматов превью, которые создаёт приложение. */
    private boolean isApplicationPreview(Path file) {
        String name = file.getFileName().toString();
        String normalized = name.toLowerCase(Locale.ROOT);
        return hasExtension(normalized, "prew_", Set.of("png"))
                || hasExtension(normalized, "minipv_", Set.of("png"))
                || hasExtension(normalized, "webpv_", WEB_PREVIEW_EXTENSIONS);
    }

    private boolean hasExtension(String name, String prefix, Set<String> extensions) {
        if (!name.startsWith(prefix)) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        return dot > prefix.length() && dot < name.length() - 1
                && extensions.contains(name.substring(dot + 1));
    }
}
