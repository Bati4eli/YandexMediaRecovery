package ru.restoreyandex.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет форматирование длительности восстановления для окна и итогового сообщения. */
class ElapsedTimeFormatterTest {
    @Test
    void formatsHoursMinutesAndSeconds() {
        assertEquals("01 часы 02 мин. 03 сек.", ElapsedTimeFormatter.format(Duration.ofSeconds(3723)));
    }

    @Test
    void retainsHoursBeyondOneDay() {
        assertEquals("27 часы 05 мин. 09 сек.", ElapsedTimeFormatter.format(Duration.ofHours(27).plusMinutes(5).plusSeconds(9)));
    }
}
