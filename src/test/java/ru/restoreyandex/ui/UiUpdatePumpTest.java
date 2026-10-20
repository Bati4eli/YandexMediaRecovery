package ru.restoreyandex.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Проверяет пакетную доставку обновлений на EDT без блокировки рабочих потоков. */
class UiUpdatePumpTest {
    @Test
    void coalescesWorkerUpdatesAndYieldsAfterConfiguredBatchSize() {
        Queue<Runnable> edtTasks = new ArrayDeque<>();
        List<List<Integer>> appliedBatches = new ArrayList<>();
        UiUpdatePump<Integer> pump = new UiUpdatePump<>(2, edtTasks::add, appliedBatches::add);

        pump.submit(1);
        pump.submit(2);
        pump.submit(3);

        assertEquals(1, edtTasks.size());
        edtTasks.remove().run();
        assertEquals(List.of(List.of(1, 2)), appliedBatches);
        assertEquals(1, edtTasks.size());

        edtTasks.remove().run();
        assertEquals(List.of(List.of(1, 2), List.of(3)), appliedBatches);
        assertEquals(0, edtTasks.size());
    }

    @Test
    void runsCompletionOnlyAfterEveryQueuedUpdateWasApplied() {
        Queue<Runnable> edtTasks = new ArrayDeque<>();
        List<List<Integer>> appliedBatches = new ArrayList<>();
        List<String> completions = new ArrayList<>();
        UiUpdatePump<Integer> pump = new UiUpdatePump<>(1, edtTasks::add, appliedBatches::add);

        pump.submit(1);
        pump.submit(2);
        pump.runWhenDrained(() -> completions.add("done"));

        edtTasks.remove().run();
        assertEquals(List.of(List.of(1)), appliedBatches);
        assertEquals(List.of(), completions);

        edtTasks.remove().run();
        assertEquals(List.of(List.of(1), List.of(2)), appliedBatches);
        assertEquals(List.of("done"), completions);
    }
}
