package ru.restoreyandex.logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Единая диагностика приложения для консоли и технических ошибок. */
public final class ApplicationLogger {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ApplicationLogger() {
    }

    /** Пишет пользовательское сообщение одновременно с экранным журналом в консоль. */
    public static void info(String message) {
        System.out.println(prefix() + Objects.requireNonNull(message));
    }

    /** Пишет контекст ошибки и полный stack trace в поток ошибок консоли. */
    public static void error(String message, Throwable exception) {
        System.err.println(prefix() + Objects.requireNonNull(message));
        if (exception != null) {
            exception.printStackTrace(System.err);
        }
    }

    private static String prefix() {
        return "[" + LocalDateTime.now().format(TIME) + "] ";
    }
}
