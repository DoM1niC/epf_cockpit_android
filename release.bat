@echo off
rem Baut eine APK und legt sie in release\ ab.
rem Nutzung:
rem   release.bat            (release-Build)
rem   release.bat debug      (debug-Build)

setlocal enabledelayedexpansion

cd /d "%~dp0"

set "VARIANT=%~1"
if "%VARIANT%"=="" set "VARIANT=release"
if /i not "%VARIANT%"=="debug" if /i not "%VARIANT%"=="release" (
    echo ERROR: unbekannte Variante '%VARIANT%' ^(erlaubt: debug, release^)
    exit /b 2
)

if not exist release mkdir release

if /i "%VARIANT%"=="debug" (
    set "GRADLE_TASK=assembleDebug"
) else (
    set "GRADLE_TASK=assembleRelease"
    if not exist keystore.properties (
        echo WARN: keystore.properties fehlt - Release wird mit Debug-Key signiert.
        echo       Lege keystore.properties an ^(siehe keystore.properties.example^) fuer eine
        echo       ordentliche Release-Signatur.
    )
)

echo ==^> Building: gradlew.bat %GRADLE_TASK%
call .\gradlew.bat %GRADLE_TASK%
if errorlevel 1 (
    echo ERROR: Gradle-Build fehlgeschlagen
    exit /b 1
)

set "APK_DIR=app\build\outputs\apk\%VARIANT%"
set "APK="
for /f "delims=" %%f in ('dir /b /a:-d /o:-d "%APK_DIR%\*.apk" 2^>nul') do (
    if not defined APK set "APK=%APK_DIR%\%%f"
)
if not defined APK (
    echo ERROR: keine APK gefunden in %APK_DIR%
    exit /b 1
)

set "VERSION=unknown"
for /f "tokens=2 delims==" %%a in ('findstr /C:"versionName" app\build.gradle.kts') do set "VRAW=%%a"
if defined VRAW (
    set "VRAW=!VRAW: =!"
    set "VERSION=!VRAW:"=!"
)

set "SIGNED_SUFFIX="
echo %APK% | findstr /c:"unsigned.apk" >nul && set "SIGNED_SUFFIX=-unsigned"

set "DEST=release\epf-cockpit-%VERSION%-%VARIANT%%SIGNED_SUFFIX%.apk"
copy /y "%APK%" "%DEST%" >nul
if errorlevel 1 (
    echo ERROR: Konnte APK nicht kopieren
    exit /b 1
)

echo ==^> APK: %DEST%
dir "%DEST%" | findstr /c:".apk"

endlocal
