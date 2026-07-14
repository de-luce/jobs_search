@echo off
setlocal EnableExtensions

REM Get Jobs 一键启动（Windows 入口）
REM 用法与 scripts\start.sh 相同，例如：
REM   scripts\start.bat
REM   scripts\start.bat --init-db
REM   scripts\start.bat --rebuild-front

set "SCRIPT_DIR=%~dp0"
set "PS1=%SCRIPT_DIR%start.ps1"

where powershell >nul 2>&1
if errorlevel 1 (
  echo [error] 未找到 powershell，请安装 Windows PowerShell 5.1 或 PowerShell 7+
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
exit /b %ERRORLEVEL%
