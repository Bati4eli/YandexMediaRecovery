package ru.restoreyandex.preview;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет ограничение частоты последовательных обращений к веб-клиенту. */
class RequestRateLimiterTest {
    @Test
    void waitsUntilTheNextAllowedRequestSlot() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        AtomicLong sleptMillis = new AtomicLong();
        RequestRateLimiter limiter = new RequestRateLimiter(2_000, clock::get,
                delay -> {
                    sleptMillis.addAndGet(delay);
                    clock.addAndGet(delay);
                });

        limiter.awaitSlot();
        clock.set(500);
        limiter.awaitSlot();

        assertEquals(1_600, sleptMillis.get());
    }
}
