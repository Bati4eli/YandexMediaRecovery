package ru.restoreyandex.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Содержит безопасные операции чтения непосредственных папок для дерева UI. */
final class DirectoryTreeSupport {
    private DirectoryTreeSupport() {
    }

    /**
     * Возвращает только непосредственные дочерние папки в порядке имени.
     * Метод намеренно не обходит вложенные каталоги, чтобы окно выбора не
     * сканировало весь Яндекс Диск при раскрытии одного узла.
     */
    static List<Path> directoriesIn(Path parent) throws IOException {
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                    .filter(path -> !isSyncDirectory(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    /** Возвращает понятное имя обычной папки или корня диска. */
    static String displayName(Path directory) {
        Path fileName = directory.getFileName();
        return fileName == null ? directory.toString() : fileName.toString();
    }

    private static boolean isSyncDirectory(Path directory) {
        Path name = directory.getFileName();
        return name != null && ".sync".equalsIgnoreCase(name.toString());
    }
}
