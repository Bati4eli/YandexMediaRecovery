package ru.restoreyandex.logging;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Единая диагностика приложения для консоли и технических ошибок. */
public final class ApplicationLogger {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final PrintStream UTF8_OUT = utf8Console(System.out);
    private static final PrintStream UTF8_ERR = utf8Console(System.err);

    private ApplicationLogger() {
    }

    /** Пишет пользовательское сообщение одновременно с экранным журналом в консоль. */
    public static void info(String message) {
        UTF8_OUT.println(prefix() + Objects.requireNonNull(message));
    }

    /** Пишет контекст ошибки и полный stack trace в поток ошибок консоли. */
    public static void error(String message, Throwable exception) {
        UTF8_ERR.println(prefix() + Objects.requireNonNull(message));
        if (exception != null) {
            exception.printStackTrace(UTF8_ERR);
        }
    }

    /**
     * Создаёт поток с фиксированной UTF-8-кодировкой поверх стандартного вывода.
     * Это устраняет зависимость от OEM-кодировки Windows при запуске из IDE.
     */
    static PrintStream utf8Console(OutputStream output) {
        return new PrintStream(Objects.requireNonNull(output), true, StandardCharsets.UTF_8);
    }

    private static String prefix() {
        return "[" + LocalDateTime.now().format(TIME) + "] ";
    }
}
