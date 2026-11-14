@echo off
setlocal
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo Python belum terpasang. Unduh dari https://www.python.org/downloads/ dan centang "Add python.exe to PATH".
  pause
  exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
  echo Menyiapkan lingkungan Python, hanya sekali...
  python -m venv .venv || goto :error
  ".venv\Scripts\python.exe" -m pip install --quiet -r requirements.txt || goto :error
)

".venv\Scripts\python.exe" app.py
exit /b 0

:error
echo Gagal menyiapkan aplikasi. Pastikan terhubung ke internet lalu coba lagi.
pause
exit /b 1
