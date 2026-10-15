package ru.restoreyandex.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет стартовое правило дерева и восстановление сохранённой ветки. */
class FolderSelectionTreeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void selectsEverythingWhenYamlDoesNotContainSelectedFolders() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("disk"));
        Path unselectedAtFirst = Files.createDirectories(root.resolve("MEDIA"));
        FolderSelectionTree tree = new FolderSelectionTree();

        tree.reload(root, List.of());

        assertTrue(tree.selection().includes(unselectedAtFirst.resolve("photo.jpg")));
    }

    @Test
    void restoresOnlyTheSavedBranchAfterLoadingItsAncestors() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("disk"));
        Path selected = Files.createDirectories(root.resolve("MEDIA/Семья/Свадьба"));
        Path other = Files.createDirectories(root.resolve("MEDIA/Работа"));
        FolderSelectionTree tree = new FolderSelectionTree();

        tree.reload(root, List.of(selected));
        waitUntilRestored(tree, selected);

        assertTrue(tree.selection().includes(selected.resolve("photo.jpg")));
        assertFalse(tree.selection().includes(other.resolve("report.jpg")));
        assertTrue(tree.selectedFolders().contains(selected));
    }

    private void waitUntilRestored(FolderSelectionTree tree, Path selected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (tree.selection().includes(selected.resolve("photo.jpg"))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Сохранённая ветка не была восстановлена");
    }
}
