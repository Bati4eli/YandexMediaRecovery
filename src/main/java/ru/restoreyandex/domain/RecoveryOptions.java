package ru.restoreyandex.domain;

/** Параметры одного запуска восстановления, не зависящие от интерфейса. */
public record RecoveryOptions(boolean overwritePreviews, boolean deleteLowerQualityPreviews, int parallelism) {
    /** Создаёт параметры с включённой очисткой менее качественных превью. */
    public RecoveryOptions(boolean overwritePreviews, int parallelism) {
        this(overwritePreviews, true, parallelism);
    }
    /** Проверяет поддерживаемый диапазон параллельной обработки. */
    public RecoveryOptions {
        if (parallelism < 1 || parallelism > 10) {
            throw new IllegalArgumentException("Количество потоков должно быть от 1 до 10");
        }
    }

    /** Создаёт безопасные параметры по умолчанию: один поток, без замены превью. */
    public static RecoveryOptions defaults() {
        return new RecoveryOptions(false, true, 1);
    }
}
