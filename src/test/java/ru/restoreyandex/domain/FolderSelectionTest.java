package ru.restoreyandex.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет наследование выбора папок из дерева главного окна. */
class FolderSelectionTest {
    @Test
    void nestedIncludedRuleOverridesExcludedParent() {
        Path root = Path.of("C:/Yandex.Disk");
        FolderSelection selection = new FolderSelection(List.of(
                new FolderSelectionRule(root.resolve("MEDIA"), false),
                new FolderSelectionRule(root.resolve("MEDIA/family"), true)));

        assertFalse(selection.includes(root.resolve("MEDIA/work/report.jpg")));
        assertTrue(selection.includes(root.resolve("MEDIA/family/photo.jpg")));
        assertTrue(selection.includes(root.resolve("Documents/note.jpg")));
    }
}
