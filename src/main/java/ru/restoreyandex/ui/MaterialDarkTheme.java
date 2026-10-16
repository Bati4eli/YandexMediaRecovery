package ru.restoreyandex.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * Настраивает единое тёмное оформление в стилистике Material Design.
 *
 * <p>FlatLaf отвечает за современную отрисовку Swing-компонентов и масштабирование
 * HiDPI, а палитра задаёт контрастные поверхности, фиолетовый акцент и удобные
 * для длительной работы поля журнала и дерева папок.</p>
 */
public final class MaterialDarkTheme {
    private static final Color BACKGROUND = Color.decode("#121212");
    private static final Color SURFACE = Color.decode("#1E1E1E");
    private static final Color SURFACE_VARIANT = Color.decode("#2B2930");
    private static final Color ON_SURFACE = Color.decode("#E6E1E5");
    private static final Color OUTLINE = Color.decode("#938F99");
    private static final Color PRIMARY = Color.decode("#BB86FC");
    private static final Color PRIMARY_CONTAINER = Color.decode("#5B21B6");
    private static final Color SUCCESS = Color.decode("#03DAC6");

    private MaterialDarkTheme() {
    }

    /** Устанавливает FlatLaf и Material-палитру до создания Swing-компонентов. */
    public static void install() {
        FlatDarkLaf.setup();
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 12);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ProgressBar.arc", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Viewport.background", SURFACE);
        UIManager.put("TextField.background", SURFACE_VARIANT);
        UIManager.put("TextField.foreground", ON_SURFACE);
        UIManager.put("TextArea.background", SURFACE);
        UIManager.put("TextArea.foreground", ON_SURFACE);
        UIManager.put("Tree.background", SURFACE);
        UIManager.put("Tree.foreground", ON_SURFACE);
        UIManager.put("Tree.rendererFillBackground", false);
        UIManager.put("Tree.selectionBackground", PRIMARY_CONTAINER);
        UIManager.put("Tree.selectionForeground", Color.WHITE);
        UIManager.put("Label.foreground", ON_SURFACE);
        UIManager.put("Button.background", SURFACE_VARIANT);
        UIManager.put("Button.foreground", ON_SURFACE);
        UIManager.put("Button.focusedBackground", PRIMARY_CONTAINER);
        UIManager.put("Component.focusColor", PRIMARY);
        UIManager.put("Component.focusWidth", 2);
        UIManager.put("Component.borderColor", OUTLINE);
        UIManager.put("ProgressBar.background", SURFACE_VARIANT);
        UIManager.put("ProgressBar.foreground", SUCCESS);
        UIManager.put("TitledBorder.titleColor", ON_SURFACE);
        UIManager.put("CheckBox.foreground", ON_SURFACE);
    }
}
