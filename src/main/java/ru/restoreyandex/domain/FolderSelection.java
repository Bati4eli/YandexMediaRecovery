package ru.restoreyandex.domain;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Определяет, входит ли файл в выбранные пользователем ветки дерева папок.
 * Более глубокое правило имеет приоритет над правилом его предка; без правил
 * выбраны все файлы корня Яндекс Диска.
 */
public final class FolderSelection {
    private final List<FolderSelectionRule> rules;

    /** Создаёт неизменяемый набор правил, полученный из UI-дерева. */
    public FolderSelection(List<FolderSelectionRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules));
    }

    /** Возвращает выбор без ограничений — выбраны все папки. */
    public static FolderSelection allFolders() {
        return new FolderSelection(List.of());
    }

    /** Проверяет путь по наиболее глубоко вложенному применимому правилу. */
    public boolean includes(Path file) {
        Path normalizedFile = Objects.requireNonNull(file).toAbsolutePath().normalize();
        return rules.stream()
                .filter(rule -> normalizedFile.startsWith(rule.folder()))
                .max(Comparator.comparingInt(rule -> rule.folder().getNameCount()))
                .map(FolderSelectionRule::included)
                .orElse(true);
    }

    /**
     * Возвращает {@code true}, если в папке или в одной из её веток есть
     * выбранный путь. Метод нужен обходчику, чтобы пропускать исключённые
     * поддеревья, но не потерять явно включённую вложенную папку.
     */
    public boolean shouldTraverse(Path directory) {
        Path normalizedDirectory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        return includes(normalizedDirectory) || rules.stream()
                .anyMatch(rule -> rule.included() && rule.folder().startsWith(normalizedDirectory));
    }

    /** Возвращает правила для передачи в параметры одного запуска. */
    public List<FolderSelectionRule> rules() {
        return rules;
    }
}
