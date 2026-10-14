package ru.restoreyandex.ui;

import ru.restoreyandex.config.PropertiesSettingsRepository;
import ru.restoreyandex.config.RecoverySettings;
import ru.restoreyandex.config.ApplicationPaths;
import ru.restoreyandex.domain.RecoveryEvent;
import ru.restoreyandex.domain.RecoveryOptions;
import ru.restoreyandex.domain.RecoveryReport;
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
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

/** Главное окно для длительной операции восстановления. */
public final class RecoveryFrame extends JFrame {
    private final JTextField sourceField = new JTextField();
    private final JTextField destinationField = new JTextField();
    private final JProgressBar progressBar = new JProgressBar();
    private final JTextArea logArea = new JTextArea();
    private final JCheckBox overwritePreviewsCheckBox = new JCheckBox("Перезаписывать превью PREW_");
    private final JComboBox<Integer> parallelismComboBox = new JComboBox<>(
            IntStream.rangeClosed(1, 10).boxed().toArray(Integer[]::new));
    private final JButton startButton = new JButton("Начать восстановление");
    private final PropertiesSettingsRepository settingsRepository = new PropertiesSettingsRepository();
    private final RecoveryService recoveryService = new RecoveryService();

    /** Создаёт окно и подставляет ранее сохранённые папки, если они есть. */
    public RecoveryFrame() {
        super("Восстановление медиатеки Яндекс Диска");
        RecoverySettings settings = settingsRepository.load();
        sourceField.setText(settings.sourceFolder().toString());
        destinationField.setText(settings.destinationFolder().toString());
        overwritePreviewsCheckBox.setSelected(settings.overwritePreviews());
        parallelismComboBox.setSelectedItem(settings.parallelism());
        initialize();
    }

    private void initialize() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(820, 500));
        setLocationByPlatform(true);
        sourceField.setEditable(false);
        destinationField.setEditable(false);
        logArea.setEditable(false);
        logArea.setFont(logArea.getFont().deriveFont(Font.PLAIN, 18f));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        progressBar.setStringPainted(true);
        progressBar.setString("Ожидание запуска");

        JPanel folders = new JPanel(new GridBagLayout());
        folders.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        addFolderRow(folders, 0, "Откуда (исходная):", sourceField, event -> chooseFolder(sourceField, "Выберите исходную папку"));
        addFolderRow(folders, 1, "Куда (восстановить):", destinationField,
                event -> chooseFolder(destinationField, "Выберите папку восстановления"));
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        options.add(overwritePreviewsCheckBox);
        options.add(new JLabel("   Потоков: "));
        options.add(parallelismComboBox);
        overwritePreviewsCheckBox.addActionListener(event -> saveCurrentSettings());
        parallelismComboBox.addActionListener(event -> saveCurrentSettings());

        GridBagConstraints optionConstraints = new GridBagConstraints();
        optionConstraints.gridx = 1;
        optionConstraints.gridy = 2;
        optionConstraints.anchor = GridBagConstraints.WEST;
        optionConstraints.insets = new Insets(4, 0, 4, 6);
        folders.add(options, optionConstraints);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(startButton);
        startButton.addActionListener(this::startRecovery);

        JPanel top = new JPanel(new BorderLayout());
        top.add(folders, BorderLayout.CENTER);
        top.add(controls, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        center.add(progressBar, BorderLayout.NORTH);
        center.add(new JScrollPane(logArea), BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
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
            field.setText(chooser.getSelectedFile().toPath().toString());
            saveCurrentSettings();
        }
    }

    private void startRecovery(ActionEvent ignored) {
        Path source = Path.of(sourceField.getText());
        Path destination = Path.of(destinationField.getText());
        RecoveryOptions options = new RecoveryOptions(overwritePreviewsCheckBox.isSelected(), selectedParallelism());
        saveCurrentSettings();
        startButton.setEnabled(false);
        logArea.setText("Подготовка списка файлов…\n");
        progressBar.setIndeterminate(true);
        new RecoveryWorker(source, destination, options).execute();
    }

    private void updateProgress(RecoveryEvent event) {
        progressBar.setIndeterminate(false);
        progressBar.setMaximum(Math.max(event.total(), 1));
        progressBar.setValue(event.processed());
        progressBar.setString(event.processed() + " / " + event.total());
        logArea.append(event.outcome().screenStatus() + " — " + event.message() + " — " + event.source() + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void saveCurrentSettings() {
        try {
            settingsRepository.save(new RecoverySettings(Path.of(sourceField.getText()), Path.of(destinationField.getText()),
                    overwritePreviewsCheckBox.isSelected(), selectedParallelism()));
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "Не удалось сохранить настройки: " + exception.getMessage(),
                    "Настройки", JOptionPane.WARNING_MESSAGE);
        }
    }

    private int selectedParallelism() {
        return (Integer) parallelismComboBox.getSelectedItem();
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
                            logArea.append("CSV-лог: " + csvLogger.file() + "\n");
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
                        SwingUtilities.invokeLater(() -> updateProgress(event));
                    }
                });
            }
        }

        @Override
        protected void done() {
            startButton.setEnabled(true);
            try {
                RecoveryReport report = get();
                logArea.append("\n" + report.toDisplayString() + "\n");
                JOptionPane.showMessageDialog(RecoveryFrame.this, report.toDisplayString(), "Восстановление завершено",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException exception) {
                String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
                logArea.append("\nОстановка: " + message + "\n");
                JOptionPane.showMessageDialog(RecoveryFrame.this, "Операция не началась: " + message,
                        "Ошибка", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
