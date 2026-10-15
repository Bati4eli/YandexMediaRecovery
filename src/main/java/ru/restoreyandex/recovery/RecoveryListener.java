package ru.restoreyandex.recovery;

import ru.restoreyandex.domain.RecoveryEvent;

import java.nio.file.Path;

/** Получает события прогресса в рабочем потоке восстановления. */
public interface RecoveryListener {
    /** Вызывается после подсчёта файлов единственным основным обходчиком папок. */
    default void onStarted(int totalFiles) {
    }

    /** Вызывается непосредственно перед проверкой и восстановлением файла. */
    default void onFileStarted(Path source, int processed, int totalFiles) {
    }

    /** Вызывается один раз для каждого найденного файла. */
    default void onFileProcessed(RecoveryEvent event) {
    }
}
