@echo off
setlocal

set GRADLE_VERSION=8.10.2
set DIST_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip
set DIST_ROOT=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin\zonewars
set GRADLE_HOME=%DIST_ROOT%\gradle-%GRADLE_VERSION%
set ZIP_FILE=%DIST_ROOT%\gradle-%GRADLE_VERSION%-bin.zip

if exist "%GRADLE_HOME%\bin\gradle.bat" goto runGradle

echo Downloading Gradle %GRADLE_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "New-Item -ItemType Directory -Force -Path '%DIST_ROOT%' | Out-Null;" ^
  "if (Test-Path '%ZIP_FILE%') { Remove-Item -LiteralPath '%ZIP_FILE%' -Force };" ^
  "try { & curl.exe -L --fail --retry 3 --output '%ZIP_FILE%' '%DIST_URL%' } catch { Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP_FILE%' };" ^
  "if (Test-Path '%GRADLE_HOME%') { Remove-Item -LiteralPath '%GRADLE_HOME%' -Recurse -Force };" ^
  "Expand-Archive -LiteralPath '%ZIP_FILE%' -DestinationPath '%DIST_ROOT%' -Force"

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo Failed to install Gradle %GRADLE_VERSION%.
  exit /b 1
)

:runGradle
call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
