package ru.restoreyandex.domain;

/** Результат проверки локальной доступности исходного файла. */
public record MediaInspection(MediaType type, boolean readable) {
    /** Создаёт результат для доступного оригинала. */
    public static MediaInspection readable(MediaType type) {
        return new MediaInspection(type, true);
    }

    /** Создаёт результат для недоступного оригинала. */
    public static MediaInspection unreadable(MediaType type) {
        return new MediaInspection(type, false);
    }

    /** Создаёт результат для файла, у которого не определён тип медиа. */
    public static MediaInspection unsupported() {
        return new MediaInspection(MediaType.UNSUPPORTED, false);
    }
}
