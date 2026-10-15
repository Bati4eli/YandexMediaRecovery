package ru.restoreyandex.ui;

import ru.restoreyandex.domain.FolderSelection;
import ru.restoreyandex.domain.FolderSelectionRule;
import ru.restoreyandex.logging.ApplicationLogger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Встроенное дерево подпапок корня Яндекс Диска с выбором нескольких веток.
 * Узлы подгружаются только при раскрытии; включённая папка наследует выбор
 * всем ещё не загруженным потомкам.
 */
final class FolderSelectionTree extends JPanel {
    private final DirectoryNode placeholderRoot = new DirectoryNode(null, "Выберите корень Яндекс Диска", true);
    private final DefaultTreeModel model = new DefaultTreeModel(placeholderRoot);
    private final JTree tree = new JTree(model);

    FolderSelectionTree() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createTitledBorder("Папки для восстановления"));
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer((currentTree, value, selected, expanded, leaf, row, hasFocus) -> {
            if (value instanceof DirectoryNode node) {
                JCheckBox checkBox = new JCheckBox(node.toString(), node.selected);
                checkBox.setOpaque(false);
                return checkBox;
            }
            return new JLabel(value.toString());
        });
        installLazyLoading();
        installSelectionToggle();

        JButton selectAllButton = new JButton("Выбрать всё");
        selectAllButton.addActionListener(event -> {
            if (model.getRoot() instanceof DirectoryNode root) {
                setSelection(root, true);
                tree.repaint();
            }
        });
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        header.add(new JLabel("Отметьте нужные папки. По умолчанию выбраны все."));
        header.add(selectAllButton);
        add(header, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    /** Загружает новый корень и включает все его подпапки по умолчанию. */
    void reload(Path rootFolder) {
        Path root = rootFolder.toAbsolutePath().normalize();
        DirectoryNode rootNode = new DirectoryNode(root, DirectoryTreeSupport.displayName(root), true);
        model.setRoot(rootNode);
        tree.setSelectionPath(new TreePath(rootNode.getPath()));
        loadChildren(rootNode);
    }

    /** Возвращает компактные правила, а не список всех файлов выбранных подпапок. */
    FolderSelection selection() {
        if (!(model.getRoot() instanceof DirectoryNode root) || root.path == null) {
            return FolderSelection.allFolders();
        }
        List<FolderSelectionRule> rules = new ArrayList<>();
        collectRules(root, true, rules);
        return new FolderSelection(rules);
    }

    private void collectRules(DirectoryNode node, boolean inheritedSelection, List<FolderSelectionRule> rules) {
        if (node.path != null && node.selected != inheritedSelection) {
            rules.add(new FolderSelectionRule(node.path, node.selected));
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            Object child = node.getChildAt(index);
            if (child instanceof DirectoryNode directoryNode) {
                collectRules(directoryNode, node.selected, rules);
            }
        }
    }

    private void installLazyLoading() {
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                Object component = event.getPath().getLastPathComponent();
                if (component instanceof DirectoryNode node) {
                    loadChildren(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                // Загруженные подпапки сохраняются, чтобы повторное раскрытие не читало диск снова.
            }
        });
    }

    private void installSelectionToggle() {
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null || !(path.getLastPathComponent() instanceof DirectoryNode node)) {
                    return;
                }
                if (event.getX() > tree.getPathBounds(path).x + 16) {
                    setSelection(node, !node.selected);
                    tree.repaint();
                }
            }
        });
    }

    private void setSelection(DirectoryNode node, boolean selected) {
        node.selected = selected;
        for (int index = 0; index < node.getChildCount(); index++) {
            Object child = node.getChildAt(index);
            if (child instanceof DirectoryNode directoryNode) {
                setSelection(directoryNode, selected);
            }
        }
    }

    private void loadChildren(DirectoryNode node) {
        if (node.path == null || node.loaded || node.loading) {
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
                tree.expandPath(new TreePath(node.getPath()));
            }
        }.execute();
    }

    /** Узел с наследуемым состоянием флажка и ленивой загрузкой потомков. */
    private static final class DirectoryNode extends DefaultMutableTreeNode {
        private final Path path;
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
}
