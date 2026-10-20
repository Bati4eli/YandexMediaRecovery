package ru.restoreyandex.ui;

import ru.restoreyandex.domain.FolderSelection;
import ru.restoreyandex.domain.FolderSelectionRule;
import ru.restoreyandex.logging.ApplicationLogger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Дерево выбора папок восстановления.
 *
 * <p>После перезапуска сначала показывает корневые папки выключенными. Затем
 * раскрывает только ветки, записанные в YAML, и включает их. Пустой список в
 * YAML имеет специальный смысл: выбраны все папки.</p>
 */
final class FolderSelectionTree extends JPanel {
    private final DirectoryNode placeholderRoot = new DirectoryNode(null, "Выберите корень Яндекс Диска", false);
    private final DefaultTreeModel model = new DefaultTreeModel(placeholderRoot);
    private final JTree tree = new JTree(model);
    private final JButton selectAllButton = new JButton("Выбрать всё");
    private Path rootFolder;
    private List<Path> restoredSelectedFolders = List.of();
    private boolean defaultAllSelected = true;
    private boolean selectionEditingEnabled = true;
    private Runnable selectionChangedListener = () -> { };
    private final FolderActions folderActions;

    FolderSelectionTree() {
        this(new FolderActions() {
            @Override
            public void showInYandexDisk(Path folder) {
                // Дерево без окна-владельца используется только в модульных тестах.
            }

            @Override
            public void showInExplorer(Path folder) {
                // Дерево без окна-владельца используется только в модульных тестах.
            }

            @Override
            public Optional<Path> recoveryFolderFor(Path sourceFolder) {
                return Optional.empty();
            }

            @Override
            public void showInRecoveryFolder(Path folder) {
                // Дерево без окна-владельца используется только в модульных тестах.
            }
        });
    }

    FolderSelectionTree(FolderActions folderActions) {
        super(new BorderLayout(0, 4));
        this.folderActions = Objects.requireNonNull(folderActions);
        setBorder(BorderFactory.createTitledBorder("Папки для восстановления"));
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer((currentTree, value, selected, expanded, leaf, row, hasFocus) -> {
            if (value instanceof DirectoryNode node) {
                JCheckBox checkBox = new JCheckBox(node.toString(), node.selected);
                // FlatLaf назначает JCheckBox собственную поверхность. У renderer
                // она выглядит как серый прямоугольник за каждым именем папки.
                // Используем ровно фон дерева, чтобы строка оставалась цельной.
                checkBox.setOpaque(true);
                checkBox.setBackground(currentTree.getBackground());
                checkBox.setForeground(currentTree.getForeground());
                checkBox.setBorderPaintedFlat(true);
                checkBox.setEnabled(selectionEditingEnabled);
                return checkBox;
            }
            return new JLabel(value.toString());
        });
        installLazyLoading();
        installSelectionToggle();
        installContextMenu();
        selectAllButton.addActionListener(event -> {
            if (!selectionEditingEnabled || !(model.getRoot() instanceof DirectoryNode root)) {
                return;
            }
            applySelection(root, true);
            defaultAllSelected = true;
            tree.repaint();
            selectionChangedListener.run();
        });

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        header.add(new JLabel("Отметьте нужные папки. При пустом выборе будут выбраны все."));
        header.add(selectAllButton);
        add(header, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    /** Перезагружает дерево с обычным правилом «выбрать всё». */
    void reload(Path rootFolder) {
        reload(rootFolder, List.of());
    }

    /**
     * Строит дерево в три шага: корень выключен, сохранённые ветки раскрыты и
     * включены, либо при пустом списке выбраны все папки.
     */
    void reload(Path sourceRoot, List<Path> selectedFolders) {
        rootFolder = sourceRoot.toAbsolutePath().normalize();
        restoredSelectedFolders = selectedFolders.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(path -> path.startsWith(rootFolder) && !path.equals(rootFolder))
                .distinct()
                .sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER))
                .toList();
        defaultAllSelected = restoredSelectedFolders.isEmpty();
        DirectoryNode rootNode = new DirectoryNode(rootFolder, DirectoryTreeSupport.displayName(rootFolder), false);
        model.setRoot(rootNode);
        tree.setSelectionPath(new TreePath(rootNode.getPath()));
        loadChildren(rootNode, () -> restoreSelection(rootNode));
    }

    /** Возвращает выбранные корни папок для YAML; пустой список означает «выбрать всё». */
    List<Path> selectedFolders() {
        if (defaultAllSelected || !(model.getRoot() instanceof DirectoryNode root) || root.path == null) {
            return List.of();
        }
        List<Path> selected = new ArrayList<>();
        collectSelectedRoots(root, selected);
        return selected.size() == 1 && selected.getFirst().equals(root.path) ? List.of() : List.copyOf(selected);
    }

    /** Возвращает фактические выбранные корни для операций над содержимым папок. */
    List<Path> selectedFolderRoots() {
        if (rootFolder == null || !(model.getRoot() instanceof DirectoryNode root)) {
            return List.of();
        }
        if (defaultAllSelected) {
            return List.of(rootFolder);
        }
        List<Path> selected = new ArrayList<>();
        collectSelectedRoots(root, selected);
        return List.copyOf(selected);
    }

    /** Преобразует текущее дерево в правила для запуска восстановления. */
    FolderSelection selection() {
        if (defaultAllSelected || rootFolder == null) {
            return FolderSelection.allFolders();
        }
        List<FolderSelectionRule> rules = new ArrayList<>();
        rules.add(new FolderSelectionRule(rootFolder, false));
        selectedFolders().forEach(path -> rules.add(new FolderSelectionRule(path, true)));
        return new FolderSelection(rules);
    }

    /** Блокирует флажки на время активного восстановления. */
    void setSelectionEditingEnabled(boolean enabled) {
        selectionEditingEnabled = enabled;
        tree.setEnabled(enabled);
        selectAllButton.setEnabled(enabled);
        tree.repaint();
    }

    /** Задаёт действие после пользовательского изменения флажка. */
    void setSelectionChangedListener(Runnable listener) {
        selectionChangedListener = Objects.requireNonNull(listener);
    }

    private void restoreSelection(DirectoryNode root) {
        if (restoredSelectedFolders.isEmpty()) {
            applySelection(root, true);
            tree.repaint();
            return;
        }
        for (Path selectedFolder : restoredSelectedFolders) {
            restoreSelectedPath(root, selectedFolder);
        }
    }

    /** Раскрывает только предков сохранённой папки и включает её. */
    private void restoreSelectedPath(DirectoryNode node, Path selectedFolder) {
        if (!selectedFolder.startsWith(node.path)) {
            return;
        }
        if (selectedFolder.equals(node.path)) {
            applySelection(node, true);
            tree.expandPath(new TreePath(node.getPath()));
            tree.repaint();
            return;
        }
        loadChildren(node, () -> {
            DirectoryNode child = childFor(node, selectedFolder);
            if (child == null) {
                ApplicationLogger.info("Сохранённая папка больше не найдена: " + selectedFolder);
                return;
            }
            tree.expandPath(new TreePath(node.getPath()));
            restoreSelectedPath(child, selectedFolder);
        });
    }

    private DirectoryNode childFor(DirectoryNode parent, Path target) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            Object child = parent.getChildAt(index);
            if (child instanceof DirectoryNode directoryNode && target.startsWith(directoryNode.path)) {
                return directoryNode;
            }
        }
        return null;
    }

    private void collectSelectedRoots(DirectoryNode node, List<Path> selected) {
        List<DirectoryNode> directories = directoryChildren(node);
        if (allLoadedDescendantsSelected(node)) {
            selected.add(node.path);
            return;
        }
        for (DirectoryNode child : directories) {
            collectSelectedRoots(child, selected);
        }
    }

    /** Не сворачивает выбранного предка, если в загруженной глубокой ветке есть снятый флажок. */
    private boolean allLoadedDescendantsSelected(DirectoryNode node) {
        return node.selected && directoryChildren(node).stream().allMatch(this::allLoadedDescendantsSelected);
    }

    private List<DirectoryNode> directoryChildren(DirectoryNode node) {
        List<DirectoryNode> result = new ArrayList<>();
        for (int index = 0; index < node.getChildCount(); index++) {
            Object child = node.getChildAt(index);
            if (child instanceof DirectoryNode directoryNode) {
                result.add(directoryNode);
            }
        }
        return result;
    }

    private void installLazyLoading() {
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() instanceof DirectoryNode node) {
                    loadChildren(node, () -> { });
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                // Загруженные данные сохраняются, чтобы повторное раскрытие не читало диск снова.
            }
        });
    }

    private void installSelectionToggle() {
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (!selectionEditingEnabled || !SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null || !(path.getLastPathComponent() instanceof DirectoryNode node)) {
                    return;
                }
                Rectangle rowBounds = tree.getPathBounds(path);
                if (rowBounds != null && rowBounds.contains(event.getPoint())) {
                    applySelection(node, !node.selected);
                    defaultAllSelected = false;
                    tree.repaint();
                    selectionChangedListener.run();
                }
            }
        });
    }

    /** Устанавливает меню для открытия выбранного каталога в Проводнике или веб-клиенте Диска. */
    private void installContextMenu() {
        tree.addMouseListener(new MouseAdapter() {
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

    private void showContextMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        TreePath treePath = tree.getPathForLocation(event.getX(), event.getY());
        if (treePath == null || !(treePath.getLastPathComponent() instanceof DirectoryNode node) || node.path == null) {
            return;
        }
        tree.setSelectionPath(treePath);
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem(UiIcon.BROWSER, "Открыть в Яндекс Диске", () -> folderActions.showInYandexDisk(node.path)));
        menu.add(menuItem(UiIcon.SOURCE_FOLDER, "Открыть в Проводнике", () -> folderActions.showInExplorer(node.path)));
        folderActions.recoveryFolderFor(node.path).ifPresent(recoveryFolder ->
                menu.add(menuItem(UiIcon.RECOVERY_FOLDER, "Показать в папке восстановления", () ->
                        folderActions.showInRecoveryFolder(recoveryFolder))));
        menu.show(tree, event.getX(), event.getY());
    }

    private JMenuItem menuItem(UiIcon icon, String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setIcon(UiIcons.icon(icon, 18));
        item.setIconTextGap(6);
        item.addActionListener(event -> action.run());
        return item;
    }

    private void applySelection(DirectoryNode node, boolean selected) {
        node.selected = selected;
        for (DirectoryNode child : directoryChildren(node)) {
            applySelection(child, selected);
        }
    }

    /** Подгружает непосредственных потомков один раз и выполняет ожидающие действия на EDT. */
    private void loadChildren(DirectoryNode node, Runnable afterLoaded) {
        if (node.path == null) {
            return;
        }
        if (node.loaded) {
            afterLoaded.run();
            return;
        }
        node.afterLoad.add(afterLoaded);
        if (node.loading) {
            return;
        }
        node.loading = true;
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode("Загрузка…"));
        model.nodeStructureChanged(node);
        new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                return DirectoryTreeSupport.directoriesIn(node.path);
            }

            @Override
            protected void done() {
                node.loading = false;
                node.loaded = true;
                node.removeAllChildren();
                try {
                    for (Path child : get()) {
                        node.add(new DirectoryNode(child, DirectoryTreeSupport.displayName(child), node.selected));
                    }
                    if (node.getChildCount() == 0) {
                        node.add(new DefaultMutableTreeNode("Нет подпапок"));
                    }
                } catch (Exception exception) {
                    ApplicationLogger.error("Не удалось прочитать папку дерева восстановления: " + node.path, exception);
                    node.add(new DefaultMutableTreeNode("Нет доступа к папке"));
                }
                model.nodeStructureChanged(node);
                List<Runnable> callbacks = List.copyOf(node.afterLoad);
                node.afterLoad.clear();
                callbacks.forEach(Runnable::run);
            }
        }.execute();
    }

    /** Узел с флажком, ленивой загрузкой и очередью восстановления сохранённого пути. */
    private static final class DirectoryNode extends DefaultMutableTreeNode {
        private final Path path;
        private final List<Runnable> afterLoad = new ArrayList<>();
        private boolean selected;
        private boolean loaded;
        private boolean loading;

        private DirectoryNode(Path path, String displayName, boolean selected) {
            super(displayName);
            this.path = path;
            this.selected = selected;
            if (path != null) {
                add(new DefaultMutableTreeNode("Разверните папку"));
            }
        }
    }

    /** Действия с каталогом выполняет главное окно, чтобы ошибки попали в общий журнал. */
    interface FolderActions {
        void showInYandexDisk(Path folder);

        void showInExplorer(Path folder);

        Optional<Path> recoveryFolderFor(Path sourceFolder);

        void showInRecoveryFolder(Path folder);
    }
}
