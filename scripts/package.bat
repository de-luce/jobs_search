@echo off
REM 转调 package.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package.ps1" %*
