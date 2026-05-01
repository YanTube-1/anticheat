@echo off
setlocal

set JAVA_HOME=d:\Git\Anticheat V1\_jdk8\jdk8u432-b06
set PATH=%JAVA_HOME%\bin;%PATH%
set GRADLE=d:\Git\Anticheat V1\_gradle-4.9\bin\gradle.bat

echo.
echo ========================================
echo  AntiCheat -- Alle 3 Versionen bauen
echo ========================================
echo.

echo [1/3] Trainer...
call "%GRADLE%" build -Pvariant=trainer
if %ERRORLEVEL% NEQ 0 ( echo FEHLER beim Trainer-Build! & exit /b 1 )

echo.
echo [2/3] Feedback (mit Chat + Befehle)...
call "%GRADLE%" build -Pvariant=feedback
if %ERRORLEVEL% NEQ 0 ( echo FEHLER beim Feedback-Build! & exit /b 1 )

echo.
echo [3/3] Silent (unsichtbar)...
call "%GRADLE%" build -Pvariant=silent
if %ERRORLEVEL% NEQ 0 ( echo FEHLER beim Silent-Build! & exit /b 1 )

echo.
echo ========================================
echo  Fertig! JARs in build\libs\
echo ========================================
echo.
dir /b "d:\Git\Anticheat V1\build\libs\*.jar" | findstr /v sources
echo.
endlocal
