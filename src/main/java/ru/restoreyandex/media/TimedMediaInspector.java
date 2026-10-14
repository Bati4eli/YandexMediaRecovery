package ru.restoreyandex.media;

import ru.restoreyandex.domain.MediaInspection;
import ru.restoreyandex.domain.MediaType;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Защищает поток восстановления от зависания при чтении облачной заглушки.
 *
 * <p>Яндекс Диск может задержать чтение файла на уровне Windows до окончания
 * синхронизации. Обычное прерывание Java не гарантированно завершает такое
 * нативное чтение. Поэтому проверка запускается в отдельном daemon-потоке и
 * ожидается ограниченное время. При таймауте файл считается недоступным: сервис
 * сразу переходит к попытке извлечь его превью.</p>
 *
 * <p>Пул намеренно ограничен. Если несколько системных вызовов уже зависли,
 * новые файлы не создают сотни потоков: они немедленно считаются недоступными,
 * но восстановление их кэшированных миниатюр продолжается.</p>
 */
public final class TimedMediaInspector implements MediaInspector {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);
    private static final int DEFAULT_MAXIMUM_WORKERS = 2;

    private final MediaInspector delegate;
    private final Duration timeout;
    private final ThreadPoolExecutor executor;

    /** Создаёт проверку с таймаутом восемь секунд и максимум двумя рабочими потоками. */
    public TimedMediaInspector(MediaInspector delegate) {
        this(delegate, DEFAULT_TIMEOUT, DEFAULT_MAXIMUM_WORKERS);
    }

    /**
     * Создаёт проверку с заданными ограничениями; конструктор доступен для
     * детерминированных тестов и для будущей настройки приложения.
     */
    public TimedMediaInspector(MediaInspector delegate, Duration timeout, int maximumWorkers) {
        this.delegate = Objects.requireNonNull(delegate);
        this.timeout = requirePositive(timeout);
        if (maximumWorkers < 1) {
            throw new IllegalArgumentException("Количество потоков должно быть положительным");
        }
        executor = new ThreadPoolExecutor(0, maximumWorkers, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), daemonThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Выполняет проверку с ограничением времени. При таймауте, отказе пула или
     * ошибке возвращает «недоступен» с типом, определённым по расширению.
     */
    @Override
    public MediaInspection inspect(Path file) {
        MediaType mediaType = MediaExtensions.typeOf(file);
        if (mediaType == MediaType.UNSUPPORTED) {
            return MediaInspection.unsupported();
        }
        Future<MediaInspection> task;
        try {
            task = executor.submit(() -> delegate.inspect(file));
        } catch (RejectedExecutionException exception) {
            return MediaInspection.unreadable(mediaType);
        }
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            return MediaInspection.unreadable(mediaType);
        } catch (InterruptedException exception) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return MediaInspection.unreadable(mediaType);
        } catch (ExecutionException exception) {
            return MediaInspection.unreadable(mediaType);
        }
    }

    private Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Таймаут должен быть положительным");
        }
        return value;
    }

    private ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "media-inspection-timeout-worker");
            thread.setDaemon(true);
            return thread;
        };
    }
}
