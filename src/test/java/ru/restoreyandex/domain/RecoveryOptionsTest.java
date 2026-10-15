package ru.restoreyandex.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет правила параметров одного запуска восстановления. */
class RecoveryOptionsTest {
    @Test
    void debugBrowserModeForcesOneRecoveryWorker() {
        RecoveryOptions options = new RecoveryOptions(false, true, 10, true, FolderSelection.allFolders());

        assertEquals(1, options.parallelism());
    }
}
