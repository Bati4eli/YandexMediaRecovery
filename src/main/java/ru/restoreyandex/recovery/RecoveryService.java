package ru.restoreyandex.recovery;

import ru.restoreyandex.domain.RecoveryAction;
import ru.restoreyandex.domain.RecoveryEvent;
import ru.restoreyandex.domain.RecoveryOptions;
import ru.restoreyandex.domain.RecoveryOutcome;
import ru.restoreyandex.domain.RecoveryPlan;
import ru.restoreyandex.domain.RecoveryReport;
import ru.restoreyandex.media.DefaultMediaInspector;
import ru.restoreyandex.media.MediaExtensions;
import ru.restoreyandex.media.MediaInspector;
import ru.restoreyandex.preview.PreviewExtractor;
import ru.restoreyandex.preview.WindowsShellPreviewExtractor;
import ru.restoreyandex.preview.CloudPreviewExtractor;
import ru.restoreyandex.preview.YandexDiskBrowserPreviewExtractor;
import ru.restoreyandex.logging.ApplicationLogger;

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
    private final PreviewExtractor largePreviewExtractor;
    private final CloudPreviewExtractor cloudPreviewExtractor;
    private final PreviewExtractor miniPreviewExtractor;
    private final SafeFileWriter fileWriter;

    /** Создаёт сервис восстановления для Windows. */
    public RecoveryService() {
        this(new RecoveryPlanner(), new DefaultMediaInspector(),
                new WindowsShellPreviewExtractor()::extractLarge, new YandexDiskBrowserPreviewExtractor(),
                new WindowsShellPreviewExtractor()::extractMini, new SafeFileWriter());
    }

    RecoveryService(RecoveryPlanner planner, MediaInspector inspector, PreviewExtractor previewExtractor,
                    SafeFileWriter fileWriter) {
        this(planner, inspector, previewExtractor, ignored -> Optional.empty(), ignored -> Optional.empty(), fileWriter);
    }

    RecoveryService(RecoveryPlanner planner, MediaInspector inspector, PreviewExtractor previewExtractor,
                    CloudPreviewExtractor cloudPreviewExtractor, SafeFileWriter fileWriter) {
        this(planner, inspector, previewExtractor, cloudPreviewExtractor, ignored -> Optional.empty(), fileWriter);
    }

    RecoveryService(RecoveryPlanner planner, MediaInspector inspector, PreviewExtractor largePreviewExtractor,
                    CloudPreviewExtractor cloudPreviewExtractor, PreviewExtractor miniPreviewExtractor,
                    SafeFileWriter fileWriter) {
        this.planner = Objects.requireNonNull(planner);
        this.inspector = Objects.requireNonNull(inspector);
        this.largePreviewExtractor = Objects.requireNonNull(largePreviewExtractor);
        this.cloudPreviewExtractor = Objects.requireNonNull(cloudPreviewExtractor);
        this.miniPreviewExtractor = Objects.requireNonNull(miniPreviewExtractor);
        this.fileWriter = Objects.requireNonNull(fileWriter);
    }

    /** Открывает Chrome с сохранённым профилем для ручного входа в Яндекс Диск. */
    public void openYandexDiskLogin() throws IOException {
        cloudPreviewExtractor.openLogin();
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
                            runInspector, options, counter));
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
            ApplicationLogger.error("Восстановление прервано", exception);
            throw new IOException("Восстановление прервано", exception);
        } catch (ExecutionException exception) {
            ApplicationLogger.error("Задача восстановления завершилась ошибкой",
                    exception.getCause() == null ? exception : exception.getCause());
            throw new IOException("Сбой задачи восстановления", exception.getCause());
        } finally {
            executor.shutdownNow();
        }
        return counter.toReport();
    }

    private RecoveryEvent recoverOne(Path source, Path sourceRoot, Path destinationRoot, MediaInspector runInspector,
                                     RecoveryOptions options, Counter counter) {
        counter.scanned.incrementAndGet();
        try {
            RecoveryPlan plan = planner.plan(source, sourceRoot, destinationRoot, runInspector,
                    options.overwritePreviews());
            return switch (plan.action()) {
                case COPY_ORIGINAL -> copy(plan, options.deleteLowerQualityPreviews(), counter);
                case WRITE_PREVIEW -> writePreview(plan, sourceRoot, options, counter);
                case SKIP_DESTINATION_EXISTS -> skipped(plan, counter);
                case SKIP_UNSUPPORTED -> skipped(plan, counter);
            };
        } catch (Exception exception) {
            counter.failed.incrementAndGet();
            ApplicationLogger.error("Ошибка восстановления файла: " + source, exception);
            return event(source, RecoveryOutcome.NO_FILE, "Ошибка: " + conciseMessage(exception));
        }
    }

    private RecoveryEvent copy(RecoveryPlan plan, boolean deleteLowerQualityPreviews, Counter counter) throws IOException {
        try {
            fileWriter.copy(plan.source(), plan.destination());
            counter.copied.incrementAndGet();
            deleteLowerQualityPreviews(plan.source(), plan.destination(), RecoveryOutcome.COPIED,
                    deleteLowerQualityPreviews, counter);
            return event(plan.source(), RecoveryOutcome.COPIED, "Скопирован: " + plan.destination());
        } catch (FileAlreadyExistsException exception) {
            return skipped(plan, counter);
        }
    }

    /**
     * Сначала получает большое превью из Windows Explorer, затем обращается к
     * браузеру Яндекс Диска и только последним шагом берёт маленькую обложку.
     * Порядок записи по качеству остаётся {@code WEBPV_}, {@code PREW_},
     * {@code MINIPV_}: появление более качественного результата удаляет худшие.
     */
    private RecoveryEvent writePreview(RecoveryPlan plan, Path sourceRoot,
                                       RecoveryOptions options, Counter counter) throws IOException {
        Path webTarget = previewTarget(plan, MediaExtensions.webPreviewName(plan.source()));
        Path explorerTarget = previewTarget(plan, MediaExtensions.previewName(plan.source()));
        Path miniTarget = previewTarget(plan, MediaExtensions.miniPreviewName(plan.source()));
        if (Files.exists(webTarget)) {
            return existingPreview(plan, RecoveryOutcome.WEB_PREVIEW_SAVED, options.deleteLowerQualityPreviews(), counter);
        }
        if (Files.exists(explorerTarget) && !options.overwritePreviews()) {
            return existingPreview(plan, RecoveryOutcome.EXPLORER_PREVIEW_SAVED,
                    options.deleteLowerQualityPreviews(), counter);
        }
        if (Files.exists(miniTarget) && !options.overwritePreviews()) {
            return existingPreview(plan, RecoveryOutcome.MINI_PREVIEW_SAVED,
                    options.deleteLowerQualityPreviews(), counter);
        }

        Optional<BufferedImage> explorerPreview = largePreviewExtractor.extract(plan.source());
        if (explorerPreview.isPresent()) {
            return savePreview(plan.source(), explorerPreview.get(), explorerTarget, options.overwritePreviews(),
                    RecoveryOutcome.EXPLORER_PREVIEW_SAVED, options.deleteLowerQualityPreviews(), counter);
        }

        Optional<BufferedImage> webPreview = cloudPreviewExtractor.extract(sourceRoot.relativize(plan.source()));
        if (webPreview.isPresent()) {
            return savePreview(plan.source(), webPreview.get(), webTarget, options.overwritePreviews(),
                    RecoveryOutcome.WEB_PREVIEW_SAVED, options.deleteLowerQualityPreviews(), counter);
        }
        if (Files.exists(explorerTarget)) {
            return existingPreview(plan, RecoveryOutcome.EXPLORER_PREVIEW_SAVED,
                    options.deleteLowerQualityPreviews(), counter);
        }
        Optional<BufferedImage> miniPreview = miniPreviewExtractor.extract(plan.source());
        if (miniPreview.isPresent()) {
            return savePreview(plan.source(), miniPreview.get(), miniTarget, options.overwritePreviews(),
                    RecoveryOutcome.MINI_PREVIEW_SAVED, options.deleteLowerQualityPreviews(), counter);
        }
        counter.previewsUnavailable.incrementAndGet();
        return event(plan.source(), RecoveryOutcome.NO_FILE, "Большое и маленькое превью недоступны");
    }

    private RecoveryEvent savePreview(Path source, BufferedImage preview, Path destination,
                                      boolean overwritePreviews, RecoveryOutcome outcome,
                                      boolean deleteLowerQualityPreviews, Counter counter) throws IOException {
        try {
            fileWriter.writePreview(preview, destination, overwritePreviews);
            counter.previewsSaved.incrementAndGet();
            deleteLowerQualityPreviews(source, destination.resolveSibling(source.getFileName()), outcome,
                    deleteLowerQualityPreviews, counter);
            return event(source, outcome,
                    "Превью сохранено: " + destination);
        } catch (FileAlreadyExistsException exception) {
            counter.skipped.incrementAndGet();
            ApplicationLogger.info("Превью уже существует и пропущено: " + destination);
            return event(source, RecoveryOutcome.ALREADY_OK, "Уже существует: " + destination);
        }
    }

    private RecoveryEvent existingPreview(RecoveryPlan plan, RecoveryOutcome retainedPreview,
                                          boolean deleteLowerQualityPreviews, Counter counter) {
        int deleted = deleteLowerQualityPreviews(plan.source(), plan.destination(), retainedPreview,
                deleteLowerQualityPreviews, counter);
        if (deleted > 0) {
            return event(plan.source(), RecoveryOutcome.PREVIEWS_CLEANED,
                    "Удалены менее качественные превью: " + deleted);
        }
        counter.skipped.incrementAndGet();
        return event(plan.source(), RecoveryOutcome.ALREADY_OK, "Результат уже существует");
    }

    private Path previewTarget(RecoveryPlan plan, String previewName) {
        return plan.destination().resolveSibling(previewName);
    }

    /** Удаляет только имена превью, которые находятся ниже сохранённого качества. */
    private int deleteLowerQualityPreviews(Path source, Path originalTarget, RecoveryOutcome retained,
                                           boolean enabled, Counter counter) {
        if (!enabled) {
            return 0;
        }
        List<Path> targets = switch (retained) {
            case COPIED -> List.of(previewTarget(originalTarget, MediaExtensions.webPreviewName(source)),
                    previewTarget(originalTarget, MediaExtensions.previewName(source)),
                    previewTarget(originalTarget, MediaExtensions.miniPreviewName(source)));
            case WEB_PREVIEW_SAVED -> List.of(previewTarget(originalTarget, MediaExtensions.previewName(source)),
                    previewTarget(originalTarget, MediaExtensions.miniPreviewName(source)));
            case EXPLORER_PREVIEW_SAVED -> List.of(previewTarget(originalTarget, MediaExtensions.miniPreviewName(source)));
            case MINI_PREVIEW_SAVED, PREVIEWS_CLEANED, ALREADY_OK, NO_FILE -> List.of();
        };
        int deleted = 0;
        for (Path target : targets) {
            try {
                if (Files.deleteIfExists(target)) {
                    deleted++;
                    counter.previewsDeleted.incrementAndGet();
                }
            } catch (IOException exception) {
                ApplicationLogger.error("Не удалось удалить менее качественное превью: " + target, exception);
            }
        }
        return deleted;
    }

    private Path previewTarget(Path originalTarget, String previewName) {
        return originalTarget.resolveSibling(previewName);
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
        private final AtomicInteger previewsDeleted = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();
        private final AtomicInteger previewsUnavailable = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        private RecoveryReport toReport() {
            return new RecoveryReport(scanned.get(), copied.get(), previewsSaved.get(), previewsDeleted.get(), skipped.get(),
                    previewsUnavailable.get(), failed.get());
        }
    }
}
