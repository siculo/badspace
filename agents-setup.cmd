@echo off
rem Sets up what coding agents need in this repository (Windows).
rem You can run it again at any time: it only fixes what is missing or wrong.
rem It uses directory junctions, which need no special permissions.
setlocal
cd /d "%~dp0"

rem Claude Code reads the skills from .claude\skills.
call :link_dir .claude\skills .agents\skills || exit /b 1
exit /b 0

rem Creates the directory junction %1 that points to %2 (relative to the
rem repository root). The junction is always created again, because Windows
rem stores its target as an absolute path that changes if the repository moves.
:link_dir
set "link=%~1"
set "target=%~2"
set "attr="
for %%F in ("%link%") do set "attr=%%~aF"
if not defined attr goto create
if /i not "%attr:~0,1%"=="d" goto remove_file
rem The last attribute is "l" for junctions and links.
if not "%attr:~8,1%"=="l" (
    echo error: %link% is a directory, not a link 1>&2
    exit /b 1
)
rmdir "%link%" || exit /b 1
goto create
:remove_file
rem A plain file is left by a checkout without symbolic links.
del "%link%" || exit /b 1
:create
for %%F in ("%link%") do if not exist "%%~dpF" mkdir "%%~dpF"
mklink /J "%link%" "%target%" >nul || exit /b 1
echo %link% now points to %target%
exit /b 0
