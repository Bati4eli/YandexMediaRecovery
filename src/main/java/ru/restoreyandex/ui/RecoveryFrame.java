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
import javax.imageio.ImageIO;
import java.awt.BorderLayout;
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
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

/** Главное окно для длительной операции восстановления. */
public final class RecoveryFrame extends JFrame {
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
    private final JButton yandexLoginButton = new JButton("Войти в Яндекс Диск…");
    private final JButton startButton = new JButton("▶️ Начать восстановление");
    private final JButton stopButton = new JButton("⏹ Остановить восстановление");
    private final PropertiesSettingsRepository settingsRepository = new PropertiesSettingsRepository();
    private final RecoveryService recoveryService = new RecoveryService();
    private RecoveryWorker activeRecoveryWorker;

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
        addFolderRow(folders, 0, "Корень Яндекс Диска:", sourceField,
                event -> chooseFolder(sourceField, "Выберите корень локального Яндекс Диска"));
        addFolderRow(folders, 1, "Корень резервной копии:", destinationField,
                event -> chooseFolder(destinationField, "Выберите корень резервной копии"));
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        options.add(overwritePreviewsCheckBox);
        options.add(deleteLowerQualityPreviewsCheckBox);
        options.add(browserDebugModeCheckBox);
        options.add(new JLabel("   Потоков: "));
        options.add(parallelismComboBox);
        overwritePreviewsCheckBox.addActionListener(event -> saveCurrentSettings());
        deleteLowerQualityPreviewsCheckBox.addActionListener(event -> saveCurrentSettings());
        parallelismComboBox.addActionListener(event -> saveCurrentSettings());
        browserDebugModeCheckBox.addActionListener(event -> {
            applyBrowserDebugMode();
            saveCurrentSettings();
        });
        applyBrowserDebugMode();

        GridBagConstraints optionConstraints = new GridBagConstraints();
        optionConstraints.gridx = 1;
        optionConstraints.gridy = 2;
        optionConstraints.anchor = GridBagConstraints.WEST;
        optionConstraints.insets = new Insets(4, 0, 4, 6);
        folders.add(options, optionConstraints);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(yandexLoginButton);
        yandexLoginButton.addActionListener(event -> openYandexLogin());
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
        stopButton.setEnabled(false);
        stopButton.addActionListener(event -> stopRecovery());
        center.add(recoveryControl, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        reloadFolderTree(Path.of(sourceField.getText()));
        pack();
    }

    private void addFolderRow(JPanel panel, int row, String label, JTextField field,
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
        JButton browseButton = new JButton("Выбрать…");
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
                    appendLog("Вход в Яндекс Диск подтверждён. Chrome закрыт; браузерные превью готовы.\n");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    ApplicationLogger.error("Ожидание открытия Chrome прервано", exception);
                } catch (ExecutionException exception) {
                    String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
                    ApplicationLogger.error("Не удалось открыть Chrome", exception.getCause() == null ? exception : exception.getCause());
                    appendLog("Не удалось открыть Chrome: " + message + "\n");
                    JOptionPane.showMessageDialog(RecoveryFrame.this, "Не удалось открыть Chrome: " + message,
                            "Яндекс Диск", JOptionPane.WARNING_MESSAGE);
                }
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
    }

    private void saveCurrentSettings() {
        try {
            settingsRepository.save(new RecoverySettings(Path.of(sourceField.getText()), Path.of(destinationField.getText()),
                    overwritePreviewsCheckBox.isSelected(), deleteLowerQualityPreviewsCheckBox.isSelected(),
                    selectedParallelism(), browserDebugModeCheckBox.isSelected()));
        } catch (Exception exception) {
            ApplicationLogger.error("Не удалось сохранить настройки", exception);
            JOptionPane.showMessageDialog(this, "Не удалось сохранить настройки: " + exception.getMessage(),
                    "Настройки", JOptionPane.WARNING_MESSAGE);
        }
    }

    private int selectedParallelism() {
        return (Integer) parallelismComboBox.getSelectedItem();
    }

    private void reloadFolderTree(Path sourceRoot) {
        try {
            folderSelectionTree.reload(sourceRoot);
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
                    public void onFileStarted(Path source, int processed, int totalFiles) {
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setIndeterminate(false);
                            progressBar.setMaximum(Math.max(totalFiles, 1));
                            progressBar.setValue(Math.max(processed - 1, 0));
                            progressBar.setString(processed + " / " + totalFiles + " — проверка");
                        });
                    }

                    @Override
                    public void onFileProcessed(RecoveryEvent event) {
                        csvLogger.onFileProcessed(event);
                        SwingUtilities.invokeLater(() -> updateProgress(event, source));
                    }
                });
            }
        }

        @Override
        protected void done() {
            startButton.setEnabled(true);
            yandexLoginButton.setEnabled(true);
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
