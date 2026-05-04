@echo off
REM MochaAgent startup script (Windows)
setlocal

set SCRIPT_DIR=%~dp0
cd /d %SCRIPT_DIR%

if not exist "target\mochaagents-1.0.0.jar" (
    echo Building MochaAgent...
    call mvnw.cmd clean package -DskipTests -q
)

set JAVA_OPTS=-Xmx512m -Xms128m
java %JAVA_OPTS% -cp "target\mochaagents-1.0.0.jar;target\dependency\*" io.sketch.mochaagents.cli.Main %*
