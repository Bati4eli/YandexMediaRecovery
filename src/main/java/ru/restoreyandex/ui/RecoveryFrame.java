package ru.restoreyandex.ui;

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
import ru.restoreyandex.preview.YandexAccountInfo;

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
import javax.swing.JTextArea;
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
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.stream.IntStream;

/** Главное окно для длительной операции восстановления. */
public final class RecoveryFrame extends JFrame {
    private static final URI PROJECT_REPOSITORY = URI.create("https://github.com/Bati4eli/YandexMediaRecovery");
    private static final String[] SPINNER_FRAMES = {"◐", "◓", "◑", "◒"};
    private final JTextField sourceField = new JTextField();
    private final JTextField destinationField = new JTextField();
    private final JProgressBar progressBar = new JProgressBar();
    private final JTextArea logArea = new JTextArea();
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
    private String yandexAccountEmail;
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
        yandexAccountEmail = settings.yandexAccountEmail();
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
        logArea.setEditable(false);
        logArea.setFont(logArea.getFont().deriveFont(Font.PLAIN, 16f));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
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

        JSplitPane workspace = new JSplitPane(JSplitPane.VERTICAL_SPLIT, folderSelectionTree, new JScrollPane(logArea));
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
        new SwingWorker<YandexAccountInfo, Void>() {
            @Override
            protected YandexAccountInfo doInBackground() throws Exception {
                return recoveryService.openYandexDiskLogin();
            }

            @Override
            protected void done() {
                yandexLoginButton.setEnabled(true);
                try {
                    YandexAccountInfo account = get();
                    yandexAuthenticationConfirmed = true;
                    if (account.hasEmail()) {
                        yandexAccountEmail = account.email();
                    }
                    saveCurrentSettings();
                    showAuthenticatedYandexAccount(account);
                    refreshYandexAccountControls();
                    appendLog("✅ Вход в Яндекс Диск подтверждён"
                            + (account.hasEmail() ? ": " + account.email() : ".")
                            + " Chrome закрыт; браузерные превью готовы.\n");
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
                logArea.setCaretPosition(logArea.getDocument().getLength());
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
                    yandexAccountEmail = "";
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
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        }.execute();
    }

    private void updateProgress(RecoveryEvent event, Path sourceRoot) {
        progressBar.setIndeterminate(false);
        progressBar.setMaximum(Math.max(event.total(), 1));
        progressBar.setValue(event.processed());
        progressBar.setString(event.processed() + " / " + event.total());
        if (event.outcome() != ru.restoreyandex.domain.RecoveryOutcome.ALREADY_OK) {
            appendLog(RecoveryLogLineFormatter.format(event, sourceRoot) + "\n");
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
        progressBar.paintImmediately(progressBar.getVisibleRect());
    }

    private void saveCurrentSettings() {
        try {
            settingsRepository.save(new RecoverySettings(Path.of(sourceField.getText()), Path.of(destinationField.getText()),
                    overwritePreviewsCheckBox.isSelected(), deleteLowerQualityPreviewsCheckBox.isSelected(),
                    selectedParallelism(), browserDebugModeCheckBox.isSelected(), folderSelectionTree.selectedFolders(),
                    yandexAccountEmail, yandexAuthenticationConfirmed));
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
            yandexAccountLabel.setText(yandexAccountEmail.isBlank()
                    ? "✅ Авторизация в Яндекс Диске подтверждена (сохранено)"
                    : "✅ Авторизация: " + yandexAccountEmail + " (сохранено)");
        }
    }

    /** Отображает успешный результат текущего входа, даже если профиль не раскрыл email. */
    private void showAuthenticatedYandexAccount(YandexAccountInfo account) {
        if (account.hasDisplayName() && account.hasEmail()) {
            yandexAccountLabel.setText("✅ Авторизация: " + account.displayName() + " (" + account.email() + ")");
        } else if (account.hasEmail()) {
            yandexAccountLabel.setText("✅ Авторизация: " + account.email());
        } else {
            yandexAccountLabel.setText("✅ Авторизация в Яндекс Диске подтверждена");
        }
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

        private RecoveryWorker(Path source, Path destination, RecoveryOptions options) {
            this.source = source;
            this.destination = destination;
            this.options = options;
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
                    public void onFileProcessed(RecoveryEvent event) {
                        csvLogger.onFileProcessed(event);
                        updateProgressOnEdt(event, source);
                    }
                });
            }
        }

        @Override
        protected void done() {
            startButton.setEnabled(true);
            yandexLoginButton.setEnabled(true);
            yandexLogoutButton.setEnabled(yandexAuthenticationConfirmed);
            sourceBrowseButton.setEnabled(true);
            destinationBrowseButton.setEnabled(true);
            folderSelectionTree.setSelectionEditingEnabled(true);
            stopButton.setEnabled(false);
            activeRecoveryWorker = null;
            try {
                RecoveryReport report = get();
                appendLog("\n" + report.toDisplayString() + "\n");
                JOptionPane.showMessageDialog(RecoveryFrame.this, report.toDisplayString(), "Восстановление завершено",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                ApplicationLogger.error("Ожидание восстановления прервано", exception);
            } catch (CancellationException exception) {
                appendLog("⏹ Восстановление остановлено пользователем.\n");
            } catch (ExecutionException exception) {
                String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
                ApplicationLogger.error("Восстановление остановлено", exception.getCause() == null ? exception : exception.getCause());
                appendLog("\nОстановка: " + message + "\n");
                JOptionPane.showMessageDialog(RecoveryFrame.this, "Операция не началась: " + message,
                        "Ошибка", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Подтверждает отрисовку каждого завершённого файла до перехода к
     * следующему. Это не даёт очереди EDT скрыть прогресс при массовом
     * пропуске уже восстановленных файлов.
     */
    private void updateProgressOnEdt(RecoveryEvent event, Path sourceRoot) {
        if (SwingUtilities.isEventDispatchThread()) {
            updateProgress(event, sourceRoot);
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> updateProgress(event, sourceRoot));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ApplicationLogger.error("Поток восстановления прерван при обновлении прогресса", exception);
        } catch (InvocationTargetException exception) {
            ApplicationLogger.error("Не удалось обновить прогресс восстановления", exception.getCause());
        }
    }

    /** Выводит сообщение и в видимый журнал, и в stdout запущенного приложения. */
    private void appendLog(String message) {
        logArea.append(message);
        ApplicationLogger.info(message.stripTrailing());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** Заменяет видимый журнал и дублирует начальное сообщение в stdout. */
    private void replaceLog(String message) {
        logArea.setText(message);
        ApplicationLogger.info(message.stripTrailing());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
