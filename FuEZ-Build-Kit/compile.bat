@echo off
chcp 65001 >nul
echo [FuEZ] Dang bien dich FuEZ.java thanh FuEZ.class...
if exist "Wonhung-Client-3.0.0.jar" (
    javac -encoding UTF-8 -cp "Wonhung-Client-3.0.0.jar;mc-stubs.jar" -d . FuEZ.java
) else (
    javac -encoding UTF-8 -cp "mc-stubs.jar" -d . FuEZ.java
)
if %errorlevel% equ 0 (
    echo [FuEZ] Bien dich THANH CONG!
    echo File class da duoc tao tai: dev\wonhung\features\modules\funny\FuEZ.class
) else (
    echo [FuEZ] Co loi khi bien dich. Hay kiem tra lai JDK (Java 17 hoac 21).
)
pause
