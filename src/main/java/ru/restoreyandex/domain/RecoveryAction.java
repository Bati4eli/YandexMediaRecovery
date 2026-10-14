package ru.restoreyandex.domain;

/** Действие, выбранное для одного исходного файла. */
public enum RecoveryAction {
    COPY_ORIGINAL,
    WRITE_PREVIEW,
    SKIP_DESTINATION_EXISTS,
    SKIP_UNSUPPORTED
}
