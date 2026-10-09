@echo off
setlocal
set "APP_HOME=%~dp0.."
if defined JAVA_HOME ( set "JAVA=%JAVA_HOME%\bin\java.exe" ) else ( set "JAVA=java.exe" )
"%JAVA%" %JAVA_OPTS% -jar "%APP_HOME%\lib\regpack-app.jar" %*
exit /b %ERRORLEVEL%
