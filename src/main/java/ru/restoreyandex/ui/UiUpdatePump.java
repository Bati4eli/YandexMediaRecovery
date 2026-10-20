package ru.restoreyandex.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Передаёт частые обновления от рабочих потоков в UI ограниченными пачками.
 * Рабочий поток добавляет значение и сразу продолжает восстановление: он не
 * ожидает выполнения задач EDT.
 */
final class UiUpdatePump<T> {
    private final int maxBatchSize;
    private final Consumer<Runnable> edtScheduler;
    private final Consumer<List<T>> batchConsumer;
    private final Queue<T> pending = new ConcurrentLinkedQueue<>();
    private final Queue<Runnable> afterDrained = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();

    UiUpdatePump(int maxBatchSize, Consumer<Runnable> edtScheduler, Consumer<List<T>> batchConsumer) {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("Размер пакета должен быть положительным");
        }
        this.maxBatchSize = maxBatchSize;
        this.edtScheduler = Objects.requireNonNull(edtScheduler);
        this.batchConsumer = Objects.requireNonNull(batchConsumer);
    }

    /** Добавляет обновление без ожидания EDT. */
    void submit(T update) {
        pending.add(Objects.requireNonNull(update));
        scheduleDrain();
    }

    /** Выполняет действие на EDT после отображения всех уже принятых обновлений. */
    void runWhenDrained(Runnable action) {
        afterDrained.add(Objects.requireNonNull(action));
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) {
            edtScheduler.accept(this::drainOnEdt);
        }
    }

    private void drainOnEdt() {
        try {
            List<T> batch = new ArrayList<>(maxBatchSize);
            for (int index = 0; index < maxBatchSize; index++) {
                T update = pending.poll();
                if (update == null) {
                    break;
                }
                batch.add(update);
            }
            if (!batch.isEmpty()) {
                batchConsumer.accept(List.copyOf(batch));
            }
        } finally {
            drainScheduled.set(false);
            if (pending.isEmpty()) {
                runAfterDrained();
            }
            if (!pending.isEmpty()) {
                scheduleDrain();
            }
        }
    }

    private void runAfterDrained() {
        Runnable action;
        while ((action = afterDrained.poll()) != null) {
            action.run();
        }
    }
}
