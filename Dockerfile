# AppImageHub runs on Ubuntu 22.04 (glibc 2.35). Keep the native ABI floor
# there, including the libraries bundled into the AppImage.
FROM eclipse-temurin:25-jdk-jammy

# Avoid interactive prompts
ENV DEBIAN_FRONTEND=noninteractive

# Install all dependencies in one layer: build tools, GTK dev libraries
# (GTK >= 4.14.5 is built below; Jammy's GTK development package supplies
# its build dependencies),
# the native tools under test, X11 test support, and the packaging toolchain.
RUN apt-get update && apt-get install -y \
    # Maven (JDK 25 comes from the base image)
    maven \
    # Build tools
    git \
    gettext \
    locales \
    curl \
    wget \
    build-essential \
    ninja-build \
    python3-pip \
    python3-packaging \
    libpcre2-dev \
    libffi-dev \
    libmount-dev \
    libxml2-dev \
    libdrm-dev \
    libtiff-dev \
    # GTK libraries for java-gi (GTK4)
    libgtk-4-dev \
    libglib2.0-dev \
    pkg-config \
    # Native tools under test (ToolLocator targets, see core/Main.java)
    pciutils \
    usbutils \
    v4l-utils \
    lshw \
    kmod \
    udev \
    systemd \
    # X11 for GUI testing (NativeWorkflowTest drives real pointer/key events)
    xvfb \
    x11-utils \
    dbus-x11 \
    xauth \
    xdotool \
    # Package building tools (deb/rpm/Arch/AppImage)
    dpkg-dev \
    fakeroot \
    rpm \
    file \
    zstd \
    libarchive-tools \
    librsvg2-bin \
    desktop-file-utils \
    patchelf \
    # Utilities
    vim \
    tree \
    && rm -rf /var/lib/apt/lists/*

# Use the same GTK build for tests and AppImage packaging. Building it on
# Jammy avoids importing Noble/Resolute glibc requirements into the AppImage.
RUN python3 -m pip install --no-cache-dir meson==1.4.2
COPY packaging/appimage/build-gtk.sh /tmp/ldm-build-gtk.sh
RUN bash /tmp/ldm-build-gtk.sh && rm /tmp/ldm-build-gtk.sh
ENV PKG_CONFIG_PATH=/opt/ldm-gtk/lib/pkgconfig:/opt/ldm-gtk/share/pkgconfig
ENV LD_LIBRARY_PATH=/opt/ldm-gtk/lib
ENV XDG_DATA_DIRS=/opt/ldm-gtk/share:/usr/local/share:/usr/share

# Exercise gettext with installed desktop locales, including French regional fallback.
RUN localedef -i en_US -f UTF-8 en_US.UTF-8 && \
    localedef -i fr_FR -f UTF-8 fr_FR.UTF-8 && \
    localedef -i de_DE -f UTF-8 de_DE.UTF-8

# JAVA_HOME is already set by the temurin base image

# Match the checkout owner, including CI runners whose UID is not 1000.
ARG LDM_UID=1000
ARG LDM_GID=1000
RUN if [ "$LDM_UID" != 0 ]; then \
        existing_user="$(getent passwd "$LDM_UID" | cut -d: -f1)"; \
        if [ -n "$existing_user" ]; then userdel "$existing_user"; fi; \
        if ! getent group "$LDM_GID" >/dev/null; then groupadd -g "$LDM_GID" developer; fi; \
        useradd -m -d /home/developer -s /bin/bash -u "$LDM_UID" -g "$LDM_GID" developer; \
    else mkdir -p /home/developer; fi && \
    mkdir -p /app /home/developer/.m2 && \
    chown -R "$LDM_UID:$LDM_GID" /app /home/developer

WORKDIR /app
# Explicit home keeps the Maven cache consistent for root callers too.
ENV MAVEN_OPTS="-Duser.home=/home/developer"
USER ${LDM_UID}:${LDM_GID}

# Fixed display for headless GUI tests (Xvfb started by docker-build.sh test).
ENV DISPLAY=:99

# Default command
CMD ["/bin/bash"]
