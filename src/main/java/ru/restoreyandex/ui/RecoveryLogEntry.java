package ru.restoreyandex.ui;

import java.nio.file.Path;
import java.util.Objects;

/** Одна визуальная строка журнала; путь присутствует только у результата обработки файла. */
record RecoveryLogEntry(String text, Path source, Path recoveryFolder) {
    RecoveryLogEntry {
        Objects.requireNonNull(text);
    }

    /** Возвращает {@code true}, если для строки допустимо контекстное меню файла. */
    boolean isFileEntry() {
        return source != null;
    }

    static RecoveryLogEntry message(String text) {
        return new RecoveryLogEntry(text, null, null);
    }
}
