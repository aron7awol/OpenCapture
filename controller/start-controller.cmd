@echo off
cd /d "%~dp0"
python server.py --listen 0.0.0.0 --open-browser
pause
