package ru.restoreyandex.preview;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Ограничивает частоту последовательных сетевых операций. */
final class RequestRateLimiter {
    private final long minimumIntervalMillis;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private long nextAllowedAtMillis;
    private boolean firstRequest = true;

    /** Создаёт ограничитель, использующий системные часы и ожидание текущего потока. */
    RequestRateLimiter(long minimumIntervalMillis) {
        this(minimumIntervalMillis, System::currentTimeMillis, Thread::sleep);
    }

    RequestRateLimiter(long minimumIntervalMillis, LongSupplier clock, Sleeper sleeper) {
        if (minimumIntervalMillis < 0) {
            throw new IllegalArgumentException("Интервал не может быть отрицательным");
        }
        this.minimumIntervalMillis = minimumIntervalMillis;
        this.clock = Objects.requireNonNull(clock);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    /** Ожидает следующий разрешённый момент и резервирует его для одного запроса. */
    synchronized void awaitSlot() throws InterruptedException {
        long current = clock.getAsLong();
        long delay = firstRequest ? 0 : Math.max(0, nextAllowedAtMillis - current);
        if (delay > 0) {
            sleeper.sleep(delay);
        }
        long startedAt = firstRequest ? clock.getAsLong() : Math.max(clock.getAsLong(), nextAllowedAtMillis);
        nextAllowedAtMillis = Math.addExact(startedAt, minimumIntervalMillis);
        firstRequest = false;
    }

    /** Абстрагирует ожидание, чтобы интервал можно было проверить без реальной паузы. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
