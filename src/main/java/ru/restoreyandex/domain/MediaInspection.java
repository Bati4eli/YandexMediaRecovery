package ru.restoreyandex.domain;

/** Результат безопасной попытки открыть исходный файл как медиа. */
public record MediaInspection(MediaType type, boolean readable) {
    /** Создаёт результат для успешно открытого файла. */
    public static MediaInspection readable(MediaType type) {
        return new MediaInspection(type, true);
    }

    /** Создаёт результат для поддерживаемого, но недоступного файла. */
    public static MediaInspection unreadable(MediaType type) {
        return new MediaInspection(type, false);
    }

    /** Создаёт результат для файла, который не является медиа. */
    public static MediaInspection unsupported() {
        return new MediaInspection(MediaType.UNSUPPORTED, false);
    }
}
