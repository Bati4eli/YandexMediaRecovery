package ru.restoreyandex.domain;

import java.nio.file.Path;

/** Неизменяемое решение о восстановлении для одного исходного пути. */
public record RecoveryPlan(Path source, Path destination, RecoveryAction action, MediaType mediaType) {
}
