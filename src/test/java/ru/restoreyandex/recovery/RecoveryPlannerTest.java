package ru.restoreyandex.recovery;

import ru.restoreyandex.domain.MediaInspection;
import ru.restoreyandex.domain.MediaType;
import ru.restoreyandex.domain.RecoveryAction;
import ru.restoreyandex.domain.RecoveryPlan;
import ru.restoreyandex.media.MediaInspector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryPlannerTest {
    @TempDir
    Path temporaryDirectory;

    private final RecoveryPlanner planner = new RecoveryPlanner();

    @Test
    void destinationFileAlwaysHasPriority() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destinationRoot = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Path source = Files.writeString(sourceRoot.resolve("photo.jpg"), "source");
        Files.writeString(destinationRoot.resolve("photo.jpg"), "do not replace");

        RecoveryPlan plan = planner.plan(source, sourceRoot, destinationRoot, readableImage());

        assertEquals(RecoveryAction.SKIP_DESTINATION_EXISTS, plan.action());
    }

    @Test
    void readableFileIsCopiedWithItsRelativePath() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destinationRoot = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Path source = Files.createDirectories(sourceRoot.resolve("2011/wedding")).resolve("IMG_1.jpg");
        Files.writeString(source, "contents");

        RecoveryPlan plan = planner.plan(source, sourceRoot, destinationRoot, readableImage());

        assertEquals(RecoveryAction.COPY_ORIGINAL, plan.action());
        assertEquals(destinationRoot.resolve("2011/wedding/IMG_1.jpg"), plan.destination());
    }

    @Test
    void unreadableSupportedFileUsesPngPreviewName() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destinationRoot = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Path source = Files.writeString(sourceRoot.resolve("IMG_1.jpg"), "placeholder");

        RecoveryPlan plan = planner.plan(source, sourceRoot, destinationRoot, unreadableImage());

        assertEquals(RecoveryAction.WRITE_PREVIEW, plan.action());
        assertEquals(destinationRoot.resolve("PREW_IMG_1.png"), plan.destination());
    }

    @Test
    void existingPreviewAlsoPreventsRepeatedRecovery() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destinationRoot = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Path source = Files.writeString(sourceRoot.resolve("IMG_1.jpg"), "placeholder");
        Files.writeString(destinationRoot.resolve("PREW_IMG_1.png"), "first result");

        RecoveryPlan plan = planner.plan(source, sourceRoot, destinationRoot, unreadableImage());

        assertEquals(RecoveryAction.SKIP_DESTINATION_EXISTS, plan.action());
    }

    @Test
    void existingPreviewIsReplacedOnlyWhenTheOptionIsEnabled() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destinationRoot = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Path source = Files.writeString(sourceRoot.resolve("IMG_1.jpg"), "placeholder");
        Files.writeString(destinationRoot.resolve("PREW_IMG_1.png"), "outdated preview");

        RecoveryPlan plan = planner.plan(source, sourceRoot, destinationRoot, unreadableImage(), true);

        assertEquals(RecoveryAction.WRITE_PREVIEW, plan.action());
        assertEquals(destinationRoot.resolve("PREW_IMG_1.png"), plan.destination());
    }

    @Test
    void originalDestinationIsNeverReplacedEvenWhenPreviewReplacementIsEnabled() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destinationRoot = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Path source = Files.writeString(sourceRoot.resolve("IMG_1.jpg"), "placeholder");
        Files.writeString(destinationRoot.resolve("IMG_1.jpg"), "protected original");

        RecoveryPlan plan = planner.plan(source, sourceRoot, destinationRoot, unreadableImage(), true);

        assertEquals(RecoveryAction.SKIP_DESTINATION_EXISTS, plan.action());
    }

    @Test
    void unrelatedFilesAreIgnored() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("source"));
        Path destinationRoot = Files.createDirectories(temporaryDirectory.resolve("destination"));
        Path source = Files.writeString(sourceRoot.resolve("notes.txt"), "not media");

        RecoveryPlan plan = planner.plan(source, sourceRoot, destinationRoot, unreadableImage());

        assertEquals(RecoveryAction.SKIP_UNSUPPORTED, plan.action());
    }

    private MediaInspector readableImage() {
        return ignored -> MediaInspection.readable(MediaType.IMAGE);
    }

    private MediaInspector unreadableImage() {
        return ignored -> MediaInspection.unreadable(MediaType.IMAGE);
    }
}
