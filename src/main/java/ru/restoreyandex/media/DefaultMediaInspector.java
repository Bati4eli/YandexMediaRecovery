package ru.restoreyandex.media;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import ru.restoreyandex.domain.MediaInspection;
import ru.restoreyandex.domain.MediaType;
import ru.restoreyandex.logging.ApplicationLogger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Проверяет, доступен ли оригинал локально перед копированием.
 * Решение основано только на флаге облачной доступности Windows: оригиналы
 * копируются побайтно независимо от расширения и установленного кодека.
 */
public final class DefaultMediaInspector implements MediaInspector {
    @Override
    public MediaInspection inspect(Path file) {
        MediaType type = MediaExtensions.typeOf(file);
        if (isOfflineCloudPlaceholder(file)) {
            return MediaInspection.unreadable(type);
        }
        return MediaInspection.readable(type);
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

}
