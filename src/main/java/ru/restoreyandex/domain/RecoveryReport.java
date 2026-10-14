package ru.restoreyandex.domain;

/** Сводный итог завершённого запуска восстановления. */
public record RecoveryReport(int scanned, int copied, int previewsSaved, int skipped,
                             int previewsUnavailable, int failed) {
    /** Возвращает краткий итог на русском языке для журнала приложения. */
    public String toDisplayString() {
        return "Готово: обработано %d; скопировано %d; превью %d; пропущено %d; "
                .formatted(scanned, copied, previewsSaved, skipped)
                + "превью недоступно %d; ошибок %d."
                .formatted(previewsUnavailable, failed);
    }
}
