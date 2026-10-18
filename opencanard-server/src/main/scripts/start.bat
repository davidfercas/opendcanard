@echo off
setlocal

REM Base directory
set BASE_DIR=%~dp0..

REM Classpath
set CP=%BASE_DIR%\lib\opencanard-server-0.1.jar;%BASE_DIR%\lib\external\*

REM JVM options
set JAVA_OPTS=
set JAVA_OPTS=%JAVA_OPTS% -Dconf.dir=%BASE_DIR%\conf
set JAVA_OPTS=%JAVA_OPTS% -Dmetadata.dir=%BASE_DIR%\metadata
set JAVA_OPTS=%JAVA_OPTS% -Ddata.dir=%BASE_DIR%\data
set JAVA_OPTS=%JAVA_OPTS% -Dlog.dir=%BASE_DIR%\logs

REM Admin user created when the metadata database is created for the first time.
REM Set OPENCANARD_ADMIN_USER and OPENCANARD_ADMIN_PASSWORD before running this script.
set JAVA_OPTS=%JAVA_OPTS% -Dadmin.user=%OPENCANARD_ADMIN_USER%
set JAVA_OPTS=%JAVA_OPTS% -Dadmin.password=%OPENCANARD_ADMIN_PASSWORD%

REM IMPORTANT: Arrow / JDK module opening
set JAVA_OPTS=%JAVA_OPTS% --add-opens=java.base/java.nio=ALL-UNNAMED

REM Optional tuning (recommended for servers)
set JAVA_OPTS=%JAVA_OPTS% -Xms512m
set JAVA_OPTS=%JAVA_OPTS% -Xmx2g
set JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8

REM Start application
java %JAVA_OPTS% -cp "%CP%" io.opencanard.server.OpenCanardServer

endlocal
pause