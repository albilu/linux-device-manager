#!/bin/bash
# Docker command dispatcher for Linux Device Manager.
# The Makefile delegates EVERYTHING here; never inline docker commands in the Makefile.
set -e

PROJECT_NAME="linux-device-manager"
IMAGE_NAME="${LDM_IMAGE_NAME:-ldm-dev}"

# Colors
GREEN='\033[0;32m'
NC='\033[0m'

log() {
    echo -e "${GREEN}[LDM]${NC} $1"
}

# The image uses the caller's UID/GID, so ordinary private cache permissions work.
prepare_m2() {
    mkdir -p "$HOME/.m2"
}

# X11 authentication forwarder. Wayland/Xwayland sessions gate the display
# behind an Xauthority token. Containers must receive that token or the X
# server rejects the connection. Prefer this over `xhost +local:docker`.
xauth_args() {
    if [ -z "$DISPLAY" ] || [ -z "$XAUTHORITY" ] || [ ! -f "$XAUTHORITY" ]; then
        return
    fi
    local host_auth="$HOME/.ldm-xauthority"
    if ! cp "$XAUTHORITY" "$host_auth" 2>/dev/null; then
        return
    fi
    echo " -e XAUTHORITY=/tmp/ldm-xauthority -v $host_auth:/tmp/ldm-xauthority:rw"
}

# Run application
run() {
    prepare_m2
    local xa="$(xauth_args)"
    log "Running application with GUI..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e DISPLAY=$DISPLAY \
        $xa \
        -v /tmp/.X11-unix:/tmp/.X11-unix:rw \
        --ipc=host \
        $IMAGE_NAME \
        bash -c 'mvn -q -pl gui-gtk -am package -DskipTests=true && mvn -q -pl gui-gtk dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt && java --enable-native-access=ALL-UNNAMED -cp "gui-gtk/target/classes:core/target/classes:$(cat /tmp/cp.txt)" org.ldm.gtk.LinuxDeviceManagerApp'
}

# Run application in debug mode
debug() {
    prepare_m2
    local xa="$(xauth_args)"
    log "Running application in debug mode (port 5005) with GUI..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e DISPLAY=$DISPLAY \
        $xa \
        -v /tmp/.X11-unix:/tmp/.X11-unix:rw \
        -p 5005:5005 \
        --ipc=host \
        $IMAGE_NAME \
        bash -c 'mvn -q -pl gui-gtk -am package -DskipTests=true && mvn -q -pl gui-gtk dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt && java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=0.0.0.0:5005 --enable-native-access=ALL-UNNAMED -cp "gui-gtk/target/classes:core/target/classes:$(cat /tmp/cp.txt)" org.ldm.gtk.LinuxDeviceManagerApp'
}

# Build the Docker image
build() {
    log "Building Docker image..."
    if [ "${GITHUB_ACTIONS:-}" = "true" ] && docker buildx version >/dev/null 2>&1; then
        # CI: reuse GitHub Actions cache for apt + toolchain layers so
        # rebuilds only pay for changed layers. --load keeps the
        # $IMAGE_NAME tag available for subsequent `docker run` steps.
        docker buildx build \
            --build-arg "LDM_UID=$(id -u)" --build-arg "LDM_GID=$(id -g)" \
            --cache-from type=gha --cache-to type=gha,mode=max \
            -t "$IMAGE_NAME" --load .
    else
        docker build --build-arg "LDM_UID=$(id -u)" --build-arg "LDM_GID=$(id -g)" -t "$IMAGE_NAME" .
    fi
}

# Start development container
dev() {
    prepare_m2
    local xa="$(xauth_args)"
    log "Starting development container..."
    docker run --init -it --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        -e DISPLAY=$DISPLAY \
        $xa \
        -v /tmp/.X11-unix:/tmp/.X11-unix:rw \
        --name ldm-dev \
        $IMAGE_NAME
}

# Run tests under Xvfb (native GTK tests need a display; see README).
test() {
    prepare_m2
    log "Running tests..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        $IMAGE_NAME \
        bash -c "Xvfb :99 -screen 0 1024x768x24 -ac +extension GLX +render -noreset > /dev/null 2>&1 & sleep 2 && GTK_A11Y=none GDK_BACKEND=x11 GSK_RENDERER=cairo G_DEBUG=fatal-criticals mvn test"
}

# Build application
compile() {
    prepare_m2
    log "Building application..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        $IMAGE_NAME \
        mvn clean compile package -DskipTests=true
}

# Create packages (version defaults to the Maven version; an explicit
# argument must match it — the build files are the source of truth).
package() {
    prepare_m2
    local version="${1:-}"
    if [ -n "$version" ] && [[ ! "$version" =~ ^[0-9]+([.][0-9]+){1,3}$ ]]; then
        echo "Invalid package version: expected numeric dotted version" >&2
        return 2
    fi
    log "Creating packages${version:+ (version ${version})}..."
    docker run --init --rm \
        -v "$(pwd):/app" \
        -v "$HOME/.m2:/home/developer/.m2" \
        $IMAGE_NAME \
        /app/packaging/build-packages.sh $version
}

# Clean up
clean() {
    log "Cleaning up..."
    docker rmi $IMAGE_NAME 2>/dev/null || true
    docker system prune -f
}

# Show help
help() {
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  build     Build Docker image"
    echo "  dev       Start development container"
    echo "  test      Run the test suite under Xvfb"
    echo "  compile   Build application"
    echo "  run       Run application with GUI support"
    echo "  debug     Run application in debug mode (port 5005)"
    echo "  package   Create distribution packages"
    echo "  clean     Clean up Docker resources"
    echo "  help      Show this help"
}

# Main
case "${1:-help}" in
    build)   build ;;
    dev)     build && dev ;;
    test)    build && test ;;
    compile) build && compile ;;
    run)     build && run ;;
    debug)   build && debug ;;
    package) shift; build && package "$@" ;;
    clean)   clean ;;
    help)    help ;;
    *)       help ;;
esac
