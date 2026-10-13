@echo off
rem Makes the World of Warcraft app's server data from a 3.3.5a (12340) client on a Windows PC:
rem dbc, Cameras, maps, vmaps and mmaps, packed into server-data.zip next to this file. Copy that
rem file to the phone and import it in the app (Game files, Import server data).
rem
rem Run it with the client's folder, or drag the client's folder onto it:
rem     extract-server-data.bat "C:\Games\World of Warcraft 3.3.5a"
setlocal
set "TOOLS=%~dp0"
set "CLIENT=%~1"
if "%CLIENT%"=="" set "CLIENT=%CD%"
if not exist "%CLIENT%\Data\common.MPQ" (
    echo "%CLIENT%" isn't a 3.3.5a client folder: it has no Data\common.MPQ.
    echo Run this with the client's folder, for example:
    echo     extract-server-data.bat "C:\Games\World of Warcraft 3.3.5a"
    pause
    exit /b 1
)
set "OUT=%TOOLS%server-data"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
cd /d "%OUT%"

echo [1/4] dbc, Cameras and maps
"%TOOLS%map_extractor.exe" -i "%CLIENT%" -o "%OUT%" || goto failed
echo [2/4] vmaps (buildings and line of sight)
"%TOOLS%vmap4_extractor.exe" -d "%CLIENT%\Data" || goto failed
mkdir vmaps
"%TOOLS%vmap4_assembler.exe" Buildings vmaps || goto failed
rmdir /s /q Buildings
echo [3/4] mmaps (pathfinding; this takes a while)
"%TOOLS%mmaps_generator.exe" --config "%TOOLS%mmaps-config.yaml" || goto failed
echo [4/4] server-data.zip
if exist "%TOOLS%server-data.zip" del "%TOOLS%server-data.zip"
tar -a -c -f "%TOOLS%server-data.zip" dbc Cameras maps vmaps mmaps || goto failed
cd /d "%TOOLS%"
rmdir /s /q "%OUT%"
echo.
echo Done: %TOOLS%server-data.zip
echo Copy it to the phone and import it in the app: Game files, Import server data.
pause
exit /b 0

:failed
echo.
echo The extraction failed (see above).
pause
exit /b 1
