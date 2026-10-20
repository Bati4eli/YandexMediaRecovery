package ru.restoreyandex.ui;

import ru.restoreyandex.domain.RecoveryEvent;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Формирует компактную строку для журнала в окне приложения.
 *
 * <p>В отличие от CSV-журнала, строка не содержит путь назначения и техническое
 * описание операции: они часто повторяют друг друга и делают журнал нечитаемым.
 * Путь исходного файла сокращается относительно выбранной исходной папки.</p>
 */
final class RecoveryLogLineFormatter {
    private RecoveryLogLineFormatter() {
    }

    /**
     * Возвращает статус с эмодзи и путь, относительный к исходной папке.
     * Если событие относится к пути вне исходной папки, сохраняет полный
     * нормализованный путь, чтобы не скрыть диагностическую информацию.
     */
    static String format(RecoveryEvent event, Path sourceRoot) {
        Objects.requireNonNull(event);
        Objects.requireNonNull(sourceRoot);
        return event.outcome().screenStatus() + " — " + displayPath(event.source(), sourceRoot);
    }

    /** Формирует временную строку для файла, который уже взят рабочим потоком. */
    static String formatRunning(Path source, int started, int total, Path sourceRoot) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(sourceRoot);
        return "⏳ В работе [%d / %d] — %s".formatted(started, total, displayPath(source, sourceRoot));
    }

    private static Path displayPath(Path source, Path sourceRoot) {
        Path normalizedRoot = sourceRoot.toAbsolutePath().normalize();
        Path normalizedSource = source.toAbsolutePath().normalize();
        return normalizedSource.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedSource)
                : normalizedSource;
    }
}
