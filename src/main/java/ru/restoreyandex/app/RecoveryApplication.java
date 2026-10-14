package ru.restoreyandex.app;

import ru.restoreyandex.ui.RecoveryFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Точка входа в настольное приложение восстановления. */
public final class RecoveryApplication {
    private RecoveryApplication() {
    }

    /** Запускает главное окно в потоке событий Swing. */
    public static void main(String[] arguments) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Стандартное кроссплатформенное оформление Swing остаётся рабочим.
            }
            new RecoveryFrame().setVisible(true);
        });
    }
}
