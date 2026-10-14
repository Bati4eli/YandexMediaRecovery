package ru.restoreyandex.recovery;

import ru.restoreyandex.domain.RecoveryEvent;
import ru.restoreyandex.domain.RecoveryOutcome;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Записывает надёжную CSV-строку для каждого файла, обработанного за запуск. */
public final class CsvRecoveryLogger implements RecoveryListener, AutoCloseable {
    private static final DateTimeFormatter FILE_NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private final Path file;
    private final BufferedWriter writer;

    /** Создаёт файл журнала с датой и временем в заданной папке программы. */
    public CsvRecoveryLogger(Path programDirectory) throws IOException {
        Objects.requireNonNull(programDirectory);
        Files.createDirectories(programDirectory);
        file = nextAvailableFile(programDirectory);
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        writer.write("\"path\",\"file_name\",\"status\"");
        writer.newLine();
        writer.flush();
    }

    /** Возвращает файл журнала, созданный для текущего запуска. */
    public Path file() {
        return file;
    }

    /**
     * Сразу добавляет и сбрасывает строку на диск, поэтому результат уже
     * обработанного файла сохранится, даже если пользователь остановит программу.
     */
    @Override
    public void onFileProcessed(RecoveryEvent event) {
        try {
            writer.write(csv(event.source().toAbsolutePath().normalize().toString()));
            writer.write(',');
            writer.write(csv(event.source().getFileName().toString()));
            writer.write(',');
            writer.write(csv(csvStatus(event.outcome())));
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // Сбой журнала не должен прерывать восстановление семейных фотографий.
        }
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    private Path nextAvailableFile(Path directory) {
        String baseName = "recovery-log-" + LocalDateTime.now().format(FILE_NAME_TIME);
        Path candidate = directory.resolve(baseName + ".csv");
        int index = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(baseName + "-" + index++ + ".csv");
        }
        return candidate;
    }

    private String csvStatus(RecoveryOutcome outcome) {
        return outcome.csvStatus();
    }

    private String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
