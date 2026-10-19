package ru.restoreyandex.recovery;

import ru.restoreyandex.media.DefaultMediaInspector;
import ru.restoreyandex.media.MediaInspector;
import ru.restoreyandex.domain.MediaInspection;
import ru.restoreyandex.domain.MediaType;
import ru.restoreyandex.domain.RecoveryOptions;
import ru.restoreyandex.domain.RecoveryReport;
import ru.restoreyandex.domain.FolderSelection;
import ru.restoreyandex.domain.FolderSelectionRule;
import ru.restoreyandex.preview.CloudPreviewExtractor;
import ru.restoreyandex.preview.BrowserPreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesOnlyAValidatedOriginal() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source/album"));
        Path destination = temporaryDirectory.resolve("destination");
        writeImage(source.resolve("photo.png"), Color.BLUE);

        RecoveryReport report = serviceWithNoPreview().recover(source.getParent(), destination, new RecoveryListener() { });

        assertEquals(1, report.copied());
        assertNotNull(ImageIO.read(destination.resolve("album/photo.png").toFile()));
    }

    @Test
    void recoversOnlyFilesFromSelectedFolderBranchesWithPathsRelativeToDiskRoot() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path included = Files.createDirectories(source.resolve("family/2011"));
        Path excluded = Files.createDirectories(source.resolve("work"));
        Path destination = temporaryDirectory.resolve("destination");
        writeImage(included.resolve("photo.png"), Color.BLUE);
        writeImage(excluded.resolve("report.png"), Color.RED);
        RecoveryOptions options = new RecoveryOptions(false, true, 1,
                new FolderSelection(List.of(new FolderSelectionRule(excluded, false))));

        RecoveryReport report = serviceWithNoPreview().recover(source, destination, options, new RecoveryListener() { });

        assertEquals(1, report.copied());
        assertNotNull(ImageIO.read(destination.resolve("family/2011/photo.png").toFile()));
        assertEquals(false, Files.exists(destination.resolve("work/report.png")));
    }

    @Test
    void skipsHiddenSyncFolderDuringRecovery() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path visible = Files.createDirectories(source.resolve("family"));
        Path sync = Files.createDirectories(source.resolve(".sync/cache"));
        Path destination = temporaryDirectory.resolve("destination");
        writeImage(visible.resolve("photo.png"), Color.BLUE);
        writeImage(sync.resolve("internal.png"), Color.RED);

        RecoveryReport report = serviceWithNoPreview().recover(source, destination, new RecoveryListener() { });

        assertEquals(1, report.scanned());
        assertNotNull(ImageIO.read(destination.resolve("family/photo.png").toFile()));
        assertEquals(false, Files.exists(destination.resolve(".sync/cache/internal.png")));
    }

    @Test
    void neverReplacesAnExistingDestinationFile() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination"));
        writeImage(source.resolve("photo.png"), Color.BLUE);
        writeImage(destination.resolve("photo.png"), Color.RED);

        RecoveryReport report = serviceWithNoPreview().recover(source, destination, new RecoveryListener() { });

        assertEquals(1, report.skipped());
        assertEquals(Color.RED.getRGB(), ImageIO.read(destination.resolve("photo.png").toFile()).getRGB(0, 0));
    }

    @Test
    void reportsProgressForEveryFileThatIsAlreadyRecovered() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination"));
        writeImage(source.resolve("one.png"), Color.BLUE);
        writeImage(source.resolve("two.png"), Color.BLUE);
        writeImage(destination.resolve("one.png"), Color.RED);
        writeImage(destination.resolve("two.png"), Color.RED);
        List<ru.restoreyandex.domain.RecoveryEvent> processed = new ArrayList<>();

        serviceWithNoPreview().recover(source, destination, new RecoveryListener() {
            @Override
            public void onFileProcessed(ru.restoreyandex.domain.RecoveryEvent event) {
                processed.add(event);
            }
        });

        assertEquals(2, processed.size());
        assertEquals(1, processed.get(0).processed());
        assertEquals(2, processed.get(1).processed());
        assertEquals(2, processed.get(1).total());
    }

    @Test
    void skipsDestinationOriginalWithoutChangingAnyNeighboringPreview() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Files.writeString(source.resolve("photo.jpg"), "cloud placeholder");
        writeImage(destination.resolve("photo.jpg"), Color.RED);
        writeImage(destination.resolve("PREW_photo.png"), Color.BLUE);
        Files.writeString(destination.resolve("notes.txt"), "keep me");

        serviceWithNoPreview().recover(source, destination, new RecoveryListener() { });

        assertEquals(Color.RED.getRGB(), ImageIO.read(destination.resolve("photo.jpg").toFile()).getRGB(0, 0));
        assertEquals(true, Files.exists(destination.resolve("PREW_photo.png")));
        assertEquals("keep me", Files.readString(destination.resolve("notes.txt")));
    }

    @Test
    void savesShellPreviewWithPrewPrefixForAPlaceholder() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source/2011"));
        Path destination = temporaryDirectory.resolve("destination");
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        BufferedImage preview = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        preview.setRGB(0, 0, Color.GREEN.getRGB());
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.of(preview), new SafeFileWriter());

        RecoveryReport report = service.recover(source.getParent(), destination, new RecoveryListener() { });

        assertEquals(1, report.previewsSaved());
        assertEquals(Color.GREEN.getRGB(), ImageIO.read(destination.resolve("2011/PREW_lost.png").toFile()).getRGB(0, 0));
    }

    @Test
    void prefersLargeExplorerPreviewBeforeBrowserPreview() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source/2011"));
        Path destination = temporaryDirectory.resolve("destination");
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        BufferedImage shellPreview = image(Color.GREEN);
        CloudPreviewExtractor cloud = relativePath -> {
            assertEquals(Path.of("2011/lost.jpg"), relativePath);
            return Optional.of(browserPreview(Color.BLUE));
        };
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.of(shellPreview), cloud, new SafeFileWriter());

        service.recover(source.getParent(), destination, new RecoveryListener() { });

        assertEquals(Color.GREEN.getRGB(), ImageIO.read(destination.resolve("2011/PREW_lost.png").toFile()).getRGB(0, 0));
    }

    @Test
    void savesBrowserPreviewWhenLargeExplorerPreviewIsUnavailable() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source/2011"));
        Path destination = temporaryDirectory.resolve("destination");
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), ignored -> Optional.of(browserPreview(Color.BLUE)), new SafeFileWriter());

        service.recover(source.getParent(), destination, new RecoveryListener() { });

        assertEquals(Color.BLUE.getRGB(), ImageIO.read(destination.resolve("2011/WEBPV_lost.png").toFile()).getRGB(0, 0));
    }

    @Test
    void writesTheExactBrowserResourceWithoutScreenshotOrPngReencoding() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source/2011"));
        Path destination = temporaryDirectory.resolve("destination");
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        byte[] resourceBytes = {10, 20, 30, 40, 50};
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), ignored -> Optional.of(new BrowserPreview(resourceBytes, "jpg")),
                new SafeFileWriter());

        service.recover(source.getParent(), destination, new RecoveryListener() { });

        assertArrayEquals(resourceBytes, Files.readAllBytes(destination.resolve("2011/WEBPV_lost.jpg")));
        assertFalse(Files.exists(destination.resolve("2011/WEBPV_lost.png")));
    }

    @Test
    void doesNotTreatTinyIncorrectlyNamedPrewAsAHighQualityPreview() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        writeSizedImage(destination.resolve("PREW_lost.png"), 160, 120, Color.RED);
        AtomicInteger browserRequests = new AtomicInteger();
        CloudPreviewExtractor cloud = relativePath -> {
            browserRequests.incrementAndGet();
            return Optional.of(browserPreview(Color.BLUE));
        };
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), cloud, new SafeFileWriter());

        service.recover(source, destination, new RecoveryListener() { });

        assertEquals(1, browserRequests.get());
        assertNotNull(ImageIO.read(destination.resolve("WEBPV_lost.png").toFile()));
        assertFalse(Files.exists(destination.resolve("PREW_lost.png")));
    }

    @Test
    void replacesTinyIncorrectlyNamedPrewWithMiniPreviewWhenNoLargeSourceIsAvailable() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        writeSizedImage(destination.resolve("PREW_lost.png"), 160, 120, Color.RED);
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), ignored -> Optional.empty(),
                ignored -> Optional.of(image(Color.MAGENTA)), new SafeFileWriter());

        service.recover(source, destination, new RecoveryListener() { });

        assertNotNull(ImageIO.read(destination.resolve("MINIPV_lost.png").toFile()));
        assertFalse(Files.exists(destination.resolve("PREW_lost.png")));
    }

    @Test
    void savesMiniPreviewWhenLargeExplorerAndBrowserPreviewsAreUnavailable() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = temporaryDirectory.resolve("destination");
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), ignored -> Optional.empty(),
                ignored -> Optional.of(image(Color.MAGENTA)), new SafeFileWriter());

        RecoveryReport report = service.recover(source, destination, new RecoveryListener() { });

        assertEquals(Color.MAGENTA.getRGB(), ImageIO.read(destination.resolve("MINIPV_lost.png").toFile()).getRGB(0, 0));
        assertEquals(1, report.lostOriginals());
    }

    @Test
    void doesNotRequestBrowserPreviewForVideoFiles() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = temporaryDirectory.resolve("destination");
        Files.writeString(source.resolve("movie.mp4"), "unavailable video");
        AtomicInteger browserRequests = new AtomicInteger();
        AtomicInteger miniPreviewRequests = new AtomicInteger();
        MediaInspector unavailableVideo = ignored -> MediaInspection.unreadable(MediaType.VIDEO);
        CloudPreviewExtractor browser = ignored -> {
            browserRequests.incrementAndGet();
            return Optional.of(browserPreview(Color.BLUE));
        };
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), unavailableVideo,
                ignored -> Optional.empty(), browser, ignored -> {
                    miniPreviewRequests.incrementAndGet();
                    return Optional.empty();
                }, new SafeFileWriter());

        service.recover(source, destination, new RecoveryListener() { });

        assertEquals(0, browserRequests.get());
        assertEquals(1, miniPreviewRequests.get());
    }

    @Test
    void countsLostOriginalWhenNoPreviewCouldBeRecovered() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = temporaryDirectory.resolve("destination");
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), ignored -> Optional.empty(),
                ignored -> Optional.empty(), new SafeFileWriter());

        RecoveryReport report = service.recover(source, destination, new RecoveryListener() { });

        assertEquals(1, report.lostOriginals());
        assertTrue(report.toDisplayString().contains("Утеряно оригиналов: 1"));
    }

    @Test
    void doesNotCountUnsupportedFileAsLostOriginal() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Files.writeString(source.resolve("note.txt"), "not media");

        RecoveryReport report = serviceWithNoPreview().recover(source, temporaryDirectory.resolve("destination"),
                new RecoveryListener() { });

        assertEquals(0, report.lostOriginals());
    }

    @Test
    void doesNotCountLostOriginalWhenItAlreadyExistsInRecoveryFolder() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        Files.writeString(destination.resolve("lost.jpg"), "protected recovered original");

        RecoveryReport report = serviceWithNoPreview().recover(source, destination, new RecoveryListener() { });

        assertEquals(0, report.lostOriginals());
    }

    @Test
    void doesNotRequestSourcesAgainWhenMiniPreviewAlreadyExists() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        writeImage(destination.resolve("MINIPV_lost.png"), Color.MAGENTA);
        AtomicInteger largeRequests = new AtomicInteger();
        AtomicInteger browserRequests = new AtomicInteger();
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> {
                    largeRequests.incrementAndGet();
                    return Optional.of(image(Color.GREEN));
                }, ignored -> {
                    browserRequests.incrementAndGet();
                    return Optional.of(browserPreview(Color.BLUE));
                }, ignored -> Optional.of(image(Color.MAGENTA)), new SafeFileWriter());

        service.recover(source, destination, new RecoveryListener() { });

        assertEquals(0, largeRequests.get());
        assertEquals(0, browserRequests.get());
    }

    @Test
    void copiesOriginalAndDeletesAllLowerQualityPreviews() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination"));
        writeImage(source.resolve("photo.png"), Color.BLUE);
        writeImage(destination.resolve("WEBPV_photo.png"), Color.RED);
        writeImage(destination.resolve("PREW_photo.png"), Color.RED);
        writeImage(destination.resolve("MINIPV_photo.png"), Color.RED);

        serviceWithNoPreview().recover(source, destination, new RecoveryListener() { });

        assertNotNull(ImageIO.read(destination.resolve("photo.png").toFile()));
        assertEquals(false, Files.exists(destination.resolve("WEBPV_photo.png")));
        assertEquals(false, Files.exists(destination.resolve("PREW_photo.png")));
        assertEquals(false, Files.exists(destination.resolve("MINIPV_photo.png")));
    }

    @Test
    void doesNotDownloadBrowserPreviewAgainWhenWebpvFileAlreadyExists() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = temporaryDirectory.resolve("destination");
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        AtomicInteger browserRequests = new AtomicInteger();
        CloudPreviewExtractor cloud = relativePath -> {
            browserRequests.incrementAndGet();
            return Optional.of(browserPreview(Color.BLUE));
        };
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), cloud, new SafeFileWriter());

        service.recover(source, destination, new RecoveryListener() { });
        service.recover(source, destination, new RecoveryListener() { });

        assertEquals(1, browserRequests.get());
        assertNotNull(ImageIO.read(destination.resolve("WEBPV_lost.png").toFile()));
    }

    @Test
    void doesNotDownloadBrowserPreviewAgainWhenTheSavedResourceIsJpeg() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        Files.write(destination.resolve("WEBPV_lost.jpg"), new byte[]{1, 2, 3});
        AtomicInteger browserRequests = new AtomicInteger();
        CloudPreviewExtractor cloud = relativePath -> {
            browserRequests.incrementAndGet();
            return Optional.of(browserPreview(Color.BLUE));
        };
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), cloud, new SafeFileWriter());

        service.recover(source, destination, new RecoveryListener() { });

        assertEquals(0, browserRequests.get());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(destination.resolve("WEBPV_lost.jpg")));
    }

    @Test
    void replacesOnlyExistingPreviewWhenTheOptionIsEnabled() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        writeImage(destination.resolve("PREW_lost.png"), Color.RED);
        BufferedImage preview = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        preview.setRGB(0, 0, Color.GREEN.getRGB());
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.of(preview), new SafeFileWriter());

        RecoveryReport report = service.recover(source, destination, new RecoveryOptions(true, 1), new RecoveryListener() { });

        assertEquals(1, report.previewsSaved());
        assertEquals(Color.GREEN.getRGB(), ImageIO.read(destination.resolve("PREW_lost.png").toFile()).getRGB(0, 0));
    }

    @Test
    void processesSeveralFilesFromOneFolderConcurrently() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source/album"));
        Path destination = temporaryDirectory.resolve("destination");
        Files.writeString(source.resolve("one.jpg"), "cloud placeholder");
        Files.writeString(source.resolve("two.jpg"), "cloud placeholder");
        CountDownLatch inspectionsStarted = new CountDownLatch(2);
        CountDownLatch allowInspectionsToFinish = new CountDownLatch(1);
        MediaInspector inspector = ignored -> {
            inspectionsStarted.countDown();
            await(allowInspectionsToFinish);
            return MediaInspection.unreadable(MediaType.IMAGE);
        };
        RecoveryService service = serviceWith(inspector);

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<RecoveryReport> recovery = caller.submit(() -> service.recover(source.getParent(), destination,
                    new RecoveryOptions(false, true, 2), new RecoveryListener() { }));

            assertTrue(inspectionsStarted.await(1, TimeUnit.SECONDS));
            allowInspectionsToFinish.countDown();
            recovery.get(2, TimeUnit.SECONDS);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void completesCurrentFolderBeforeStartingFilesFromNextFolder() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path firstFolder = Files.createDirectories(source.resolve("a-current"));
        Path nextFolder = Files.createDirectories(source.resolve("z-next"));
        Path destination = temporaryDirectory.resolve("destination");
        for (int index = 1; index <= 3; index++) {
            Files.writeString(firstFolder.resolve("photo-%d.jpg".formatted(index)), "cloud placeholder");
        }
        Files.writeString(nextFolder.resolve("photo.jpg"), "cloud placeholder");
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch thirdStarted = new CountDownLatch(1);
        CountDownLatch nextFolderStarted = new CountDownLatch(1);
        Semaphore permits = new Semaphore(0);
        AtomicInteger firstFolderInspections = new AtomicInteger();
        MediaInspector inspector = file -> {
            if (file.startsWith(firstFolder)) {
                int ordinal = firstFolderInspections.incrementAndGet();
                if (ordinal <= 2) {
                    firstTwoStarted.countDown();
                } else {
                    thirdStarted.countDown();
                }
                acquire(permits);
            } else {
                nextFolderStarted.countDown();
            }
            return MediaInspection.unreadable(MediaType.IMAGE);
        };
        RecoveryService service = serviceWith(inspector);

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<RecoveryReport> recovery = caller.submit(() -> service.recover(source, destination,
                    new RecoveryOptions(false, true, 2), new RecoveryListener() { }));
            assertTrue(firstTwoStarted.await(1, TimeUnit.SECONDS));
            permits.release();
            assertTrue(thirdStarted.await(1, TimeUnit.SECONDS));
            permits.release();

            assertFalse(nextFolderStarted.await(300, TimeUnit.MILLISECONDS));
            permits.release();
            recovery.get(2, TimeUnit.SECONDS);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void cancelClosesTheActiveBrowserPreviewChannel() {
        AtomicBoolean browserClosed = new AtomicBoolean();
        CloudPreviewExtractor browser = new CloudPreviewExtractor() {
            @Override
            public Optional<BrowserPreview> extract(Path relativePath) {
                return Optional.empty();
            }

            @Override
            public void close() {
                browserClosed.set(true);
            }
        };
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), browser, ignored -> Optional.empty(), new SafeFileWriter());

        service.cancel();

        assertTrue(browserClosed.get());
    }

    @Test
    void logoutDelegatesToCloudPreviewChannel() throws Exception {
        AtomicBoolean loggedOut = new AtomicBoolean();
        CloudPreviewExtractor browser = new CloudPreviewExtractor() {
            @Override
            public Optional<BrowserPreview> extract(Path relativePath) {
                return Optional.empty();
            }

            @Override
            public void logout() {
                loggedOut.set(true);
            }
        };
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), browser, ignored -> Optional.empty(), new SafeFileWriter());

        service.logoutYandexDisk();

        assertTrue(loggedOut.get());
    }

    private RecoveryService serviceWithNoPreview() {
        return new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), new SafeFileWriter());
    }

    private RecoveryService serviceWith(MediaInspector inspector) {
        return new RecoveryService(new RecoveryPlanner(), inspector,
                ignored -> Optional.empty(), ignored -> Optional.empty(), ignored -> Optional.empty(), new SafeFileWriter());
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void acquire(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeImage(Path path, Color color) throws Exception {
        BufferedImage image = image(color);
        ImageIO.write(image, "png", path.toFile());
    }

    private void writeSizedImage(Path path, int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color.getRGB());
        ImageIO.write(image, "png", path.toFile());
    }

    private BufferedImage image(Color color) {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color.getRGB());
        return image;
    }

    private BrowserPreview browserPreview(Color color) {
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            ImageIO.write(image(color), "png", output);
            return new BrowserPreview(output.toByteArray(), "png");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
