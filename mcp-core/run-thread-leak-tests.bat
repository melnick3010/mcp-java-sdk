@echo off
REM Script per eseguire i test standalone di thread leak su Windows
REM Uso: run-thread-leak-tests.bat [client|server|both]

setlocal enabledelayedexpansion

echo === MCP Java SDK - Thread Leak Tests ===
echo.

REM Funzione per stampare messaggi colorati
set "INFO=[INFO]"
set "SUCCESS=[SUCCESS]"
set "WARNING=[WARNING]"
set "ERROR=[ERROR]"

REM Verifica prerequisiti
call :check_prerequisites %1
if errorlevel 1 exit /b 1

REM Determina quale test eseguire
set "test_type=%1"
if "%test_type%"=="" set "test_type=both"

if /i "%test_type%"=="client" (
    call :run_client_tests
) else if /i "%test_type%"=="server" (
    call :run_server_tests
) else if /i "%test_type%"=="both" (
    call :run_both_tests
) else if /i "%test_type%"=="help" (
    call :show_help
) else if /i "%test_type%"=="-h" (
    call :show_help
) else if /i "%test_type%"=="--help" (
    call :show_help
) else (
    echo %ERROR% Unknown option: %test_type%
    call :show_help
    exit /b 1
)

exit /b %errorlevel%

REM ===== Funzioni =====

:check_prerequisites
echo %INFO% Checking prerequisites...

REM Verifica Java
where java >nul 2>&1
if errorlevel 1 (
    echo %ERROR% Java not found. Please install Java 17 or later.
    exit /b 1
)

for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION=%%i
    set JAVA_VERSION=!JAVA_VERSION:"=!
    for /f "tokens=1 delims=." %%j in ("!JAVA_VERSION!") do set JAVA_MAJOR=%%j
)

if !JAVA_MAJOR! LSS 17 (
    echo %ERROR% Java 17 or later required. Found: !JAVA_MAJOR!
    exit /b 1
)
echo %SUCCESS% Java !JAVA_MAJOR! found

REM Verifica Maven
where mvn >nul 2>&1
if errorlevel 1 (
    echo %ERROR% Maven not found. Please install Maven 3.6 or later.
    exit /b 1
)
echo %SUCCESS% Maven found

REM Verifica Docker (solo per test client)
if /i "%1"=="client" (
    call :check_docker
    if errorlevel 1 exit /b 1
)
if /i "%1"=="both" (
    call :check_docker
    if errorlevel 1 exit /b 1
)

exit /b 0

:check_docker
where docker >nul 2>&1
if errorlevel 1 (
    echo %ERROR% Docker not found. Please install Docker for client tests.
    exit /b 1
)

docker info >nul 2>&1
if errorlevel 1 (
    echo %ERROR% Docker daemon not running. Please start Docker.
    exit /b 1
)
echo %SUCCESS% Docker found and running
exit /b 0

:run_client_tests
echo %INFO% Running CLIENT thread leak tests...
echo.

echo %INFO% This will:
echo %INFO% - Start a Docker container with MCP SSE server
echo %INFO% - Create and close multiple SSE clients
echo %INFO% - Monitor thread count for leaks
echo.

call mvn test -Dtest=HttpSseClientThreadLeakStandaloneTest -q

if errorlevel 1 (
    echo %ERROR% CLIENT tests FAILED - Thread leak detected or other error
    exit /b 1
) else (
    echo %SUCCESS% CLIENT tests PASSED - No thread leak detected
)
exit /b 0

:run_server_tests
echo %INFO% Running SERVER thread leak tests...
echo.

echo %INFO% This will:
echo %INFO% - Start embedded Tomcat with MCP SSE server
echo %INFO% - Simulate multiple SSE connections
echo %INFO% - Monitor thread count for leaks
echo.

call mvn test -Dtest=HttpSseServerThreadLeakStandaloneTest -q

if errorlevel 1 (
    echo %ERROR% SERVER tests FAILED - Thread leak detected or other error
    exit /b 1
) else (
    echo %SUCCESS% SERVER tests PASSED - No thread leak detected
)
exit /b 0

:run_both_tests
echo %INFO% Running BOTH client and server thread leak tests...
echo.

call mvn test -Dtest="*ThreadLeakStandaloneTest" -q

if errorlevel 1 (
    echo %ERROR% Some tests FAILED - Thread leak detected or other error
    exit /b 1
) else (
    echo %SUCCESS% ALL tests PASSED - No thread leak detected
)
exit /b 0

:show_help
echo Usage: %~nx0 [client^|server^|both]
echo.
echo Options:
echo   client  - Run only client thread leak tests (requires Docker)
echo   server  - Run only server thread leak tests (no Docker required)
echo   both    - Run both client and server tests (default)
echo   help    - Show this help message
echo.
echo Examples:
echo   %~nx0 client    # Test only client component
echo   %~nx0 server    # Test only server component
echo   %~nx0           # Test both components
echo.
exit /b 0

@REM Made with Bob
