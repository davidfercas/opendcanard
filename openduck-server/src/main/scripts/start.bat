@echo off
setlocal

REM Base directory
set BASE_DIR=%~dp0..

REM Classpath
set CP=%BASE_DIR%\lib\openduck-0.1.jar;%BASE_DIR%\lib\external\*

REM JVM options
set JAVA_OPTS=
set JAVA_OPTS=%JAVA_OPTS% -Dconf.dir=%BASE_DIR%\conf
set JAVA_OPTS=%JAVA_OPTS% -Dmetadata.dir=%BASE_DIR%\metadata
set JAVA_OPTS=%JAVA_OPTS% -Ddata.dir=%BASE_DIR%\data
set JAVA_OPTS=%JAVA_OPTS% -Dlog.dir=%BASE_DIR%\logs

REM IMPORTANT: Arrow / JDK module opening
set JAVA_OPTS=%JAVA_OPTS% --add-opens=java.base/java.nio=ALL-UNNAMED

REM Optional tuning (recommended for servers)
set JAVA_OPTS=%JAVA_OPTS% -Xms512m
set JAVA_OPTS=%JAVA_OPTS% -Xmx2g
set JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8

REM Start application
java %JAVA_OPTS% -cp "%CP%" io.openduck.server.OpenDuckServer

endlocal
pause