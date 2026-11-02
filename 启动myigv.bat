@echo off
setlocal
set "HERE=%~dp0"
set "DIST=%HERE%build\myigv-dist"
set "JAR=%DIST%\lib\myigv.jar"

if exist "%HERE%jdk-21\bin\javaw.exe" (set "JAVAW=%HERE%jdk-21\bin\javaw.exe") else (set "JAVAW=javaw")

if exist "%JAR%" goto run

echo.
echo [myigv] Build not found, running gradlew createDist first...
echo [myigv] This needs the bundled jdk-21 in the current folder.
echo.
call "%HERE%gradlew.bat" createDist --console=plain

if not exist "%JAR%" (
    echo.
    echo [myigv] Build failed. Make sure the jdk-21 folder exists, then retry.
    echo.
    pause
    exit /b 1
)

:run
if exist "%DIST%\pack\manifest.tsv" (
    echo [myigv] Local resource pack detected - offline annotation mode enabled.
) else (
    echo [myigv] Warning: local resource pack not found - will run in online mode.
)
echo [myigv] Starting myigv, please wait...
"%JAVAW%" -Xmx4g --module-path="%DIST%\lib" -Dproduction=true -Dmyigv.dir="%DIST%\myigv_data" @"%DIST%\igv.args" -Djava.net.preferIPv4Stack=true -Dsun.java2d.noddraw=true -Djava.net.useSystemProxies=true --module=org.igv/org.broad.igv.ui.Main %*
endlocal
