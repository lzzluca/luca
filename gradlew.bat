@echo off
set DIR=%~dp0
if exist "%DIR%\.gradle-dist\gradle-8.7\bin\gradle.bat" (
  call "%DIR%\.gradle-dist\gradle-8.7\bin\gradle.bat" %*
  exit /b %ERRORLEVEL%
)
echo Please run on Unix/macOS for this workspace setup.
exit /b 1
