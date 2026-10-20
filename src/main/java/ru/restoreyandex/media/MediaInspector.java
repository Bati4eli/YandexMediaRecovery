package ru.restoreyandex.media;

import ru.restoreyandex.domain.MediaInspection;

import java.nio.file.Path;

/** Проверяет, доступен ли исходный файл локально без его изменения. */
@FunctionalInterface
public interface MediaInspector {
    /**
     * Проверяет файл. Реализация не должна изменять или удалять исходный файл.
     */
    MediaInspection inspect(Path file);
}
