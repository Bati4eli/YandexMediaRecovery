package ru.restoreyandex.domain;

/** Результат проверки локальной доступности исходного файла. */
public record MediaInspection(MediaType type, boolean readable, boolean unavailableOnDisk) {
    /** Создаёт результат для доступного оригинала. */
    public static MediaInspection readable(MediaType type) {
        return new MediaInspection(type, true, false);
    }

    /** Создаёт результат для оригинала, недоступного для декодирования; у фото возможен поиск превью. */
    public static MediaInspection unreadable(MediaType type) {
        return new MediaInspection(type, false, false);
    }

    /** Создаёт результат для облачной заглушки без локальных байтов. */
    public static MediaInspection unavailableOnDisk(MediaType type) {
        return new MediaInspection(type, false, true);
    }

    /** Создаёт результат для файла, у которого не определён тип медиа. */
    public static MediaInspection unsupported() {
        return unreadable(MediaType.UNSUPPORTED);
    }
}
