package ru.restoreyandex.media;

import ru.restoreyandex.domain.MediaInspection;
import ru.restoreyandex.domain.MediaType;
import ru.restoreyandex.logging.ApplicationLogger;

import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** Открывает пиксели изображения или декодируемый кадр видео перед копированием оригинала. */
public final class DefaultMediaInspector implements MediaInspector {
    @Override
    public MediaInspection inspect(Path file) {
        MediaType type = MediaExtensions.typeOf(file);
        if (type != MediaType.UNSUPPORTED && isOfflineCloudPlaceholder(file)) {
            return MediaInspection.unreadable(type);
        }
        return switch (type) {
            case IMAGE -> new MediaInspection(type, canDecodeImage(file));
            case VIDEO -> new MediaInspection(type, canDecodeVideo(file));
            case UNSUPPORTED -> MediaInspection.unsupported();
        };
    }

    /**
     * Не открывает негидратированную облачную заглушку. В Windows это сохраняет
     * различие между локальным оригиналом и облачным объектом, хотя оверлей
     * Проводника не является публичным API.
     */
    private boolean isOfflineCloudPlaceholder(Path file) {
        try {
            Object offline = Files.getAttribute(file, "dos:offline");
            return Boolean.TRUE.equals(offline);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException exception) {
            ApplicationLogger.error("Не удалось прочитать Windows-флаг облачной заглушки: " + file, exception);
            return false;
        }
    }

    private boolean canDecodeImage(Path file) {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                BufferedImage image = reader.read(0);
                return image != null && image.getWidth() > 0 && image.getHeight() > 0;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            ApplicationLogger.error("Не удалось декодировать изображение: " + file, exception);
            return false;
        }
    }

    private boolean canDecodeVideo(Path file) {
        try (SeekableByteChannel channel = NIOUtils.readableChannel(file.toFile())) {
            return FrameGrab.createFrameGrab(channel).getNativeFrame() != null;
        } catch (Exception exception) {
            ApplicationLogger.error("Не удалось декодировать видео: " + file, exception);
            return false;
        }
    }
}
