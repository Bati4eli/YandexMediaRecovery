package ru.restoreyandex.downloader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Точка входа Gradle-задачи, записывающая готовый BAT в каталог сборки.
 */
public final class DownloadScriptGenerator {

    private DownloadScriptGenerator() {
    }

    /**
     * Создаёт скрипт загрузки зависимостей.
     *
     * @param args путь назначения, имя поставляемого JAR приложения и путь к TSV-манифесту зависимостей
     * @throws IOException если файл назначения нельзя создать или записать
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Ожидаются: путь BAT, имя JAR приложения и путь к TSV-манифесту");
        }
        Path output = Path.of(args[0]);
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<DownloadArtifact> artifacts = readManifest(Path.of(args[2]));
        Files.writeString(output, new DownloadScriptRenderer().render(args[1], artifacts), StandardCharsets.UTF_8);
    }

    /**
     * Читает сгенерированный Gradle список из пар {@code URL<TAB>имя файла}.
     */
    static List<DownloadArtifact> readManifest(Path manifest) throws IOException {
        List<DownloadArtifact> artifacts = Files.readAllLines(manifest, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(DownloadScriptGenerator::parseManifestLine)
                .toList();
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("Gradle не передал зависимости для загрузчика");
        }
        return artifacts;
    }

    private static DownloadArtifact parseManifestLine(String line) {
        String[] parts = line.split("\\t", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Некорректная строка манифеста зависимостей");
        }
        return new DownloadArtifact(parts[0], parts[1]);
    }
}
