@echo off
setlocal
set "HERE=%~dp0"
set "DIST=%HERE%build\myigv-dist"
set "JAR=%DIST%\lib\myigv.jar"

if exist "%HERE%jdk-21\bin\java.exe" (set "JAVA=%HERE%jdk-21\bin\java.exe") else (set "JAVA=java")

if not exist "%JAR%" (
    echo [myigv] Build not found. Run 启动myigv.bat first to build.
    pause
    exit /b 1
)

echo [myigv] Console mode. Close this window to exit.
"%JAVA%" -Xmx4g --module-path="%DIST%\lib" -Dproduction=true -Dmyigv.dir="%DIST%\myigv_data" @"%DIST%\igv.args" -Djava.net.preferIPv4Stack=true -Dsun.java2d.noddraw=true -Djava.net.useSystemProxies=true --module=org.igv/org.broad.igv.ui.Main %*
echo.
echo [myigv] myigv exited with code %ERRORLEVEL%
pause
