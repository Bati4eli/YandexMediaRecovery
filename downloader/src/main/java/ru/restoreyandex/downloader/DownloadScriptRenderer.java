package ru.restoreyandex.downloader;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Формирует BAT-файл, который дополняет компактную поставку приложения
 * Java Runtime и библиотеками, получаемыми из проверенных публичных источников.
 *
 * <p>Скрипт намеренно не скачивает Chrome и не переносит пользовательский профиль:
 * Chrome устанавливает пользователь, а каталог профиля создаётся пустым для
 * последующего ручного входа в Яндекс Диск.</p>
 */
public final class DownloadScriptRenderer {

    private static final Pattern SAFE_JAR_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar");
    private static final String JAVA_RUNTIME_URL =
            "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse";

    /**
     * Создаёт идемпотентный сценарий подготовки компактной папки приложения.
     *
     * @param applicationJarName имя уже поставляемого JAR приложения без пути
     * @param runtimeArtifacts зависимости, разрешённые корневой Gradle-сборкой
     * @return текст BAT в кодировке UTF-8
     * @throws IllegalArgumentException если имя JAR небезопасно для записи в BAT
     */
    public String render(String applicationJarName, List<DownloadArtifact> runtimeArtifacts) {
        validateApplicationJarName(applicationJarName);
        List<DownloadArtifact> artifacts = List.copyOf(Objects.requireNonNull(runtimeArtifacts, "runtimeArtifacts"));
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("План загрузки зависимостей не должен быть пустым");
        }
        StringBuilder script = new StringBuilder(8_192);
        appendHeader(script, applicationJarName);
        appendRuntimePreparation(script);
        for (DownloadArtifact artifact : artifacts) {
            script.append("call :download \"")
                    .append(artifact.url())
                    .append("\" \"%APP_DIR%\\")
                    .append(artifact.fileName())
                    .append("\" || goto :failure\r\n");
        }
        appendDownloadFunction(script);
        return script.toString();
    }

    private static void validateApplicationJarName(String applicationJarName) {
        if (applicationJarName == null || !SAFE_JAR_NAME.matcher(applicationJarName).matches()) {
            throw new IllegalArgumentException("Имя JAR приложения должно быть простым именем файла .jar");
        }
    }

    private static void appendHeader(StringBuilder script, String applicationJarName) {
        script.append("@echo off\r\n")
                .append("setlocal EnableExtensions DisableDelayedExpansion\r\n")
                .append("title Yandex Media Recovery - downloader\r\n")
                .append("set \"ROOT_DIR=%~dp0\"\r\n")
                .append("set \"APP_DIR=%ROOT_DIR%app\"\r\n")
                .append("set \"RUNTIME_DIR=%ROOT_DIR%runtime\"\r\n")
                .append("set \"PROFILE_DIR=%ROOT_DIR%yandex-media-recovery-browser-profile\"\r\n")
                .append("set \"JAVA_ARCHIVE=%TEMP%\\YandexMediaRecovery-jre21.zip\"\r\n")
                .append("set \"JAVA_STAGE=%TEMP%\\YandexMediaRecovery-jre21\"\r\n\r\n")
                .append("where powershell.exe >nul 2>nul || (echo [ERROR] PowerShell is required.& exit /b 1)\r\n")
                .append("if not exist \"%APP_DIR%\" mkdir \"%APP_DIR%\"\r\n")
                .append("if not exist \"%PROFILE_DIR%\" mkdir \"%PROFILE_DIR%\"\r\n")
                .append("if not exist \"%APP_DIR%\\")
                .append(applicationJarName)
                .append("\" (\r\n")
                .append("  echo [ERROR] The application JAR is missing: ")
                .append(applicationJarName)
                .append("\r\n  exit /b 1\r\n)\r\n\r\n")
                .append("call :prepareRuntime || goto :failure\r\n");
    }

    private static void appendRuntimePreparation(StringBuilder script) {
        script.append("echo.\r\n")
                .append("echo Preparing Java Runtime and application libraries...\r\n")
                .append("goto :downloadLibraries\r\n\r\n")
                .append(":prepareRuntime\r\n")
                .append("if exist \"%RUNTIME_DIR%\\bin\\javaw.exe\" (\r\n")
                .append("  echo Java Runtime is already available.\r\n")
                .append("  exit /b 0\r\n)\r\n")
                .append("echo Downloading Eclipse Temurin Java 21 Runtime...\r\n")
                .append("if exist \"%JAVA_STAGE%\" rmdir /s /q \"%JAVA_STAGE%\"\r\n")
                .append("if not exist \"%RUNTIME_DIR%\" mkdir \"%RUNTIME_DIR%\"\r\n")
                .append("powershell.exe -NoProfile -ExecutionPolicy Bypass -Command \"$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri '")
                .append(JAVA_RUNTIME_URL)
                .append("' -OutFile $env:JAVA_ARCHIVE; Expand-Archive -LiteralPath $env:JAVA_ARCHIVE -DestinationPath $env:JAVA_STAGE -Force; $javaHome = Get-ChildItem -LiteralPath $env:JAVA_STAGE -Directory ^| Select-Object -First 1; if ($null -eq $javaHome) { throw 'Java Runtime archive has unexpected layout.' }; Copy-Item -Path (Join-Path $javaHome.FullName '*') -Destination $env:RUNTIME_DIR -Recurse -Force; Remove-Item -LiteralPath $env:JAVA_ARCHIVE -Force; Remove-Item -LiteralPath $env:JAVA_STAGE -Recurse -Force\"\r\n")
                .append("if errorlevel 1 exit /b 1\r\n")
                .append("if not exist \"%RUNTIME_DIR%\\bin\\javaw.exe\" exit /b 1\r\n")
                .append("exit /b 0\r\n\r\n")
                .append(":downloadLibraries\r\n");
    }

    private static void appendDownloadFunction(StringBuilder script) {
        script.append("echo.\r\n")
                .append("echo Preparation completed. Run YandexMediaRecovery.exe.\r\n")
                .append("exit /b 0\r\n\r\n")
                .append(":failure\r\n")
                .append("echo.\r\n")
                .append("echo [ERROR] Preparation did not finish. Check the Internet connection and run this file again.\r\n")
                .append("exit /b 1\r\n\r\n")
                .append(":download\r\n")
                .append("set \"DOWNLOAD_URL=%~1\"\r\n")
                .append("set \"DOWNLOAD_TARGET=%~2\"\r\n")
                .append("if exist \"%DOWNLOAD_TARGET%\" (\r\n")
                .append("  echo Already present: %~nx2\r\n")
                .append("  exit /b 0\r\n)\r\n")
                .append("echo Downloading: %~nx2\r\n")
                .append("powershell.exe -NoProfile -ExecutionPolicy Bypass -Command \"$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri $env:DOWNLOAD_URL -OutFile $env:DOWNLOAD_TARGET\"\r\n")
                .append("exit /b %errorlevel%\r\n");
    }
}
