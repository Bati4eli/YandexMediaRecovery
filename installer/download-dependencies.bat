@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
title Yandex Media Recovery - downloader

echo.
echo ============================================================
echo   🚀 Yandex Media Recovery — подготовка приложения
echo ============================================================

set "ROOT_DIR=%~dp0"
set "APP_DIR=%ROOT_DIR%app"
set "RUNTIME_DIR=%ROOT_DIR%runtime"
set "PROFILE_DIR=%ROOT_DIR%yandex-media-recovery-browser-profile"
set "JAVA_ARCHIVE=%TEMP%\YandexMediaRecovery-jre21.zip"
set "JAVA_STAGE=%TEMP%\YandexMediaRecovery-jre21"

echo.
echo ── [1/3] 🔎 Проверка компонентов Windows ───────────────────
where powershell.exe >nul 2>nul || goto :failure
where curl.exe >nul 2>nul || goto :failure

if not exist "%APP_DIR%" mkdir "%APP_DIR%"
if not exist "%PROFILE_DIR%" mkdir "%PROFILE_DIR%"
if not exist "%APP_DIR%\yandex-media-recovery-1.0.0.jar" (
  echo [ERROR] The application JAR is missing.
  goto :failure
)

echo ✅ PowerShell и curl доступны.
call :prepareRuntime || goto :failure

echo.
echo ── [2/3] 📦 Загрузка библиотек приложения ──────────────────
call :download "https://repo.maven.apache.org/maven2/com/microsoft/playwright/driver/1.58.0/driver-1.58.0.jar" "%APP_DIR%\driver-1.58.0.jar" || goto :failure
call :download "https://repo.maven.apache.org/maven2/com/microsoft/playwright/driver-bundle/1.58.0/driver-bundle-1.58.0.jar" "%APP_DIR%\driver-bundle-1.58.0.jar" || goto :failure
call :download "https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.41.0/error_prone_annotations-2.41.0.jar" "%APP_DIR%\error_prone_annotations-2.41.0.jar" || goto :failure
call :download "https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar" "%APP_DIR%\gson-2.13.2.jar" || goto :failure
call :download "https://repo.maven.apache.org/maven2/org/jcodec/jcodec/0.2.5/jcodec-0.2.5.jar" "%APP_DIR%\jcodec-0.2.5.jar" || goto :failure
call :download "https://repo.maven.apache.org/maven2/org/jcodec/jcodec-javase/0.2.5/jcodec-javase-0.2.5.jar" "%APP_DIR%\jcodec-javase-0.2.5.jar" || goto :failure
call :download "https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.16.0/jna-5.16.0.jar" "%APP_DIR%\jna-5.16.0.jar" || goto :failure
call :download "https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/5.16.0/jna-platform-5.16.0.jar" "%APP_DIR%\jna-platform-5.16.0.jar" || goto :failure
call :download "https://repo.maven.apache.org/maven2/org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar" "%APP_DIR%\opentest4j-1.3.0.jar" || goto :failure
call :download "https://repo.maven.apache.org/maven2/com/microsoft/playwright/playwright/1.58.0/playwright-1.58.0.jar" "%APP_DIR%\playwright-1.58.0.jar" || goto :failure
call :download "https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.3/snakeyaml-2.3.jar" "%APP_DIR%\snakeyaml-2.3.jar" || goto :failure

echo.
echo ── [3/3] ✅ Подготовка завершена ───────────────────────────
echo Готово. Теперь запустите YandexMediaRecovery.exe
echo ============================================================
exit /b 0

:prepareRuntime
if exist "%RUNTIME_DIR%\bin\javaw.exe" if exist "%RUNTIME_DIR%\bin\server\jvm.dll" (
  echo ☕ Java Runtime уже установлена.
  exit /b 0
)

echo ☕ Скачивание Eclipse Temurin Java 21 Runtime...
if exist "%JAVA_STAGE%" rmdir /s /q "%JAVA_STAGE%"
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%"
if exist "%JAVA_ARCHIVE%" del /f /q "%JAVA_ARCHIVE%"
call :download "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse" "%JAVA_ARCHIVE%" || exit /b 1

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Expand-Archive -LiteralPath $env:JAVA_ARCHIVE -DestinationPath $env:JAVA_STAGE -Force; $javaHome = $null; foreach ($directory in Get-ChildItem -LiteralPath $env:JAVA_STAGE -Directory) { if (Test-Path -LiteralPath (Join-Path $directory.FullName 'bin\javaw.exe')) { $javaHome = $directory; break } }; if ($null -eq $javaHome -and (Test-Path -LiteralPath (Join-Path $env:JAVA_STAGE 'bin\javaw.exe'))) { $javaHome = Get-Item -LiteralPath $env:JAVA_STAGE }; if ($null -eq $javaHome) { throw 'Java Runtime archive has unexpected layout.' }; Copy-Item -Path (Join-Path $javaHome.FullName '*') -Destination $env:RUNTIME_DIR -Recurse -Force; Remove-Item -LiteralPath $env:JAVA_ARCHIVE -Force; Remove-Item -LiteralPath $env:JAVA_STAGE -Recurse -Force"
if errorlevel 1 exit /b 1
if not exist "%RUNTIME_DIR%\bin\javaw.exe" exit /b 1
if not exist "%RUNTIME_DIR%\bin\server\jvm.dll" exit /b 1
exit /b 0

:download
set "DOWNLOAD_URL=%~1"
set "DOWNLOAD_TARGET=%~2"
if exist "%DOWNLOAD_TARGET%" (
  echo ⏭️ Уже есть: %~nx2
  exit /b 0
)

echo ⬇️ Скачивание: %~nx2
set "DOWNLOAD_TEMP=%DOWNLOAD_TARGET%.part"
if exist "%DOWNLOAD_TEMP%" del /f /q "%DOWNLOAD_TEMP%"
curl.exe --fail --location --retry 3 --retry-delay 2 --output "%DOWNLOAD_TEMP%" "%DOWNLOAD_URL%"
if errorlevel 1 (
  if exist "%DOWNLOAD_TEMP%" del /f /q "%DOWNLOAD_TEMP%"
  exit /b 1
)
move /y "%DOWNLOAD_TEMP%" "%DOWNLOAD_TARGET%" >nul
exit /b %errorlevel%

:failure
echo.
echo ============================================================
echo ❌ Подготовка не завершена.
echo Проверьте интернет и запустите download-dependencies.bat снова.
echo ============================================================
pause
exit /b 1
