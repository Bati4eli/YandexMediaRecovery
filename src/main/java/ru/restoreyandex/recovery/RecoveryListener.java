package ru.restoreyandex.recovery;

import ru.restoreyandex.domain.RecoveryEvent;

/** Получает события прогресса в рабочем потоке восстановления. */
public interface RecoveryListener {
    /** Вызывается после формирования рекурсивного списка файлов. */
    default void onStarted(int totalFiles) {
    }

    /** Вызывается один раз для каждого найденного файла. */
    default void onFileProcessed(RecoveryEvent event) {
    }
}
