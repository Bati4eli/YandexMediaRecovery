package ru.restoreyandex.downloader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет контракт BAT-файла первоначальной загрузки переносимого приложения.
 */
class DownloadScriptRendererTest {

    @Test
    void createsIdempotentScriptForJavaAndRuntimeLibraries() {
        String script = new DownloadScriptRenderer().render("yandex-media-recovery-1.0.0.jar", List.of(
                new DownloadArtifact(
                        "https://repo.maven.apache.org/maven2/example/playwright/9.9.9/playwright-9.9.9.jar",
                        "playwright-9.9.9.jar")
        ));

        assertTrue(script.contains("if exist \"%RUNTIME_DIR%\\bin\\javaw.exe\""));
        assertTrue(script.contains("https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse"));
        assertTrue(script.contains("playwright-9.9.9.jar"));
        assertTrue(script.contains("if not exist \"%PROFILE_DIR%\" mkdir \"%PROFILE_DIR%\""));
    }

    @Test
    void rejectsUnsafeApplicationJarName() {
        DownloadScriptRenderer renderer = new DownloadScriptRenderer();

        assertThrows(IllegalArgumentException.class,
                () -> renderer.render("..\\application.jar", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render("application.txt", List.of()));
    }

    @Test
    void rejectsEmptyRuntimeDependencyPlan() {
        assertThrows(IllegalArgumentException.class,
                () -> new DownloadScriptRenderer().render("application.jar", List.of()));
    }
}
