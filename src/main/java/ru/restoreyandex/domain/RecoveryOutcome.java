package ru.restoreyandex.domain;

/** Наблюдаемый итог обработки одного исходного пути. */
public enum RecoveryOutcome {
    COPIED,
    PREVIEW_SAVED,
    ALREADY_OK,
    NO_FILE;

    /** Возвращает неизменный статус для записи в CSV. */
    public String csvStatus() {
        return switch (this) {
            case COPIED -> "SRC_SAVED";
            case PREVIEW_SAVED -> "PREVIEW_SAVED";
            case ALREADY_OK -> "ALREADY_OK";
            case NO_FILE -> "NO_FILE";
        };
    }

    /** Возвращает статус с эмодзи для начала строки экранного журнала. */
    public String screenStatus() {
        return switch (this) {
            case COPIED -> "💾 SRC_SAVED";
            case PREVIEW_SAVED -> "🖼️ PREVIEW_SAVED";
            case ALREADY_OK -> "✅ ALREADY_OK";
            case NO_FILE -> "❌ NO_FILE";
        };
    }
}
