@echo off
setlocal enabledelayedexpansion

rem Launch one P2P Chat peer instance.
rem Usage: run-peer.cmd [peer-name]
rem   e.g. run-peer.cmd A
rem Run it twice (two terminals, or double-click twice) to chat between the two.

set "PEER=%~1"
if "%PEER%"=="" set "PEER=Peer"

set "ROOT=%~dp0"
set "JAR=%ROOT%target\P2PChat.jar"

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
echo  P2P Chat - instance: %PEER%
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
