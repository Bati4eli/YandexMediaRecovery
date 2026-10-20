package ru.restoreyandex.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет замену строки текущего файла его итоговым статусом. */
class RecoveryLogViewTest {
    @Test
    void replacesRunningFileLineInsteadOfAppendingAnotherLine() {
        RecoveryLogView view = new RecoveryLogView(noopActions());
        Path source = Path.of("C:/media/source/2011/wedding/Skes.mpg");
        Path recoveryFolder = Path.of("D:/backup/2011/wedding");

        view.appendRunningFile("⏳ В работе [2 / 10] — 2011\\wedding\\Skes.mpg", source, recoveryFolder);
        view.replaceRunningFile("SRC_SAVED — 2011\\wedding\\Skes.mpg", source, recoveryFolder);

        assertEquals(1, view.getModel().getSize());
        assertEquals("SRC_SAVED — 2011\\wedding\\Skes.mpg", view.getModel().getElementAt(0).text());
    }

    @Test
    void replacesOnlyTheMatchingRunningFile() {
        RecoveryLogView view = new RecoveryLogView(noopActions());
        Path first = Path.of("C:/media/source/one.jpg");
        Path second = Path.of("C:/media/source/two.jpg");
        Path recoveryFolder = Path.of("D:/backup");

        view.appendRunningFile("⏳ В работе — one.jpg", first, recoveryFolder);
        view.appendRunningFile("⏳ В работе — two.jpg", second, recoveryFolder);
        view.replaceRunningFile("SRC_SAVED — two.jpg", second, recoveryFolder);

        assertEquals(2, view.getModel().getSize());
        assertEquals("⏳ В работе — one.jpg", view.getModel().getElementAt(0).text());
        assertEquals("SRC_SAVED — two.jpg", view.getModel().getElementAt(1).text());
    }

    private RecoveryLogView.FileActions noopActions() {
        return new RecoveryLogView.FileActions() {
            @Override
            public void showInBrowser(Path file) {
            }

            @Override
            public void showInYandexFolder(Path file) {
            }

            @Override
            public void showInRecoveryFolder(Path folder) {
            }
        };
    }
}
