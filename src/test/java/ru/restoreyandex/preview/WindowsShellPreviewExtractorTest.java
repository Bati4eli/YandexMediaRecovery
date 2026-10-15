package ru.restoreyandex.preview;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет границу между большим превью Explorer и маленькой обложкой. */
class WindowsShellPreviewExtractorTest {
    @Test
    void rejectsExplorerCoverAsLargePreview() {
        assertFalse(WindowsShellPreviewExtractor.isLargePreview(new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void acceptsHighResolutionExplorerPreview() {
        assertTrue(WindowsShellPreviewExtractor.isLargePreview(new BufferedImage(1266, 720, BufferedImage.TYPE_INT_RGB)));
    }
}
