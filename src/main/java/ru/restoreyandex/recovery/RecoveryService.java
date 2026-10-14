package ru.restoreyandex.recovery;

import ru.restoreyandex.domain.RecoveryAction;
import ru.restoreyandex.domain.RecoveryEvent;
import ru.restoreyandex.domain.RecoveryOutcome;
import ru.restoreyandex.domain.RecoveryPlan;
import ru.restoreyandex.domain.RecoveryReport;
import ru.restoreyandex.media.DefaultMediaInspector;
import ru.restoreyandex.media.MediaInspector;
import ru.restoreyandex.preview.PreviewExtractor;
import ru.restoreyandex.preview.WindowsShellPreviewExtractor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Координирует рекурсивное планирование, проверку и безопасную запись восстановления. */
public final class RecoveryService {
    private final RecoveryPlanner planner;
    private final MediaInspector inspector;
    private final PreviewExtractor previewExtractor;
    private final SafeFileWriter fileWriter;

    /** Создаёт сервис восстановления для Windows. */
    public RecoveryService() {
        this(new RecoveryPlanner(), new DefaultMediaInspector(), new WindowsShellPreviewExtractor(), new SafeFileWriter());
    }

    RecoveryService(RecoveryPlanner planner, MediaInspector inspector, PreviewExtractor previewExtractor,
                    SafeFileWriter fileWriter) {
        this.planner = Objects.requireNonNull(planner);
        this.inspector = Objects.requireNonNull(inspector);
        this.previewExtractor = Objects.requireNonNull(previewExtractor);
        this.fileWriter = Objects.requireNonNull(fileWriter);
    }

    /**
     * Восстанавливает все обычные файлы внутри исходной папки. Ошибка одного
     * файла фиксируется в результате и не останавливает остальные файлы.
     */
    public RecoveryReport recover(Path sourceFolder, Path destinationFolder, RecoveryListener listener) throws IOException {
        Path sourceRoot = requireDirectory(sourceFolder, "Исходная папка");
        Path destinationRoot = destinationFolder.toAbsolutePath().normalize();
        Files.createDirectories(destinationRoot);
        if (!Files.isDirectory(destinationRoot)) {
            throw new IOException("Папка назначения недоступна: " + destinationRoot);
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            files = stream.filter(Files::isRegularFile).toList();
        }
        listener.onStarted(files.size());
        Counter counter = new Counter();
        int processed = 0;
        for (Path source : files) {
            processed++;
            RecoveryEvent event = recoverOne(source, sourceRoot, destinationRoot, processed, files.size(), counter);
            listener.onFileProcessed(event);
        }
        return counter.toReport();
    }

    private RecoveryEvent recoverOne(Path source, Path sourceRoot, Path destinationRoot, int processed,
                                     int total, Counter counter) {
        counter.scanned++;
        try {
            RecoveryPlan plan = planner.plan(source, sourceRoot, destinationRoot, inspector);
            return switch (plan.action()) {
                case COPY_ORIGINAL -> copy(plan, processed, total, counter);
                case WRITE_PREVIEW -> writePreview(plan, processed, total, counter);
                case SKIP_DESTINATION_EXISTS, SKIP_UNSUPPORTED -> skipped(plan, processed, total, counter);
            };
        } catch (Exception exception) {
            counter.failed++;
            return event(processed, total, source, RecoveryOutcome.NO_FILE, "Ошибка: " + conciseMessage(exception));
        }
    }

    private RecoveryEvent copy(RecoveryPlan plan, int processed, int total, Counter counter) throws IOException {
        try {
            fileWriter.copy(plan.source(), plan.destination());
            counter.copied++;
            return event(processed, total, plan.source(), RecoveryOutcome.COPIED, "Скопирован: " + plan.destination());
        } catch (FileAlreadyExistsException exception) {
            counter.skipped++;
            return event(processed, total, plan.source(), RecoveryOutcome.ALREADY_OK, "Уже существует: " + plan.destination());
        }
    }

    private RecoveryEvent writePreview(RecoveryPlan plan, int processed, int total, Counter counter) throws IOException {
        Optional<BufferedImage> preview = previewExtractor.extract(plan.source());
        if (preview.isEmpty()) {
            counter.previewsUnavailable++;
            return event(processed, total, plan.source(), RecoveryOutcome.NO_FILE,
                    "Превью недоступно в кэше Windows");
        }
        try {
            fileWriter.writePreview(preview.get(), plan.destination());
            counter.previewsSaved++;
            return event(processed, total, plan.source(), RecoveryOutcome.PREVIEW_SAVED,
                    "Превью сохранено: " + plan.destination());
        } catch (FileAlreadyExistsException exception) {
            counter.skipped++;
            return event(processed, total, plan.source(), RecoveryOutcome.ALREADY_OK, "Уже существует: " + plan.destination());
        }
    }

    private RecoveryEvent skipped(RecoveryPlan plan, int processed, int total, Counter counter) {
        counter.skipped++;
        String message = plan.action() == RecoveryAction.SKIP_DESTINATION_EXISTS
                ? "Пропуск: файл уже есть в папке назначения"
                : "Пропуск: не фото и не видео";
        RecoveryOutcome outcome = plan.action() == RecoveryAction.SKIP_DESTINATION_EXISTS
                ? RecoveryOutcome.ALREADY_OK : RecoveryOutcome.NO_FILE;
        return event(processed, total, plan.source(), outcome, message);
    }

    private RecoveryEvent event(int processed, int total, Path source, RecoveryOutcome outcome, String message) {
        return new RecoveryEvent(processed, total, source, outcome, message);
    }

    private Path requireDirectory(Path folder, String label) throws IOException {
        Path normalized = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IOException(label + " не существует или недоступна: " + normalized);
        }
        return normalized;
    }

    private String conciseMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static final class Counter {
        private int scanned;
        private int copied;
        private int previewsSaved;
        private int skipped;
        private int previewsUnavailable;
        private int failed;

        private RecoveryReport toReport() {
            return new RecoveryReport(scanned, copied, previewsSaved, skipped, previewsUnavailable, failed);
        }
    }
}
