package ru.restoreyandex.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Журнал восстановления с контекстными действиями для строк, которые относятся
 * к конкретному файлу. Обычные диагностические строки остаются пассивными.
 */
final class RecoveryLogView extends JList<RecoveryLogEntry> {
    private static final String ACTION_ICON = "⋮";
    private final DefaultListModel<RecoveryLogEntry> model = new DefaultListModel<>();
    private final Map<Path, Integer> runningFileIndexes = new HashMap<>();
    private final FileActions actions;
    private int hoveredIndex = -1;

    RecoveryLogView(FileActions actions) {
        super();
        this.actions = Objects.requireNonNull(actions);
        setModel(model);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setFocusable(false);
        setFont(getFont().deriveFont(Font.PLAIN, 16f));
        setCellRenderer(new RecoveryLogRenderer());
        installMouseInteractions();
    }

    /** Добавляет служебное сообщение, разделяя многострочный текст на строки журнала. */
    void appendMessage(String message) {
        for (String line : message.split("\\R", -1)) {
            model.addElement(RecoveryLogEntry.message(line));
        }
        scrollToEnd();
    }

    /** Добавляет строку результата обработки файла и привязывает к ней пути для меню. */
    void appendFile(String text, Path source, Path recoveryFolder) {
        model.addElement(new RecoveryLogEntry(text, source, recoveryFolder));
        scrollToEnd();
    }

    /** Добавляет строку файла, который начал обрабатываться, и запоминает её для итоговой замены. */
    void appendRunningFile(String text, Path source, Path recoveryFolder) {
        appendRunningFile(text, source, recoveryFolder, true);
    }

    /** Добавляет строку текущего файла; пакетный UI сам выполняет прокрутку в конце пачки. */
    void appendRunningFile(String text, Path source, Path recoveryFolder, boolean scrollToEnd) {
        Path key = normalized(source);
        Integer existingIndex = runningFileIndexes.get(key);
        RecoveryLogEntry entry = new RecoveryLogEntry(text, source, recoveryFolder);
        if (existingIndex != null) {
            model.set(existingIndex, entry);
            repaint(getCellBounds(existingIndex, existingIndex));
        } else {
            int index = model.size();
            model.addElement(entry);
            runningFileIndexes.put(key, index);
        }
        if (scrollToEnd) {
            scrollToEnd();
        }
    }

    /** Заменяет строку текущего файла итоговым статусом, не добавляя дубль в журнал. */
    void replaceRunningFile(String text, Path source, Path recoveryFolder) {
        replaceRunningFile(text, source, recoveryFolder, true);
    }

    /** Заменяет строку текущего файла; пакетный UI сам выполняет прокрутку в конце пачки. */
    void replaceRunningFile(String text, Path source, Path recoveryFolder, boolean scrollToEnd) {
        Path key = normalized(source);
        Integer index = runningFileIndexes.remove(key);
        RecoveryLogEntry entry = new RecoveryLogEntry(text, source, recoveryFolder);
        if (index == null) {
            model.addElement(entry);
            if (scrollToEnd) {
                scrollToEnd();
            }
            return;
        }
        model.set(index, entry);
        repaint(getCellBounds(index, index));
        if (scrollToEnd) {
            ensureIndexIsVisible(index);
        }
    }

    /** Очищает журнал и показывает начальное сообщение. */
    void replaceWith(String message) {
        model.clear();
        runningFileIndexes.clear();
        appendMessage(message);
    }

    /** Прокручивает журнал к последней добавленной строке. */
    void scrollToEnd() {
        int last = model.size() - 1;
        if (last >= 0) {
            ensureIndexIsVisible(last);
        }
    }

    private void installMouseInteractions() {
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                updateHover(indexAt(event.getPoint()));
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent event) {
                updateHover(-1);
            }

            @Override
            public void mousePressed(MouseEvent event) {
                showContextMenu(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showContextMenu(event);
            }
        });
    }

    private void updateHover(int index) {
        int fileIndex = index >= 0 && model.get(index).isFileEntry() ? index : -1;
        if (hoveredIndex == fileIndex) {
            return;
        }
        int previous = hoveredIndex;
        hoveredIndex = fileIndex;
        if (previous >= 0) {
            repaint(getCellBounds(previous, previous));
        }
        if (hoveredIndex >= 0) {
            repaint(getCellBounds(hoveredIndex, hoveredIndex));
        }
        setCursor(hoveredIndex >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
    }

    private void showContextMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int index = indexAt(event.getPoint());
        if (index < 0 || !model.get(index).isFileEntry()) {
            return;
        }
        setSelectedIndex(index);
        RecoveryLogEntry entry = model.get(index);
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("🌐 Показать в браузере", () -> actions.showInBrowser(entry.source())));
        menu.add(menuItem("📁 Показать в Яндекс папке", () -> actions.showInYandexFolder(entry.source())));
        menu.add(menuItem("📂 Показать в папке восстановления", () -> actions.showInRecoveryFolder(entry.recoveryFolder())));
        menu.show(this, event.getX(), event.getY());
    }

    private JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(event -> action.run());
        return item;
    }

    /** Не принимает ближайшую строку JList, если курсор уже находится ниже неё. */
    private int indexAt(Point point) {
        int index = locationToIndex(point);
        return index >= 0 && getCellBounds(index, index).contains(point) ? index : -1;
    }

    private Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** Действия с путями выполняет окно, чтобы все ошибки попали в единый журнал приложения. */
    interface FileActions {
        void showInBrowser(Path file);

        void showInYandexFolder(Path file);

        void showInRecoveryFolder(Path folder);
    }

    private final class RecoveryLogRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            RecoveryLogEntry entry = (RecoveryLogEntry) value;
            javax.swing.JPanel cell = new javax.swing.JPanel(new BorderLayout(8, 0));
            boolean hovered = index == hoveredIndex && entry.isFileEntry();
            Color background = selected || hovered ? list.getSelectionBackground() : list.getBackground();
            Color foreground = selected || hovered ? list.getSelectionForeground() : list.getForeground();
            cell.setBackground(background);
            cell.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            javax.swing.JLabel text = new javax.swing.JLabel(entry.text());
            text.setFont(list.getFont());
            text.setForeground(foreground);
            text.setBackground(background);
            text.setOpaque(false);
            text.setToolTipText(entry.isFileEntry() ? entry.source().toString() : null);
            cell.add(text, BorderLayout.CENTER);
            javax.swing.JLabel action = new javax.swing.JLabel(ACTION_ICON);
            action.setFont(list.getFont().deriveFont(Font.BOLD));
            action.setForeground(foreground);
            action.setVisible(hovered);
            action.setPreferredSize(new Dimension(18, 18));
            cell.add(action, BorderLayout.EAST);
            return cell;
        }
    }
}
