package ru.restoreyandex.recovery;

import ru.restoreyandex.domain.RecoveryAction;
import ru.restoreyandex.domain.RecoveryEvent;
import ru.restoreyandex.domain.RecoveryOptions;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.stream.Stream;

/** Координирует рекурсивное планирование, проверку и безопасную запись восстановления. */
public final class RecoveryService {
    private final RecoveryPlanner planner;
    private final MediaInspector inspector;
    private final PreviewExtractor previewExtractor;
    private final SafeFileWriter fileWriter;

    /** Создаёт сервис восстановления для Windows. */
    public RecoveryService() {
        this(new RecoveryPlanner(), new DefaultMediaInspector(),
                new WindowsShellPreviewExtractor(), new SafeFileWriter());
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
        return recover(sourceFolder, destinationFolder, RecoveryOptions.defaults(), listener);
    }

    /**
     * Восстанавливает файлы в заданное число потоков. Все результаты пишутся
     * безопасно, а исходные файлы и существующие оригиналы не изменяются.
     */
    public RecoveryReport recover(Path sourceFolder, Path destinationFolder, RecoveryOptions options,
                                  RecoveryListener listener) throws IOException {
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
        MediaInspector runInspector = new ru.restoreyandex.media.TimedMediaInspector(inspector,
                Duration.ofSeconds(8), options.parallelism());
        ExecutorService executor = Executors.newFixedThreadPool(options.parallelism());
        try {
            ExecutorCompletionService<IndexedEvent> completion = new ExecutorCompletionService<>(executor);
            for (int index = 0; index < files.size(); index++) {
                Path source = files.get(index);
                int ordinal = index + 1;
                completion.submit(() -> {
                    listener.onFileStarted(source, ordinal, files.size());
                    return new IndexedEvent(source, recoverOne(source, sourceRoot, destinationRoot,
                            runInspector, options.overwritePreviews(), counter));
                });
            }
            for (int completed = 1; completed <= files.size(); completed++) {
                Future<IndexedEvent> future = completion.take();
                IndexedEvent result = future.get();
                RecoveryEvent raw = result.event();
                listener.onFileProcessed(new RecoveryEvent(completed, files.size(), result.source(),
                        raw.outcome(), raw.message()));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Восстановление прервано", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Сбой задачи восстановления", exception.getCause());
        } finally {
            executor.shutdownNow();
        }
        return counter.toReport();
    }

    private RecoveryEvent recoverOne(Path source, Path sourceRoot, Path destinationRoot, MediaInspector runInspector,
                                     boolean overwritePreviews, Counter counter) {
        counter.scanned.incrementAndGet();
        try {
            RecoveryPlan plan = planner.plan(source, sourceRoot, destinationRoot, runInspector, overwritePreviews);
            return switch (plan.action()) {
                case COPY_ORIGINAL -> copy(plan, counter);
                case WRITE_PREVIEW -> writePreview(plan, overwritePreviews, counter);
                case SKIP_DESTINATION_EXISTS, SKIP_UNSUPPORTED -> skipped(plan, counter);
            };
        } catch (Exception exception) {
            counter.failed.incrementAndGet();
            return event(source, RecoveryOutcome.NO_FILE, "Ошибка: " + conciseMessage(exception));
        }
    }

    private RecoveryEvent copy(RecoveryPlan plan, Counter counter) throws IOException {
        try {
            fileWriter.copy(plan.source(), plan.destination());
            counter.copied.incrementAndGet();
            return event(plan.source(), RecoveryOutcome.COPIED, "Скопирован: " + plan.destination());
        } catch (FileAlreadyExistsException exception) {
            counter.skipped.incrementAndGet();
            return event(plan.source(), RecoveryOutcome.ALREADY_OK, "Уже существует: " + plan.destination());
        }
    }

    private RecoveryEvent writePreview(RecoveryPlan plan, boolean overwritePreviews, Counter counter) throws IOException {
        Optional<BufferedImage> preview = previewExtractor.extract(plan.source());
        if (preview.isEmpty()) {
            counter.previewsUnavailable.incrementAndGet();
            return event(plan.source(), RecoveryOutcome.NO_FILE,
                    "Превью недоступно в кэше Windows");
        }
        try {
            fileWriter.writePreview(preview.get(), plan.destination(), overwritePreviews);
            counter.previewsSaved.incrementAndGet();
            return event(plan.source(), RecoveryOutcome.PREVIEW_SAVED,
                    "Превью сохранено: " + plan.destination());
        } catch (FileAlreadyExistsException exception) {
            counter.skipped.incrementAndGet();
            return event(plan.source(), RecoveryOutcome.ALREADY_OK, "Уже существует: " + plan.destination());
        }
    }

    private RecoveryEvent skipped(RecoveryPlan plan, Counter counter) {
        counter.skipped.incrementAndGet();
        String message = plan.action() == RecoveryAction.SKIP_DESTINATION_EXISTS
                ? "Пропуск: файл уже есть в папке назначения"
                : "Пропуск: не фото и не видео";
        RecoveryOutcome outcome = plan.action() == RecoveryAction.SKIP_DESTINATION_EXISTS
                ? RecoveryOutcome.ALREADY_OK : RecoveryOutcome.NO_FILE;
        return event(plan.source(), outcome, message);
    }

    private RecoveryEvent event(Path source, RecoveryOutcome outcome, String message) {
        return new RecoveryEvent(0, 0, source, outcome, message);
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

    private record IndexedEvent(Path source, RecoveryEvent event) {
    }

    private static final class Counter {
        private final AtomicInteger scanned = new AtomicInteger();
        private final AtomicInteger copied = new AtomicInteger();
        private final AtomicInteger previewsSaved = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();
        private final AtomicInteger previewsUnavailable = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        private RecoveryReport toReport() {
            return new RecoveryReport(scanned.get(), copied.get(), previewsSaved.get(), skipped.get(),
                    previewsUnavailable.get(), failed.get());
        }
    }
}
