package ru.restoreyandex.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMediaInspectorTest {
    @TempDir
    Path temporaryDirectory;

    private final DefaultMediaInspector inspector = new DefaultMediaInspector();

    @Test
    void decodesARealImage() throws Exception {
        Path image = temporaryDirectory.resolve("photo.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile());

        assertTrue(inspector.inspect(image).readable());
    }

    @Test
    void rejectsAPlaceholderPretendingToBeAnImage() throws Exception {
        Path image = Files.writeString(temporaryDirectory.resolve("photo.jpg"), "not a JPEG");

        assertFalse(inspector.inspect(image).readable());
    }
}
