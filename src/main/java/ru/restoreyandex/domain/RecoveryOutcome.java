package ru.restoreyandex.domain;

/** Наблюдаемый итог обработки одного исходного пути. */
public enum RecoveryOutcome {
    COPIED,
    WEB_PREVIEW_SAVED,
    EXPLORER_PREVIEW_SAVED,
    MINI_PREVIEW_SAVED,
    PREVIEWS_CLEANED,
    ALREADY_OK,
    NO_FILE;

    /** Возвращает неизменный статус для записи в CSV. */
    public String csvStatus() {
        return switch (this) {
            case COPIED -> "SRC_SAVED";
            case WEB_PREVIEW_SAVED -> "WEBPV_SAVED";
            case EXPLORER_PREVIEW_SAVED -> "PREW_SAVED";
            case MINI_PREVIEW_SAVED -> "MINIPV_SAVED";
            case PREVIEWS_CLEANED -> "PREVIEWS_CLEANED";
            case ALREADY_OK -> "ALREADY_OK";
            case NO_FILE -> "NO_FILE";
        };
    }

    /** Возвращает текстовый статус для начала строки экранного журнала. */
    public String screenStatus() {
        return switch (this) {
            case COPIED -> "SRC_SAVED";
            case WEB_PREVIEW_SAVED -> "WEBPV_SAVED";
            case EXPLORER_PREVIEW_SAVED -> "PREW_SAVED";
            case MINI_PREVIEW_SAVED -> "MINIPV_SAVED";
            case PREVIEWS_CLEANED -> "PREVIEWS_CLEANED";
            case ALREADY_OK -> "ALREADY_OK";
            case NO_FILE -> "NO_FILE";
        };
    }
}
