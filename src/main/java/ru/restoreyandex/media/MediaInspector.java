package ru.restoreyandex.media;

import ru.restoreyandex.domain.MediaInspection;

import java.nio.file.Path;

/** Проверяет, можно ли открыть медиафайл без его изменения. */
@FunctionalInterface
public interface MediaInspector {
    /**
     * Проверяет файл. Реализация должна превращать ошибки разбора в результат
     * «недоступен», а не изменять и не удалять файл.
     */
    MediaInspection inspect(Path file);
}
