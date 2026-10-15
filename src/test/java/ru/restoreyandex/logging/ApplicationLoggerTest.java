package ru.restoreyandex.logging;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет кодировку байтов, отправляемых в консоль Windows и IDE. */
class ApplicationLoggerTest {
    @Test
    void writesRussianConsoleTextAsUtf8() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream console = ApplicationLogger.utf8Console(output)) {
            console.print("Вход в Яндекс Диск подтверждён");
        }

        assertEquals("Вход в Яндекс Диск подтверждён", output.toString(StandardCharsets.UTF_8));
    }
}
