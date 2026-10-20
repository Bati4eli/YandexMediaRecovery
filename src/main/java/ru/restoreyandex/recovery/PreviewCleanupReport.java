package ru.restoreyandex.recovery;

/** Итог удаления файлов превью из папки назначения. */
public record PreviewCleanupReport(int deleted, int failed) {
    /** Проверяет, что счётчики обработки неотрицательны. */
    public PreviewCleanupReport {
        if (deleted < 0 || failed < 0) {
            throw new IllegalArgumentException("Счётчики очистки не могут быть отрицательными");
        }
    }
}
