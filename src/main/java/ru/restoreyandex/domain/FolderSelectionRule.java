package ru.restoreyandex.domain;

import java.nio.file.Path;
import java.util.Objects;

/** Одно явное правило включения или исключения папки из восстановления. */
public record FolderSelectionRule(Path folder, boolean included) {
    /** Нормализует путь правила, чтобы сравнение поддеревьев было однозначным. */
    public FolderSelectionRule {
        folder = Objects.requireNonNull(folder).toAbsolutePath().normalize();
    }
}
