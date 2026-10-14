package ru.restoreyandex.recovery;

import ru.restoreyandex.media.DefaultMediaInspector;
import ru.restoreyandex.domain.RecoveryOptions;
import ru.restoreyandex.domain.RecoveryReport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color.getRGB());
        ImageIO.write(image, "png", path.toFile());
    }
}
