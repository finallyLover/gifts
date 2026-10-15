@echo off
REM ============================================================
REM  Gift Ledger - One-click Build & Pack (NSIS installer)
REM  Double-click to run. Do NOT run as administrator.
REM ============================================================
cd /d %~dp0

echo ==========================================
echo   Gift Ledger - Build Installer
echo ==========================================
echo.

REM ---------- 1. Check Rust toolchain ----------
where cargo >nul 2>nul
if errorlevel 1 (
    echo [ERROR] cargo not found.
    echo         Please install Rust first: https://rustup.rs
    echo         Then run this script again.
    pause
    exit /b 1
)

REM ---------- 2. Install frontend deps (skip if exists) ----------
if not exist node_modules (
    echo [1/3] Installing npm dependencies...
    call npm install
    if errorlevel 1 goto fail
) else (
    echo [1/3] node_modules exists, skip npm install
)

REM ---------- 3. Build and bundle ----------
echo [2/3] Building... First build takes 10-30 min, please wait.
call npm run tauri build
if errorlevel 1 goto fail

REM ---------- 4. Done, open output folder ----------
echo.
echo [3/3] Build SUCCESS!
echo Installer location: src-tauri\target\release\bundle\nsis\
echo Run the .exe inside to install. You can choose install dir.
echo.
explorer src-tauri\target\release\bundle\nsis
pause
exit /b 0

:fail
echo.
echo [ERROR] Build failed. Check the log above.
pause
exit /b 1
