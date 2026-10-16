package ru.restoreyandex.app;

import ru.restoreyandex.ui.RecoveryFrame;
import ru.restoreyandex.ui.MaterialDarkTheme;
import ru.restoreyandex.logging.ApplicationLogger;

import javax.swing.SwingUtilities;

/** Точка входа в настольное приложение восстановления. */
public final class RecoveryApplication {
    private RecoveryApplication() {
    }

    /** Запускает главное окно в потоке событий Swing. */
    public static void main(String[] arguments) {
        SwingUtilities.invokeLater(() -> {
            try {
                MaterialDarkTheme.install();
            } catch (Exception exception) {
                ApplicationLogger.error("Не удалось включить тёмное Material-оформление Swing", exception);
            }
            new RecoveryFrame().setVisible(true);
        });
    }
}
