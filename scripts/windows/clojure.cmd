@echo off
node.exe "%~dp0clojure.mjs" %*
exit /b %errorlevel%
