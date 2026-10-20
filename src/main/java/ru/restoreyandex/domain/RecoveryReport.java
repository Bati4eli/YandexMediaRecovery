package ru.restoreyandex.domain;

/** Сводный итог завершённого запуска восстановления. */
public record RecoveryReport(int scanned, int copied, int previewsSaved, int previewsDeleted, int skipped,
                             int previewsUnavailable, int lostOriginals) {
    /** Возвращает краткий итог на русском языке для журнала приложения. */
    public String toDisplayString() {
        return "Восстановление завершено\n\n"
                + "Проверено: %d\n".formatted(scanned)
                + "Оригиналов сохранено: %d\n".formatted(copied)
                + "Превью сохранено: %d\n".formatted(previewsSaved)
                + "Превью худшего качества удалено: %d\n".formatted(previewsDeleted)
                + "Без изменений: %d\n".formatted(skipped)
                + "Превью недоступно: %d\n".formatted(previewsUnavailable)
                + "Утеряно оригиналов: %d".formatted(lostOriginals);
    }
}
