package ru.restoreyandex.recovery;

import ru.restoreyandex.domain.MediaInspection;
import ru.restoreyandex.domain.MediaType;
import ru.restoreyandex.domain.RecoveryAction;
import ru.restoreyandex.domain.RecoveryPlan;
import ru.restoreyandex.media.MediaExtensions;
import ru.restoreyandex.media.MediaInspector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Применяет правило приоритета папки назначения до любой записи на диск. */
public final class RecoveryPlanner {
    /**
     * Планирует восстановление, ничего не записывая на диск.
     *
     * <p>Целевой путь всегда находится внутри {@code destinationRoot}; исходный
     * файл должен быть потомком {@code sourceRoot}.</p>
     */
    public RecoveryPlan plan(Path source, Path sourceRoot, Path destinationRoot, MediaInspector inspector) {
        return plan(source, sourceRoot, destinationRoot, inspector, false);
    }

    /**
     * Планирует восстановление с учётом разрешения на обновление только
     * существующего PREW_-превью; оригинал в папке назначения всегда защищён.
     */
    public RecoveryPlan plan(Path source, Path sourceRoot, Path destinationRoot, MediaInspector inspector,
                             boolean overwritePreviews) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(sourceRoot);
        Objects.requireNonNull(destinationRoot);
        Objects.requireNonNull(inspector);

        Path relativePath = sourceRoot.relativize(source);
        if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
            throw new IllegalArgumentException("Source file must be inside source root: " + source);
        }
        Path originalTarget = destinationRoot.resolve(relativePath);
        MediaType hintedType = MediaExtensions.typeOf(source);
        if (hintedType == MediaType.UNSUPPORTED) {
            return new RecoveryPlan(source, originalTarget, RecoveryAction.SKIP_UNSUPPORTED, hintedType);
        }

        Path previewTarget = originalTarget.resolveSibling(MediaExtensions.previewName(source));
        if (Files.exists(originalTarget) || (Files.exists(previewTarget) && !overwritePreviews)) {
            return new RecoveryPlan(source, originalTarget, RecoveryAction.SKIP_DESTINATION_EXISTS, hintedType);
        }

        MediaInspection inspection = inspector.inspect(source);
        if (inspection.readable()) {
            return new RecoveryPlan(source, originalTarget, RecoveryAction.COPY_ORIGINAL, inspection.type());
        }
        return new RecoveryPlan(source, previewTarget, RecoveryAction.WRITE_PREVIEW, inspection.type());
    }
}
