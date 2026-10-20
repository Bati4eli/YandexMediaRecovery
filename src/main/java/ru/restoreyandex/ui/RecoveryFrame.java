package ru.restoreyandex.ui;

import com.sun.jna.platform.win32.Shell32;
import ru.restoreyandex.config.PropertiesSettingsRepository;
import ru.restoreyandex.config.RecoverySettings;
import ru.restoreyandex.config.ApplicationPaths;
import ru.restoreyandex.domain.RecoveryEvent;
import ru.restoreyandex.domain.RecoveryOptions;
import ru.restoreyandex.domain.RecoveryReport;
import ru.restoreyandex.logging.ApplicationLogger;
import ru.restoreyandex.recovery.CsvRecoveryLogger;
import ru.restoreyandex.recovery.RecoveryListener;
import ru.restoreyandex.recovery.RecoveryService;
import ru.restoreyandex.preview.YandexDiskUrlBuilder;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.imageio.ImageIO;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.net.URL;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.stream.IntStream;

/** Главное окно для длительной операции восстановления. */
public final class RecoveryFrame extends JFrame {
    private static final URI PROJECT_REPOSITORY = URI.create("https://github.com/Bati4eli/YandexMediaRecovery");
    private static final String[] SPINNER_FRAMES = {"◐", "◓", "◑", "◒"};
    private static final int MAX_UI_UPDATES_PER_TURN = 64;
    private final JTextField sourceField = new JTextField();
    private final JTextField destinationField = new JTextField();
    private final JProgressBar progressBar = new JProgressBar();
    private final RecoveryLogView logView = new RecoveryLogView(new RecoveryLogView.FileActions() {
        @Override
        public void showInBrowser(Path file) {
            openFileInSystemBrowser(file);
        }

        @Override
        public void showInYandexFolder(Path file) {
            showInExplorer(file, true);
        }

        @Override
        public void showInRecoveryFolder(Path folder) {
            showInExplorer(folder, false);
        }
    });
    private final FolderSelectionTree folderSelectionTree = new FolderSelectionTree();
    private final JCheckBox overwritePreviewsCheckBox = new JCheckBox("Перезаписывать превью");
    private final JCheckBox deleteLowerQualityPreviewsCheckBox = new JCheckBox("Удалять менее качественные превью");
    private final JCheckBox browserDebugModeCheckBox = new JCheckBox("🐞 Отладка браузера (1 поток)");
    private final JComboBox<Integer> parallelismComboBox = new JComboBox<>(
            IntStream.rangeClosed(1, 10).boxed().toArray(Integer[]::new));
    private final JButton yandexLoginButton = new JButton("🔐 Войти в Яндекс Диск…");
    private final JButton yandexLogoutButton = new JButton("🚪 Выход из аккаунта");
    private final JButton sourceBrowseButton = new JButton("Выбрать…");
    private final JButton destinationBrowseButton = new JButton("Выбрать…");
    private final JLabel yandexAccountLabel = new JLabel("Аккаунт Яндекс Диска: не авторизован");
    private final JButton startButton = new JButton("▶️ Начать восстановление");
    private final JButton stopButton = new JButton("⏹ Остановить восстановление");
    private final PropertiesSettingsRepository settingsRepository = new PropertiesSettingsRepository();
    private final RecoveryService recoveryService = new RecoveryService();
    private List<Path> savedSelectedFolders = List.of();
    private boolean yandexAuthenticationConfirmed;
    private RecoveryWorker activeRecoveryWorker;
    private int logoutSpinnerFrame;
    private final Timer logoutSpinnerTimer = new Timer(120, event -> {
        yandexLogoutButton.setText(SPINNER_FRAMES[logoutSpinnerFrame++ % SPINNER_FRAMES.length]
                + " 🚪 Выход из аккаунта");
    });

    /** Создаёт окно и подставляет ранее сохранённые папки, если они есть. */
    public RecoveryFrame() {
        super("Восстановление медиатеки Яндекс Диска");
        RecoverySettings settings = settingsRepository.load();
        sourceField.setText(settings.sourceFolder().toString());
        destinationField.setText(settings.destinationFolder().toString());
        overwritePreviewsCheckBox.setSelected(settings.overwritePreviews());
        deleteLowerQualityPreviewsCheckBox.setSelected(settings.deleteLowerQualityPreviews());
        parallelismComboBox.setSelectedItem(settings.parallelism());
        browserDebugModeCheckBox.setSelected(settings.browserDebugMode());
        savedSelectedFolders = settings.selectedFolders();
        yandexAuthenticationConfirmed = settings.yandexAuthenticationConfirmed();
        setApplicationIcon();
        initialize();
    }

    /** Устанавливает в заголовке Windows логотип, включённый в ресурсы приложения. */
    private void setApplicationIcon() {
        URL logoResource = RecoveryFrame.class.getResource("/logo.png");
        if (logoResource == null) {
            ApplicationLogger.error("Не найден ресурс логотипа приложения", null);
            return;
        }
        try {
            Image image = ImageIO.read(logoResource);
            if (image == null) {
                ApplicationLogger.error("Не удалось декодировать логотип приложения", null);
                return;
            }
            setIconImage(image);
        } catch (IOException exception) {
            ApplicationLogger.error("Не удалось загрузить логотип приложения", exception);
        }
    }

    private void initialize() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(820, 720));
        setLocationByPlatform(true);
        sourceField.setEditable(false);
        destinationField.setEditable(false);
        progressBar.setStringPainted(true);
        progressBar.setString("Ожидание запуска");

        JPanel folders = new JPanel(new GridBagLayout());
        folders.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        addFolderRow(folders, 0, "Корень Яндекс Диска:", sourceField, sourceBrowseButton,
                event -> chooseFolder(sourceField, "Выберите корень локального Яндекс Диска"));
        addFolderRow(folders, 1, "Корень резервной копии:", destinationField, destinationBrowseButton,
                event -> chooseFolder(destinationField, "Выберите корень резервной копии"));
        JPanel options = new JPanel();
        options.setLayout(new javax.swing.BoxLayout(options, javax.swing.BoxLayout.Y_AXIS));
        overwritePreviewsCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        deleteLowerQualityPreviewsCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        browserDebugModeCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.add(overwritePreviewsCheckBox);
        options.add(deleteLowerQualityPreviewsCheckBox);
        options.add(browserDebugModeCheckBox);
        JPanel parallelismOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        parallelismOptions.add(new JLabel("Потоков: "));
        parallelismOptions.add(parallelismComboBox);
        parallelismOptions.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.add(parallelismOptions);
        overwritePreviewsCheckBox.addActionListener(event -> saveCurrentSettings());
        deleteLowerQualityPreviewsCheckBox.addActionListener(event -> saveCurrentSettings());
        parallelismComboBox.addActionListener(event -> saveCurrentSettings());
        folderSelectionTree.setSelectionChangedListener(this::saveCurrentSettings);
        browserDebugModeCheckBox.addActionListener(event -> {
            applyBrowserDebugMode();
            saveCurrentSettings();
        });
        applyBrowserDebugMode();

        GridBagConstraints optionConstraints = new GridBagConstraints();
        optionConstraints.gridx = 0;
        optionConstraints.gridy = 2;
        optionConstraints.gridwidth = 3;
        optionConstraints.anchor = GridBagConstraints.WEST;
        optionConstraints.insets = new Insets(8, 0, 4, 6);
        folders.add(options, optionConstraints);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(yandexLoginButton);
        controls.add(yandexLogoutButton);
        controls.add(yandexAccountLabel);
        showSavedYandexAccount();
        refreshYandexAccountControls();
        yandexLoginButton.addActionListener(event -> openYandexLogin());
        yandexLogoutButton.addActionListener(event -> logoutYandexAccount());
        startButton.addActionListener(this::startRecovery);

        JPanel top = new JPanel(new BorderLayout());
        top.add(folders, BorderLayout.CENTER);
        top.add(controls, BorderLayout.SOUTH);

        JSplitPane workspace = new JSplitPane(JSplitPane.VERTICAL_SPLIT, folderSelectionTree, new JScrollPane(logView));
        workspace.setResizeWeight(0.45);
        workspace.setDividerLocation(255);
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        center.add(progressBar, BorderLayout.NORTH);
        center.add(workspace, BorderLayout.CENTER);
        JPanel recoveryControl = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        recoveryControl.add(startButton);
        recoveryControl.add(stopButton);
        JLabel repositoryLink = new JLabel("<html><a href=\"#\">GitHub: YandexMediaRecovery</a></html>");
        repositoryLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        repositoryLink.setToolTipText(PROJECT_REPOSITORY.toString());
        repositoryLink.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                openProjectRepository();
            }
        });
        JPanel bottomControls = new JPanel(new BorderLayout());
        bottomControls.add(recoveryControl, BorderLayout.WEST);
        bottomControls.add(repositoryLink, BorderLayout.EAST);
        stopButton.setEnabled(false);
        stopButton.addActionListener(event -> stopRecovery());
        center.add(bottomControls, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        reloadFolderTree(Path.of(sourceField.getText()), savedSelectedFolders);
        pack();
    }

    private void addFolderRow(JPanel panel, int row, String label, JTextField field, JButton browseButton,
                              java.awt.event.ActionListener browseAction) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.insets = new Insets(4, 0, 4, 6);
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        browseButton.addActionListener(browseAction);
        panel.add(browseButton, constraints);
    }

    private void chooseFolder(JTextField field, String title) {
        JFileChooser chooser = new JFileChooser(field.getText());
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path folder = chooser.getSelectedFile().toPath();
            field.setText(folder.toString());
            if (field == sourceField) {
                reloadFolderTree(folder);
            }
            saveCurrentSettings();
        }
    }

    private void startRecovery(ActionEvent ignored) {
        Path source = Path.of(sourceField.getText());
        Path destination = Path.of(destinationField.getText());
        RecoveryOptions options = new RecoveryOptions(overwritePreviewsCheckBox.isSelected(),
                deleteLowerQualityPreviewsCheckBox.isSelected(), selectedParallelism(),
                browserDebugModeCheckBox.isSelected(), folderSelectionTree.selection());
        saveCurrentSettings();
        startButton.setEnabled(false);
        yandexLoginButton.setEnabled(false);
        yandexLogoutButton.setEnabled(false);
        sourceBrowseButton.setEnabled(false);
        destinationBrowseButton.setEnabled(false);
        folderSelectionTree.setSelectionEditingEnabled(false);
        stopButton.setEnabled(true);
        replaceLog("Подготовка списка файлов…\n");
        progressBar.setIndeterminate(true);
        activeRecoveryWorker = new RecoveryWorker(source, destination, options);
        activeRecoveryWorker.execute();
    }

    /** Останавливает рабочие задачи и немедленно закрывает фоновый Chrome. */
    private void stopRecovery() {
        if (activeRecoveryWorker == null) {
            return;
        }
        stopButton.setEnabled(false);
        appendLog("⏹ Останавливаю восстановление и закрываю Chrome…\n");
        recoveryService.cancel();
    }

    /** Открывает страницу файла Яндекс Диска в браузере системы без блокировки меню Swing. */
    private void openFileInSystemBrowser(Path file) {
        URI viewerUri;
        try {
            viewerUri = YandexDiskUrlBuilder.viewerUri(Path.of(sourceField.getText()), file);
        } catch (Exception exception) {
            reportSystemBrowserError(file, exception);
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    throw new UnsupportedOperationException("Системный браузер недоступен");
                }
                Desktop.getDesktop().browse(viewerUri);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    reportSystemBrowserError(file, exception);
                } catch (ExecutionException exception) {
                    reportSystemBrowserError(file,
                            exception.getCause() == null ? exception : exception.getCause());
                }
            }
        }.execute();
    }

    /** Сообщает об ошибке запуска внешнего браузера после возврата управления UI. */
    private void reportSystemBrowserError(Path file, Throwable exception) {
        ApplicationLogger.error("Не удалось открыть файл в системном браузере: " + file, exception);
        appendLog("Не удалось открыть в браузере: " + file + "\n");
    }

    /** Открывает содержащую папку через Windows Shell, который корректно обрабатывает кириллицу в путях. */
    private void showInExplorer(Path path, boolean selectFile) {
        Path folder = selectFile ? path.getParent() : path;
        if (folder == null) {
            reportExplorerError(path, new IllegalArgumentException("Не определена папка файла"));
            return;
        }
        Path normalizedFolder = folder.toAbsolutePath().normalize();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (!Files.isDirectory(normalizedFolder)) {
                    throw new IOException("Папка не существует: " + normalizedFolder);
                }
                if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
                    long result = Shell32.INSTANCE.ShellExecute(null, "open", normalizedFolder.toString(), null, null, 1)
                            .longValue();
                    if (result <= 32) {
                        throw new IOException("Windows Shell вернул код " + result);
                    }
                } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(normalizedFolder.toFile());
                } else {
                    throw new UnsupportedOperationException("Системный файловый менеджер недоступен");
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    reportExplorerError(normalizedFolder, exception);
                } catch (ExecutionException exception) {
                    reportExplorerError(normalizedFolder,
                            exception.getCause() == null ? exception : exception.getCause());
                }
            }
        }.execute();
    }

    /** Логирует ошибку открытия папки после возврата управления интерфейсу. */
    private void reportExplorerError(Path path, Throwable exception) {
        try {
            ApplicationLogger.error("Не удалось открыть Проводник: " + path, exception);
            appendLog("Не удалось открыть папку: " + path + "\n");
        } catch (RuntimeException ignored) {
            // Ошибка журнала не должна завершать поток SwingWorker.
        }
    }

    /** Открывает страницу исходного кода в системном браузере пользователя. */
    private void openProjectRepository() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw new UnsupportedOperationException("Системный браузер недоступен");
            }
            Desktop.getDesktop().browse(PROJECT_REPOSITORY);
        } catch (Exception exception) {
            ApplicationLogger.error("Не удалось открыть репозиторий проекта", exception);
            JOptionPane.showMessageDialog(this, "Не удалось открыть страницу проекта:\n" + PROJECT_REPOSITORY,
                    "GitHub", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** В отладке Chrome видим и обращение к нему всегда выполняется в один поток. */
    private void applyBrowserDebugMode() {
        boolean debugMode = browserDebugModeCheckBox.isSelected();
        if (debugMode) {
            parallelismComboBox.setSelectedItem(1);
        }
        parallelismComboBox.setEnabled(!debugMode);
    }

    /** Открывает отдельное окно Chrome; данные входа пользователь вводит только в браузере. */
    private void openYandexLogin() {
        yandexLoginButton.setEnabled(false);
        yandexLogoutButton.setEnabled(false);
        appendLog("Открываю Chrome для входа в Яндекс Диск…\n");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                recoveryService.openYandexDiskLogin();
                return null;
            }

            @Override
            protected void done() {
                yandexLoginButton.setEnabled(true);
                try {
                    get();
                    yandexAuthenticationConfirmed = true;
                    saveCurrentSettings();
                    showAuthenticatedYandexAccount();
                    appendLog("✅ Вход в Яндекс Диск подтверждён. Chrome закрыт; браузерные превью готовы.\n");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    ApplicationLogger.error("Ожидание открытия Chrome прервано", exception);
                } catch (ExecutionException exception) {
                    String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
                    ApplicationLogger.error("Не удалось открыть Chrome", exception.getCause() == null ? exception : exception.getCause());
                    appendLog("Не удалось открыть Chrome: " + message + "\n");
                    JOptionPane.showMessageDialog(RecoveryFrame.this, "Не удалось открыть Chrome: " + message,
                            "Яндекс Диск", JOptionPane.WARNING_MESSAGE);
                } finally {
                    refreshYandexAccountControls();
                }
                logView.scrollToEnd();
            }
        }.execute();
    }

    /** Выходит из профиля приложения в фоновом Chrome и сбрасывает сохранённый статус только после успеха. */
    private void logoutYandexAccount() {
        yandexLoginButton.setEnabled(false);
        yandexLogoutButton.setEnabled(false);
        startButton.setEnabled(false);
        logoutSpinnerFrame = 0;
        yandexLogoutButton.setText(SPINNER_FRAMES[logoutSpinnerFrame++] + " 🚪 Выход из аккаунта");
        logoutSpinnerTimer.start();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                recoveryService.logoutYandexDisk();
                return null;
            }

            @Override
            protected void done() {
                logoutSpinnerTimer.stop();
                yandexLogoutButton.setText("🚪 Выход из аккаунта");
                yandexLoginButton.setEnabled(true);
                startButton.setEnabled(true);
                try {
                    get();
                    yandexAuthenticationConfirmed = false;
                    saveCurrentSettings();
                    yandexAccountLabel.setText("Аккаунт Яндекс Диска: не авторизован");
                    refreshYandexAccountControls();
                    appendLog("✅ Выполнен выход из аккаунта Яндекс Диска.\n");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    ApplicationLogger.error("Ожидание выхода из аккаунта прервано", exception);
                } catch (ExecutionException exception) {
                    String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
                    ApplicationLogger.error("Не удалось выйти из аккаунта Яндекс Диска",
                            exception.getCause() == null ? exception : exception.getCause());
                    appendLog("Не удалось выйти из аккаунта: " + message + "\n");
                    JOptionPane.showMessageDialog(RecoveryFrame.this, "Не удалось выйти из аккаунта: " + message,
                            "Яндекс Диск", JOptionPane.WARNING_MESSAGE);
                }
                refreshYandexAccountControls();
                logView.scrollToEnd();
            }
        }.execute();
    }

    /** Применяет ограниченную пачку событий на EDT, чтобы не блокировать взаимодействие с окном. */
    private void applyRecoveryUiUpdates(List<RecoveryUiUpdate> updates, Path sourceRoot, Path destinationRoot) {
        RecoveryEvent latestCompleted = null;
        for (RecoveryUiUpdate update : updates) {
            if (update.isStarted()) {
                logView.appendRunningFile(RecoveryLogLineFormatter.formatRunning(
                        update.startedFile(), update.started(), update.totalFiles(), sourceRoot), update.startedFile(),
                        recoveryFolderFor(update.startedFile(), sourceRoot, destinationRoot), false);
                continue;
            }
            RecoveryEvent event = update.completedEvent();
            logView.replaceRunningFile(RecoveryLogLineFormatter.format(event, sourceRoot), event.source(),
                    recoveryFolderFor(event.source(), sourceRoot, destinationRoot), false);
            latestCompleted = event;
        }
        if (latestCompleted != null) {
            updateProgressBar(latestCompleted);
        }
        logView.scrollToEnd();
    }

    private void updateProgressBar(RecoveryEvent event) {
        progressBar.setIndeterminate(false);
        progressBar.setMaximum(Math.max(event.total(), 1));
        progressBar.setValue(event.processed());
        progressBar.setString(event.processed() + " / " + event.total());
        progressBar.repaint();
    }

    /** Возвращает папку назначения, соответствующую исходной строке журнала. */
    private Path recoveryFolderFor(Path source, Path sourceRoot, Path destinationRoot) {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedRoot = sourceRoot.toAbsolutePath().normalize();
        if (!normalizedSource.startsWith(normalizedRoot)) {
            return destinationRoot;
        }
        Path relative = normalizedRoot.relativize(normalizedSource);
        Path parent = relative.getParent();
        return parent == null ? destinationRoot : destinationRoot.resolve(parent);
    }

    private void saveCurrentSettings() {
        try {
            settingsRepository.save(new RecoverySettings(Path.of(sourceField.getText()), Path.of(destinationField.getText()),
                    overwritePreviewsCheckBox.isSelected(), deleteLowerQualityPreviewsCheckBox.isSelected(),
                    selectedParallelism(), browserDebugModeCheckBox.isSelected(), folderSelectionTree.selectedFolders(),
                    yandexAuthenticationConfirmed));
        } catch (Exception exception) {
            ApplicationLogger.error("Не удалось сохранить настройки", exception);
            JOptionPane.showMessageDialog(this, "Не удалось сохранить настройки: " + exception.getMessage(),
                    "Настройки", JOptionPane.WARNING_MESSAGE);
        }
    }

    private int selectedParallelism() {
        return (Integer) parallelismComboBox.getSelectedItem();
    }

    /** Показывает email, сохранённый после ранее успешно подтверждённого входа. */
    private void showSavedYandexAccount() {
        if (yandexAuthenticationConfirmed) {
            yandexAccountLabel.setText("✅ Авторизация в Яндекс Диске подтверждена (сохранено)");
        }
    }

    /** Отображает подтверждённый текущий вход без чтения данных профиля пользователя. */
    private void showAuthenticatedYandexAccount() {
        yandexAccountLabel.setText("✅ Авторизация в Яндекс Диске подтверждена");
    }

    /** Показывает только действие, соответствующее сохранённому состоянию авторизации. */
    private void refreshYandexAccountControls() {
        yandexLoginButton.setVisible(!yandexAuthenticationConfirmed);
        yandexLoginButton.setEnabled(!yandexAuthenticationConfirmed);
        yandexLogoutButton.setVisible(yandexAuthenticationConfirmed);
        yandexLogoutButton.setEnabled(yandexAuthenticationConfirmed);
    }

    private void reloadFolderTree(Path sourceRoot) {
        reloadFolderTree(sourceRoot, java.util.List.of());
    }

    private void reloadFolderTree(Path sourceRoot, java.util.List<Path> selectedFolders) {
        try {
            folderSelectionTree.reload(sourceRoot, selectedFolders);
        } catch (Exception exception) {
            ApplicationLogger.error("Не удалось построить дерево папок Яндекс Диска", exception);
        }
    }

    private final class RecoveryWorker extends SwingWorker<RecoveryReport, Void> {
        private final Path source;
        private final Path destination;
        private final RecoveryOptions options;
        private final UiUpdatePump<RecoveryUiUpdate> uiUpdates;

        private RecoveryWorker(Path source, Path destination, RecoveryOptions options) {
            this.source = source;
            this.destination = destination;
            this.options = options;
            uiUpdates = new UiUpdatePump<>(MAX_UI_UPDATES_PER_TURN, SwingUtilities::invokeLater,
                    updates -> applyRecoveryUiUpdates(updates, source, destination));
        }

        @Override
        protected RecoveryReport doInBackground() throws Exception {
            try (CsvRecoveryLogger csvLogger = new CsvRecoveryLogger(ApplicationPaths.programDirectory())) {
                return recoveryService.recover(source, destination, options, new RecoveryListener() {
                    @Override
                    public void onStarted(int totalFiles) {
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setIndeterminate(false);
                            progressBar.setMaximum(Math.max(totalFiles, 1));
                            progressBar.setValue(0);
                            progressBar.setString("0 / " + totalFiles);
                            appendLog("CSV-лог: " + csvLogger.file() + "\n");
                        });
                    }

                    @Override
                    public void onFileStarted(Path file, int started, int totalFiles) {
                        uiUpdates.submit(RecoveryUiUpdate.started(file, started, totalFiles));
                    }

                    @Override
                    public void onFileProcessed(RecoveryEvent event) {
                        csvLogger.onFileProcessed(event);
                        if (event.outcome() != ru.restoreyandex.domain.RecoveryOutcome.ALREADY_OK) {
                            ApplicationLogger.info(RecoveryLogLineFormatter.format(event, source));
                        }
                        uiUpdates.submit(RecoveryUiUpdate.completed(event));
                    }
                });
            }
        }

        @Override
        protected void done() {
            try {
                RecoveryReport report = get();
                uiUpdates.runWhenDrained(() -> {
                    restoreRecoveryControls();
                    showRecoveryCompleted(report);
                });
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                restoreRecoveryControls();
                ApplicationLogger.error("Ожидание восстановления прервано", exception);
            } catch (CancellationException exception) {
                restoreRecoveryControls();
                appendLog("⏹ Восстановление остановлено пользователем.\n");
            } catch (ExecutionException exception) {
                restoreRecoveryControls();
                String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
                ApplicationLogger.error("Восстановление остановлено", exception.getCause() == null ? exception : exception.getCause());
                appendLog("\nОстановка: " + message + "\n");
                JOptionPane.showMessageDialog(RecoveryFrame.this, "Операция не началась: " + message,
                        "Ошибка", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void restoreRecoveryControls() {
            startButton.setEnabled(true);
            yandexLoginButton.setEnabled(true);
            yandexLogoutButton.setEnabled(yandexAuthenticationConfirmed);
            sourceBrowseButton.setEnabled(true);
            destinationBrowseButton.setEnabled(true);
            folderSelectionTree.setSelectionEditingEnabled(true);
            stopButton.setEnabled(false);
            activeRecoveryWorker = null;
        }

        private void showRecoveryCompleted(RecoveryReport report) {
            appendLog("\n" + report.toDisplayString() + "\n");
            JOptionPane.showMessageDialog(RecoveryFrame.this, report.toDisplayString(), "Восстановление завершено",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** Описывает один старт или финальный результат файла, ожидающий отображения в журнале. */
    private record RecoveryUiUpdate(RecoveryEvent completedEvent, Path startedFile, int started, int totalFiles) {
        private static RecoveryUiUpdate started(Path file, int started, int totalFiles) {
            return new RecoveryUiUpdate(null, file, started, totalFiles);
        }

        private static RecoveryUiUpdate completed(RecoveryEvent event) {
            return new RecoveryUiUpdate(event, null, 0, 0);
        }

        private boolean isStarted() {
            return completedEvent == null;
        }
    }

    /** Выводит сообщение и в видимый журнал, и в stdout запущенного приложения. */
    private void appendLog(String message) {
        logView.appendMessage(message);
        ApplicationLogger.info(message.stripTrailing());
    }

    /** Заменяет видимый журнал и дублирует начальное сообщение в stdout. */
    private void replaceLog(String message) {
        logView.replaceWith(message);
        ApplicationLogger.info(message.stripTrailing());
    }
}
