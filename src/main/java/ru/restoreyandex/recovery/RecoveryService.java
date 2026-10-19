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
import ru.restoreyandex.media.TimedMediaInspector;
import ru.restoreyandex.preview.PreviewExtractor;
import ru.restoreyandex.preview.WindowsShellPreviewExtractor;
import ru.restoreyandex.preview.CloudPreviewExtractor;
import ru.restoreyandex.preview.YandexDiskBrowserPreviewExtractor;
import ru.restoreyandex.preview.BrowserPreview;
import ru.restoreyandex.logging.ApplicationLogger;

import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;

/** Координирует рекурсивное планирование, проверку и безопасную запись восстановления. */
public final class RecoveryService {
    private final RecoveryPlanner planner;
    private final MediaInspector inspector;
    private final PreviewExtractor largePreviewExtractor;
    private final CloudPreviewExtractor cloudPreviewExtractor;
    private final PreviewExtractor miniPreviewExtractor;
    private final SafeFileWriter fileWriter;
    private volatile ExecutorService activeRecoveryExecutor;
    private volatile TimedMediaInspector activeTimedInspector;
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();

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

    /** Выходит из аккаунта Яндекс Диска в изолированном браузерном профиле приложения. */
    public void logoutYandexDisk() throws IOException {
        cloudPreviewExtractor.logout();
    }

    /**
     * Немедленно останавливает текущий запуск: отменяет рабочие задачи,
     * прерывает ограниченную проверку оригиналов и закрывает Chrome.
     */
    public void cancel() {
        cancellationRequested.set(true);
        ExecutorService executor = activeRecoveryExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        TimedMediaInspector timedInspector = activeTimedInspector;
        if (timedInspector != null) {
            timedInspector.close();
        }
        cloudPreviewExtractor.close();
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
        cancellationRequested.set(false);
        Path sourceRoot = requireDirectory(sourceFolder, "Исходная папка");
        Path destinationRoot = destinationFolder.toAbsolutePath().normalize();
        Files.createDirectories(destinationRoot);
        if (!Files.isDirectory(destinationRoot)) {
            throw new IOException("Папка назначения недоступна: " + destinationRoot);
        }
        cloudPreviewExtractor.configureBrowserDebugMode(options.browserDebugMode());
        List<FolderFiles> folders = collectSelectedFolderFiles(sourceRoot, options);
        int totalFiles = folders.stream().mapToInt(folder -> folder.files().size()).sum();
        listener.onStarted(totalFiles);
        Counter counter = new Counter();
        TimedMediaInspector runInspector = new TimedMediaInspector(inspector,
                Duration.ofSeconds(8), options.parallelism());
        ExecutorService executor = Executors.newFixedThreadPool(options.parallelism());
        activeTimedInspector = runInspector;
        activeRecoveryExecutor = executor;
        try {
            int submitted = 0;
            int completed = 0;
            for (FolderFiles folder : folders) {
                if (cancellationRequested.get()) {
                    break;
                }
                FolderProgress progress = processFolder(folder, submitted, completed, totalFiles, sourceRoot,
                        destinationRoot, runInspector, options, counter, listener, executor);
                submitted = progress.submitted();
                completed = progress.completed();
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
            runInspector.close();
            if (activeRecoveryExecutor == executor) {
                activeRecoveryExecutor = null;
            }
            if (activeTimedInspector == runInspector) {
                activeTimedInspector = null;
            }
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
            ApplicationLogger.error("Ошибка восстановления файла: " + source, exception);
            return event(source, RecoveryOutcome.NO_FILE, "Ошибка: " + conciseMessage(exception));
        }
    }

    /**
     * Обходит только выбранные ветки. Исключённая папка не открывается вовсе;
     * однако путь к явно включённой вложенной папке остаётся доступен.
     */
    /**
     * Единственный основной обходчик строит упорядоченные батчи по папкам.
     * Рабочие потоки получают файлы только одной папки за раз, поэтому новая
     * папка не начинает восстановление, пока не завершилась предыдущая.
     */
    private List<FolderFiles> collectSelectedFolderFiles(Path sourceRoot, RecoveryOptions options) throws IOException {
        Map<Path, List<Path>> filesByFolder = new LinkedHashMap<>();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(sourceRoot) && isSyncDirectory(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(sourceRoot) && !options.folderSelection().shouldTraverse(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                filesByFolder.putIfAbsent(directory, new ArrayList<>());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && options.folderSelection().includes(file)) {
                    filesByFolder.computeIfAbsent(file.getParent(), ignored -> new ArrayList<>()).add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                ApplicationLogger.error("Не удалось прочитать путь при обходе: " + file, exception);
                return FileVisitResult.CONTINUE;
            }
        });
        return filesByFolder.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER)))
                .map(entry -> new FolderFiles(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER)).toList()))
                .toList();
    }

    /** Выполняет файлы одной папки параллельно и возвращает счётчики общего прогресса. */
    private FolderProgress processFolder(FolderFiles folder, int submitted, int completed, int totalFiles,
                                         Path sourceRoot, Path destinationRoot, MediaInspector runInspector,
                                         RecoveryOptions options, Counter counter, RecoveryListener listener,
                                         ExecutorService executor) throws InterruptedException, ExecutionException {
        ExecutorCompletionService<IndexedEvent> completion = new ExecutorCompletionService<>(executor);
        int nextSubmitted = submitted;
        for (Path source : folder.files()) {
            if (cancellationRequested.get()) {
                break;
            }
            int ordinal = ++nextSubmitted;
            try {
                completion.submit(() -> {
                    listener.onFileStarted(source, ordinal, totalFiles);
                    return new IndexedEvent(source, recoverOne(source, sourceRoot, destinationRoot,
                            runInspector, options, counter));
                });
            } catch (RejectedExecutionException exception) {
                if (!cancellationRequested.get()) {
                    throw exception;
                }
                break;
            }
        }
        int nextCompleted = completed;
        int submittedInFolder = nextSubmitted - submitted;
        for (int index = 0; index < submittedInFolder && !cancellationRequested.get();) {
            Future<IndexedEvent> completedFuture = completion.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (completedFuture == null) {
                continue;
            }
            IndexedEvent result = completedFuture.get();
            RecoveryEvent raw = result.event();
            listener.onFileProcessed(new RecoveryEvent(++nextCompleted, totalFiles, result.source(),
                    raw.outcome(), raw.message()));
            index++;
        }
        return new FolderProgress(nextSubmitted, nextCompleted);
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
        // С этого момента для файла уже нет доступного оригинала: планировщик
        // направляет сюда только недекодируемые исходники без копии-оригинала в назначении.
        counter.lostOriginals.incrementAndGet();
        Path explorerTarget = previewTarget(plan, MediaExtensions.previewName(plan.source()));
        Path miniTarget = previewTarget(plan, MediaExtensions.miniPreviewName(plan.source()));
        boolean usableExplorerPreview = hasUsableExplorerPreview(explorerTarget);
        boolean misclassifiedExplorerPreview = Files.isRegularFile(explorerTarget) && !usableExplorerPreview;
        if (findExistingWebPreview(plan.source(), plan.destination()).isPresent()) {
            return existingPreview(plan, RecoveryOutcome.WEB_PREVIEW_SAVED, options.deleteLowerQualityPreviews(), counter);
        }
        if (usableExplorerPreview && !options.overwritePreviews()) {
            return existingPreview(plan, RecoveryOutcome.EXPLORER_PREVIEW_SAVED,
                    options.deleteLowerQualityPreviews(), counter);
        }
        if (Files.exists(miniTarget) && !options.overwritePreviews()) {
            deleteMisclassifiedExplorerPreview(explorerTarget, misclassifiedExplorerPreview,
                    options.deleteLowerQualityPreviews(), counter);
            return existingPreview(plan, RecoveryOutcome.MINI_PREVIEW_SAVED,
                    options.deleteLowerQualityPreviews(), counter);
        }

        Optional<BufferedImage> explorerPreview = largePreviewExtractor.extract(plan.source());
        if (explorerPreview.isPresent()) {
            return savePreview(plan.source(), explorerPreview.get(), explorerTarget, options.overwritePreviews(),
                    RecoveryOutcome.EXPLORER_PREVIEW_SAVED, options.deleteLowerQualityPreviews(), counter);
        }

        Optional<BrowserPreview> webPreview = MediaExtensions.supportsBrowserPreview(plan.source())
                ? cloudPreviewExtractor.extract(sourceRoot.relativize(plan.source()))
                : Optional.empty();
        if (webPreview.isPresent()) {
            BrowserPreview resource = webPreview.orElseThrow();
            Path webTarget = previewTarget(plan, MediaExtensions.webPreviewName(plan.source(), resource.extension()));
            return saveBrowserPreview(plan.source(), resource, webTarget, options.overwritePreviews(),
                    RecoveryOutcome.WEB_PREVIEW_SAVED, options.deleteLowerQualityPreviews(), counter);
        }
        if (usableExplorerPreview) {
            return existingPreview(plan, RecoveryOutcome.EXPLORER_PREVIEW_SAVED,
                    options.deleteLowerQualityPreviews(), counter);
        }
        Optional<BufferedImage> miniPreview = miniPreviewExtractor.extract(plan.source());
        if (miniPreview.isPresent()) {
            deleteMisclassifiedExplorerPreview(explorerTarget, misclassifiedExplorerPreview,
                    options.deleteLowerQualityPreviews(), counter);
            return savePreview(plan.source(), miniPreview.get(), miniTarget, options.overwritePreviews(),
                    RecoveryOutcome.MINI_PREVIEW_SAVED, options.deleteLowerQualityPreviews(), counter);
        }
        counter.previewsUnavailable.incrementAndGet();
        return event(plan.source(), RecoveryOutcome.NO_FILE, "Большое и маленькое превью недоступны");
    }

    /**
     * Не позволяет старой обложке с ошибочным префиксом {@code PREW_} остановить
     * получение качественного превью. Оригинальные файлы этим методом никогда
     * не открываются и не удаляются.
     */
    private boolean hasUsableExplorerPreview(Path explorerTarget) {
        if (!Files.isRegularFile(explorerTarget)) {
            return false;
        }
        try {
            BufferedImage image = ImageIO.read(explorerTarget.toFile());
            boolean usable = image != null && WindowsShellPreviewExtractor.isLargePreview(image);
            if (!usable) {
                ApplicationLogger.info("Игнорирую маленький файл с ошибочным префиксом PREW_: " + explorerTarget);
            }
            return usable;
        } catch (IOException exception) {
            ApplicationLogger.error("Не удалось проверить размер PREW_-превью: " + explorerTarget, exception);
            return false;
        }
    }

    /** Удаляет только ошибочно названную маленькую картинку, когда ей уже найден корректный результат. */
    private void deleteMisclassifiedExplorerPreview(Path explorerTarget, boolean misclassified,
                                                     boolean enabled, Counter counter) {
        if (!enabled || !misclassified) {
            return;
        }
        try {
            if (Files.deleteIfExists(explorerTarget)) {
                counter.previewsDeleted.incrementAndGet();
                ApplicationLogger.info("Удалено маленькое превью с ошибочным префиксом PREW_: " + explorerTarget);
            }
        } catch (IOException exception) {
            ApplicationLogger.error("Не удалось удалить ошибочно названное PREW_-превью: " + explorerTarget, exception);
        }
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

    /** Сохраняет без преобразования сетевой файл, который уже скачала вкладка Chrome. */
    private RecoveryEvent saveBrowserPreview(Path source, BrowserPreview preview, Path destination,
                                             boolean overwritePreviews, RecoveryOutcome outcome,
                                             boolean deleteLowerQualityPreviews, Counter counter) throws IOException {
        try {
            fileWriter.writePreview(preview.bytes(), destination, overwritePreviews);
            counter.previewsSaved.incrementAndGet();
            deleteLowerQualityPreviews(source, destination.resolveSibling(source.getFileName()), outcome,
                    deleteLowerQualityPreviews, counter);
            return event(source, outcome, "Ресурс превью Chrome сохранён без перекодирования: " + destination);
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
            case COPIED -> List.of(previewTarget(originalTarget, MediaExtensions.previewName(source)),
                    previewTarget(originalTarget, MediaExtensions.miniPreviewName(source)));
            case WEB_PREVIEW_SAVED -> List.of(previewTarget(originalTarget, MediaExtensions.previewName(source)),
                    previewTarget(originalTarget, MediaExtensions.miniPreviewName(source)));
            case EXPLORER_PREVIEW_SAVED -> List.of(previewTarget(originalTarget, MediaExtensions.miniPreviewName(source)));
            case MINI_PREVIEW_SAVED, PREVIEWS_CLEANED, ALREADY_OK, NO_FILE -> List.of();
        };
        int deleted = 0;
        if (retained == RecoveryOutcome.COPIED) {
            deleted += deleteWebPreviews(source, originalTarget, counter);
        }
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

    /** Находит WEBPV_ с любым форматом: Яндекс может вернуть JPEG, PNG или WebP. */
    private Optional<Path> findExistingWebPreview(Path source, Path originalTarget) {
        Path directory = originalTarget.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return Optional.empty();
        }
        String prefix = webPreviewPrefix(source);
        try (Stream<Path> siblings = Files.list(directory)) {
            return siblings.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .findFirst();
        } catch (IOException exception) {
            ApplicationLogger.error("Не удалось проверить существующее WEBPV_-превью: " + originalTarget, exception);
            return Optional.empty();
        }
    }

    /** Удаляет только WEBPV_-файлы текущего оригинала, не затрагивая никакие другие данные. */
    private int deleteWebPreviews(Path source, Path originalTarget, Counter counter) {
        Path directory = originalTarget.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> siblings = Files.list(directory)) {
            for (Path preview : siblings.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(webPreviewPrefix(source))).toList()) {
                if (Files.deleteIfExists(preview)) {
                    deleted++;
                    counter.previewsDeleted.incrementAndGet();
                }
            }
        } catch (IOException exception) {
            ApplicationLogger.error("Не удалось удалить WEBPV_-превью: " + originalTarget, exception);
        }
        return deleted;
    }

    private String webPreviewPrefix(Path source) {
        String defaultName = MediaExtensions.webPreviewName(source);
        return defaultName.substring(0, defaultName.lastIndexOf('.') + 1);
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

    private boolean isSyncDirectory(Path directory) {
        Path name = directory.getFileName();
        return name != null && ".sync".equalsIgnoreCase(name.toString());
    }

    private String conciseMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record IndexedEvent(Path source, RecoveryEvent event) {
    }

    private record FolderFiles(Path folder, List<Path> files) {
    }

    private record FolderProgress(int submitted, int completed) {
    }

    private static final class Counter {
        private final AtomicInteger scanned = new AtomicInteger();
        private final AtomicInteger copied = new AtomicInteger();
        private final AtomicInteger previewsSaved = new AtomicInteger();
        private final AtomicInteger previewsDeleted = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();
        private final AtomicInteger previewsUnavailable = new AtomicInteger();
        private final AtomicInteger lostOriginals = new AtomicInteger();

        private RecoveryReport toReport() {
            return new RecoveryReport(scanned.get(), copied.get(), previewsSaved.get(), previewsDeleted.get(), skipped.get(),
                    previewsUnavailable.get(), lostOriginals.get());
        }
    }
}
