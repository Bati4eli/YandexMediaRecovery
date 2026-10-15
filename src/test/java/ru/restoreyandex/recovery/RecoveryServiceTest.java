package ru.restoreyandex.recovery;

import ru.restoreyandex.media.DefaultMediaInspector;
import ru.restoreyandex.domain.RecoveryOptions;
import ru.restoreyandex.domain.RecoveryReport;
import ru.restoreyandex.preview.CloudPreviewExtractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        BufferedImage browserPreview = image(Color.BLUE);
        BufferedImage shellPreview = image(Color.GREEN);
        CloudPreviewExtractor cloud = relativePath -> {
            assertEquals(Path.of("2011/lost.jpg"), relativePath);
            return Optional.of(browserPreview);
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
                ignored -> Optional.empty(), ignored -> Optional.of(image(Color.BLUE)), new SafeFileWriter());

        service.recover(source.getParent(), destination, new RecoveryListener() { });

        assertEquals(Color.BLUE.getRGB(), ImageIO.read(destination.resolve("2011/WEBPV_lost.png").toFile()).getRGB(0, 0));
    }

    @Test
    void savesMiniPreviewWhenLargeExplorerAndBrowserPreviewsAreUnavailable() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destination = temporaryDirectory.resolve("destination");
        Files.writeString(source.resolve("lost.jpg"), "cloud placeholder");
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), ignored -> Optional.empty(),
                ignored -> Optional.of(image(Color.MAGENTA)), new SafeFileWriter());

        service.recover(source, destination, new RecoveryListener() { });

        assertEquals(Color.MAGENTA.getRGB(), ImageIO.read(destination.resolve("MINIPV_lost.png").toFile()).getRGB(0, 0));
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
                    return Optional.of(image(Color.BLUE));
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
            return Optional.of(image(Color.BLUE));
        };
        RecoveryService service = new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), cloud, new SafeFileWriter());

        service.recover(source, destination, new RecoveryListener() { });
        service.recover(source, destination, new RecoveryListener() { });

        assertEquals(1, browserRequests.get());
        assertNotNull(ImageIO.read(destination.resolve("WEBPV_lost.png").toFile()));
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

    private RecoveryService serviceWithNoPreview() {
        return new RecoveryService(new RecoveryPlanner(), new DefaultMediaInspector(),
                ignored -> Optional.empty(), new SafeFileWriter());
    }

    private void writeImage(Path path, Color color) throws Exception {
        BufferedImage image = image(color);
        ImageIO.write(image, "png", path.toFile());
    }

    private BufferedImage image(Color color) {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color.getRGB());
        return image;
    }
}
