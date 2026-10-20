package ru.restoreyandex.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет распознавание облачных заглушек по нативным атрибутам Windows. */
class DefaultMediaInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesOnlyPinnedCloudFileAndNeverHydratesAmbiguousReparsePoint() {
        assertTrue(DefaultMediaInspector.hasLocalWindowsContent(0x80420));
        assertFalse(DefaultMediaInspector.hasLocalWindowsContent(0x481620));
        assertFalse(DefaultMediaInspector.hasLocalWindowsContent(0x420));
        assertTrue(DefaultMediaInspector.hasLocalWindowsContent(0x20));
    }

    @Test
    void treatsLocallyAvailableAviAsAnOriginalEvenWhenItsCodecIsUnsupported() throws Exception {
        Path video = Files.write(temporaryDirectory.resolve("legacy.avi"),
                new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4});

        var inspection = new DefaultMediaInspector().inspect(video);

        assertEquals(ru.restoreyandex.domain.MediaType.VIDEO, inspection.type());
        assertTrue(inspection.readable());
    }
}
