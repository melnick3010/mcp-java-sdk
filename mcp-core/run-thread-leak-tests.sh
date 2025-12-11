#!/bin/bash

# Script per eseguire i test standalone di thread leak
# Uso: ./run-thread-leak-tests.sh [client|server|both]

set -e

echo "=== MCP Java SDK - Thread Leak Tests ==="
echo

# Colori per output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Funzione per stampare con colori
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Verifica prerequisiti
check_prerequisites() {
    print_info "Checking prerequisites..."
    
    # Verifica Java
    if ! command -v java &> /dev/null; then
        print_error "Java not found. Please install Java 17 or later."
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 17 ]; then
        print_error "Java 17 or later required. Found: $JAVA_VERSION"
        exit 1
    fi
    print_success "Java $JAVA_VERSION found"
    
    # Verifica Maven
    if ! command -v mvn &> /dev/null; then
        print_error "Maven not found. Please install Maven 3.6 or later."
        exit 1
    fi
    print_success "Maven found"
    
    # Verifica Docker (solo per test client)
    if [ "$1" = "client" ] || [ "$1" = "both" ]; then
        if ! command -v docker &> /dev/null; then
            print_error "Docker not found. Please install Docker for client tests."
            exit 1
        fi
        
        if ! docker info &> /dev/null; then
            print_error "Docker daemon not running. Please start Docker."
            exit 1
        fi
        print_success "Docker found and running"
    fi
}

# Esegui test client
run_client_tests() {
    print_info "Running CLIENT thread leak tests..."
    echo
    
    print_info "This will:"
    print_info "- Start a Docker container with MCP SSE server"
    print_info "- Create and close multiple SSE clients"
    print_info "- Monitor thread count for leaks"
    echo
    
    mvn test -Dtest=HttpSseClientThreadLeakStandaloneTest -q
    
    if [ $? -eq 0 ]; then
        print_success "CLIENT tests PASSED - No thread leak detected"
    else
        print_error "CLIENT tests FAILED - Thread leak detected or other error"
        return 1
    fi
}

# Esegui test server
run_server_tests() {
    print_info "Running SERVER thread leak tests..."
    echo
    
    print_info "This will:"
    print_info "- Start embedded Tomcat with MCP SSE server"
    print_info "- Simulate multiple SSE connections"
    print_info "- Monitor thread count for leaks"
    echo
    
    mvn test -Dtest=HttpSseServerThreadLeakStandaloneTest -q
    
    if [ $? -eq 0 ]; then
        print_success "SERVER tests PASSED - No thread leak detected"
    else
        print_error "SERVER tests FAILED - Thread leak detected or other error"
        return 1
    fi
}

# Esegui entrambi i test
run_both_tests() {
    print_info "Running BOTH client and server thread leak tests..."
    echo
    
    mvn test -Dtest="*ThreadLeakStandaloneTest" -q
    
    if [ $? -eq 0 ]; then
        print_success "ALL tests PASSED - No thread leak detected"
    else
        print_error "Some tests FAILED - Thread leak detected or other error"
        return 1
    fi
}

# Mostra help
show_help() {
    echo "Usage: $0 [client|server|both]"
    echo
    echo "Options:"
    echo "  client  - Run only client thread leak tests (requires Docker)"
    echo "  server  - Run only server thread leak tests (no Docker required)"
    echo "  both    - Run both client and server tests (default)"
    echo "  help    - Show this help message"
    echo
    echo "Examples:"
    echo "  $0 client    # Test only client component"
    echo "  $0 server    # Test only server component"
    echo "  $0           # Test both components"
    echo
}

# Main
main() {
    local test_type="${1:-both}"
    
    case "$test_type" in
        "client")
            check_prerequisites "client"
            run_client_tests
            ;;
        "server")
            check_prerequisites "server"
            run_server_tests
            ;;
        "both")
            check_prerequisites "both"
            run_both_tests
            ;;
        "help"|"-h"|"--help")
            show_help
            exit 0
            ;;
        *)
            print_error "Unknown option: $test_type"
            show_help
            exit 1
            ;;
    esac
}

# Verifica che siamo nella directory corretta
if [ ! -f "pom.xml" ]; then
    print_error "Please run this script from the mcp-core directory"
    exit 1
fi

# Esegui main con tutti gli argomenti
main "$@"

# Made with Bob
