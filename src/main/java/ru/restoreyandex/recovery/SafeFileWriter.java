package ru.restoreyandex.recovery;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Записывает результаты через временный файл рядом с целью, не заменяя существующий файл. */
final class SafeFileWriter {
    void copy(Path source, Path target) throws IOException {
        prepareParent(target);
        Path temporary = temporarySibling(target);
        try {
            Files.copy(source, temporary);
            moveWithoutReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void writePreview(BufferedImage image, Path target) throws IOException {
        prepareParent(target);
        Path temporary = temporarySibling(target);
        try {
            if (!ImageIO.write(image, "png", temporary.toFile())) {
                throw new IOException("PNG writer is unavailable");
            }
            moveWithoutReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void prepareParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Target must have a parent directory: " + target);
        }
        Files.createDirectories(parent);
    }

    private Path temporarySibling(Path target) {
        return target.resolveSibling(target.getFileName() + ".partial-" + UUID.randomUUID());
    }

    private void moveWithoutReplacing(Path temporary, Path target) throws IOException {
        // Намеренно не передаём REPLACE_EXISTING и ATOMIC_MOVE: при атомарном
        // перемещении замена существующей цели зависит от файлового провайдера.
        Files.move(temporary, target);
    }
}
