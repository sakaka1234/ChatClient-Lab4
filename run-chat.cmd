@echo off
setlocal enabledelayedexpansion

rem Launch one chat instance.
rem Usage: run-chat.cmd [name]
rem   e.g. run-chat.cmd Server
rem        run-chat.cmd An
rem Start one instance, choose SERVER, press Start Server. Then start more instances,
rem choose CLIENT, and point them at the server's address.

set "NAME=%~1"
if "%NAME%"=="" set "NAME=Client"

set "ROOT=%~dp0"
set "JAR=%ROOT%target\Chat.jar"

if not exist "%JAR%" (
    echo [ERROR] %JAR% not found.
    echo         Build it first:  mvnw.cmd clean package
    pause
    exit /b 1
)

rem --- find a JDK 21+ (default java may be 17 and cannot run this jar) ---
set "JAVA_EXE="
for %%J in (
    "%ROOT%..\..\.jdks\ms-21.0.12.1\bin\java.exe"
    "%ROOT%..\..\.jdks\corretto-22.0.2\bin\java.exe"
    "%USERPROFILE%\.jdks\ms-21.0.12.1\bin\java.exe"
    "%USERPROFILE%\.jdks\corretto-22.0.2\bin\java.exe"
    "%JAVA_HOME%\bin\java.exe"
) do (
    if not defined JAVA_EXE if exist %%J set "JAVA_EXE=%%~J"
)
if not defined JAVA_EXE (
    for /f "delims=" %%J in ('where java 2^>nul') do (
        if not defined JAVA_EXE set "JAVA_EXE=%%J"
    )
)
if not defined JAVA_EXE (
    echo [ERROR] No java.exe found. Install JDK 21 or set JAVA_HOME.
    pause
    exit /b 1
)

echo ================================================
echo  Chat - instance: %NAME%
echo  Java: %JAVA_EXE%
echo ================================================
echo.

"%JAVA_EXE%" -jar "%JAR%" %2 %3 %4 %5

if errorlevel 1 (
    echo.
    echo [ERROR] The application exited with an error.
    pause
)

endlocal
