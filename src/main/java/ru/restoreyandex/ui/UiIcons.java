package ru.restoreyandex.ui;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Загружает и масштабирует цветные PNG-иконки интерфейса из единого атласа ресурсов. */
final class UiIcons {
    private static final int GRID_SIZE = 4;
    private static final BufferedImage ATLAS = loadAtlas();
    private static final Map<IconKey, Icon> ICONS = new HashMap<>();

    private UiIcons() {
    }

    /** Возвращает кэшированную иконку нужного типа и квадратного размера. */
    static Icon icon(UiIcon icon, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Размер иконки должен быть положительным");
        }
        return ICONS.computeIfAbsent(new IconKey(icon, size), UiIcons::createIcon);
    }

    private static Icon createIcon(IconKey key) {
        int index = key.icon.ordinal();
        int column = index % GRID_SIZE;
        int row = index / GRID_SIZE;
        int left = column * ATLAS.getWidth() / GRID_SIZE;
        int top = row * ATLAS.getHeight() / GRID_SIZE;
        int right = (column + 1) * ATLAS.getWidth() / GRID_SIZE;
        int bottom = (row + 1) * ATLAS.getHeight() / GRID_SIZE;
        BufferedImage cell = ATLAS.getSubimage(left, top, right - left, bottom - top);
        Image scaled = cell.getScaledInstance(key.size, key.size, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    private static BufferedImage loadAtlas() {
        try (InputStream stream = UiIcons.class.getResourceAsStream("/ui-icons.png")) {
            if (stream == null) {
                throw new IllegalStateException("Не найден ресурс ui-icons.png");
            }
            BufferedImage atlas = ImageIO.read(stream);
            if (atlas == null) {
                throw new IllegalStateException("Не удалось декодировать ui-icons.png");
            }
            return atlas;
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось загрузить ui-icons.png", exception);
        }
    }

    private record IconKey(UiIcon icon, int size) {
    }
}
