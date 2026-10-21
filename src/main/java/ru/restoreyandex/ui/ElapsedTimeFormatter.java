package ru.restoreyandex.ui;

import java.time.Duration;
import java.util.Locale;

/** Форматирует длительность восстановления для отображения в интерфейсе. */
final class ElapsedTimeFormatter {
    private ElapsedTimeFormatter() {
    }

    /** Возвращает длительность в неизменном формате часов, минут и секунд. */
    static String format(Duration duration) {
        long totalSeconds = Math.max(0, duration.getSeconds());
        long hours = totalSeconds / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%02d часы %02d мин. %02d сек.", hours, minutes, seconds);
    }
}
