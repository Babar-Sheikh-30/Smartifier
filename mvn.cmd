@echo off
setlocal
set "MAVEN_CMD=%~dp0.tools\apache-maven-3.9.11\bin\mvn.cmd"
if not exist "%MAVEN_CMD%" (
  echo Maven is missing. See README.md for installation instructions.
  exit /b 1
)
call "%MAVEN_CMD%" %*
exit /b %errorlevel%
