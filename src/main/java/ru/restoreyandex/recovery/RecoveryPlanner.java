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
     * Планирует восстановление с учётом разрешения на обновление превью.
     * Оригинал в папке назначения всегда защищён, а приоритет превью
     * определяется сервисом после проверки доступных источников.
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
        if (Files.exists(originalTarget)) {
            return new RecoveryPlan(source, originalTarget, RecoveryAction.SKIP_DESTINATION_EXISTS, hintedType);
        }

        MediaInspection inspection = inspector.inspect(source);
        // Облачную заглушку нельзя копировать. Для фото сохраняется безопасная
        // цепочка восстановления превью, для остальных типов — финальный NO_FILE.
        if (inspection.unavailableOnDisk() && inspection.type() != MediaType.IMAGE) {
            return new RecoveryPlan(source, originalTarget, RecoveryAction.NO_ORIGINAL_AVAILABLE, inspection.type());
        }
        if (inspection.readable()) {
            return new RecoveryPlan(source, originalTarget, RecoveryAction.COPY_ORIGINAL, inspection.type());
        }
        if (inspection.type() == MediaType.IMAGE) {
            return new RecoveryPlan(source, originalTarget, RecoveryAction.WRITE_PREVIEW, inspection.type());
        }
        return new RecoveryPlan(source, originalTarget, RecoveryAction.NO_ORIGINAL_AVAILABLE, inspection.type());
    }
}
