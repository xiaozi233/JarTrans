@echo off
rem JarTrans 启动脚本：优先用 JAVA_HOME，其次 PATH 中的 java
rem 需先执行过: gradlew shadowJar
setlocal
cd /d "%~dp0"

set "JAVA_EXE=java"
if not "%JAVA_HOME%"=="" (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

rem 定位最新构建的可执行 jar（jartrans-<version>.jar），避免脚本与版本号双重维护
set "APP_JAR="
for /f "delims=" %%f in ('dir /b /o-d "build\libs\jartrans-*.jar" 2^>nul') do (
    if not defined APP_JAR set "APP_JAR=build\libs\%%f"
)

if not defined APP_JAR (
    echo [JarTrans] 未找到 build\libs\jartrans-*.jar，请先执行:
    echo   gradlew shadowJar
    pause
    exit /b 1
)

echo [JarTrans] 启动中...
"%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 ^
    -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 ^
    -jar "%APP_JAR%"
endlocal
