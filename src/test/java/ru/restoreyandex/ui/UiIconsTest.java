package ru.restoreyandex.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Проверяет доступность цветных PNG-иконок интерфейса. */
class UiIconsTest {
    @Test
    void loadsEveryIconFromThePngAtlas() {
        for (UiIcon icon : UiIcon.values()) {
            assertNotNull(UiIcons.icon(icon, 18));
        }
    }
}
