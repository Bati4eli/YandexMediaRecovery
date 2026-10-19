package ru.restoreyandex.media;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import ru.restoreyandex.domain.MediaInspection;
import ru.restoreyandex.domain.MediaType;
import ru.restoreyandex.logging.ApplicationLogger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

/**
 * Проверяет, доступен ли оригинал локально перед копированием.
 * Изображения декодируются, а видео проверяется чтением данных: доступный AVI
 * должен быть сохранён независимо от того, поддерживает ли его кодек JCodec.
 */
public final class DefaultMediaInspector implements MediaInspector {
    @Override
    public MediaInspection inspect(Path file) {
        MediaType type = MediaExtensions.typeOf(file);
        if (type != MediaType.UNSUPPORTED && isOfflineCloudPlaceholder(file)) {
            return MediaInspection.unreadable(type);
        }
        return switch (type) {
            case IMAGE -> new MediaInspection(type, canDecodeImage(file));
            case VIDEO -> new MediaInspection(type, canReadLocalVideo(file));
            case UNSUPPORTED -> MediaInspection.unsupported();
        };
    }

    /**
     * Не открывает негидратированную облачную заглушку. В Windows это сохраняет
     * различие между локальным оригиналом и облачным объектом, хотя оверлей
     * Проводника не является публичным API.
     */
    private boolean isOfflineCloudPlaceholder(Path file) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            return false;
        }
        try {
            int attributes = Kernel32.INSTANCE.GetFileAttributes(file.toString());
            if (attributes == WinBase.INVALID_FILE_ATTRIBUTES) {
                ApplicationLogger.error("Не удалось прочитать Windows-атрибуты облачного файла: " + file,
                        new IOException("GetFileAttributes failed with code " + Kernel32.INSTANCE.GetLastError()));
                return false;
            }
            return isOfflineWindowsAttributes(attributes);
        } catch (RuntimeException | LinkageError exception) {
            ApplicationLogger.error("Не удалось прочитать Windows-атрибуты облачного файла: " + file, exception);
            return false;
        }
    }

    /** Возвращает {@code true}, если в нативных атрибутах Windows есть флаг OFFLINE. */
    static boolean isOfflineWindowsAttributes(int attributes) {
        return (attributes & WinNT.FILE_ATTRIBUTE_OFFLINE) != 0;
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

    /**
     * Проверяет содержимое локального видео без декодирования. Сначала вызывающий
     * код отсекает {@code FILE_ATTRIBUTE_OFFLINE}, поэтому чтение не должно
     * запускать загрузку облачной заглушки.
     */
    private boolean canReadLocalVideo(Path file) {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            return channel.size() > 0 && channel.read(ByteBuffer.allocate(1)) > 0;
        } catch (IOException | RuntimeException exception) {
            ApplicationLogger.error("Не удалось прочитать локальный видеооригинал: " + file, exception);
            return false;
        }
    }
}
