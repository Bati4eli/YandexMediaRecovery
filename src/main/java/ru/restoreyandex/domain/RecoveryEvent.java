package ru.restoreyandex.domain;

import java.nio.file.Path;

/** Запись о ходе восстановления, пригодная для экрана и CSV-журнала. */
public record RecoveryEvent(int processed, int total, Path source, RecoveryOutcome outcome, String message) {
}
