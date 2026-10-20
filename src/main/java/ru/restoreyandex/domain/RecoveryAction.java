package ru.restoreyandex.domain;

/** Действие, выбранное для одного исходного файла. */
public enum RecoveryAction {
    COPY_ORIGINAL,
    WRITE_PREVIEW,
    SKIP_DESTINATION_EXISTS,
    NO_ORIGINAL_AVAILABLE
}
