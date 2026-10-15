package ru.restoreyandex.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет безопасную выборку непосредственных папок для ленивого дерева. */
class DirectoryTreeSupportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsOnlyDirectoriesSortedWithoutScanningNestedContent() throws Exception {
        Path zulu = Files.createDirectory(temporaryDirectory.resolve("Zulu"));
        Path alpha = Files.createDirectory(temporaryDirectory.resolve("alpha"));
        Files.createDirectory(temporaryDirectory.resolve(".sync"));
        Files.createDirectories(alpha.resolve("nested"));
        Files.writeString(temporaryDirectory.resolve("file.jpg"), "not a directory");

        List<Path> directories = DirectoryTreeSupport.directoriesIn(temporaryDirectory);

        assertEquals(List.of(alpha, zulu), directories);
    }
}
