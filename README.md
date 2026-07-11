# Linux Device Manager

A Windows Device Manager like program for Linux. The program is intended to be an advanced and easy to use device management tool for Linux users. It will provide a graphical interface to view and manage devices on the system.

## Hard Requirements

- The program should really provide an Windows Device Manager like experience for Linux users. It should be able to display all devices on the system, including those that are not currently active or connected. It should also provide detailed information about each device, including its driver, status, and any errors or warnings.
- UI design and layout `gui-gtk/main.ui` and `gui-gtk/image.png`: don't deviate from it.
- Use the UI file instead of programmatically creating the UI.

## Tech Stack

- GTK UI (Java GI)
- Qt UI (Qt Jambi)
- Leverage Linux system APIs and existing programs like below to gather extended and comprehensive device information:
    - lsusb
    - lspci
    - dmesg
    - udevadm
    - modprobe
    - modinfo
    - systemctl
    - dmesg
    - lshw
    - etc. (or any other appropriate tools)

## Features

- Tree view of devices by category with details and actions:
    - List devices by group categories (Multimedia, Network, Storage, USB, PCI, etc.)

- Device details Panel with tabs:
    - General info
    - Advanced details
    - Driver details
    - Sys Logs/events

- Context Menu Actions
    - Enable/Disable device

- Status bar with loading/progress indicators for long running operations

## Roadmap

V1: Complete functionality and features with GTK UI.
V2: QT UI with all features of V1.

## Architecture

Multi modules:

- core: core device management library
- gui-gtk: GTK UI
- gui-qt: Qt UI

## Distribution

- deb package for Debian/Ubuntu based distributions
- appimage for other distributions

NOTE: Embedded static binaries (for example, `lsusb`, `lspci`, etc.) are preferred over external or OS dependencies to avoid issues with different versions of the same tool on different distributions.

## Build

Required tools:

- JDK 25+ (java-gi GTK binding targets OpenJDK 25) and Maven for tests and JVM builds
- `dpkg-deb` for Debian package assembly
- `jlink` (bundled with JDK 25+) for trimmed runtime creation

If the default `java` is older than 25, set `JAVA_HOME` to a JDK 25+ install before running Maven.

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn test
```

## Packaging

### Prerequisites

- JDK 25+ (`JAVA_HOME` must point to a JDK 25+ with `jlink`)
- `dpkg-deb` (Debian/Ubuntu)
- Maven 3.8+

### Build the .deb

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 packaging/build-deb.sh
```

Or via Maven:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn package -Ppackage-deb -DskipTests
```

Output: `packaging/dist/linux-device-manager_0.1.0-1_amd64.deb`

### Verify the .deb

```sh
packaging/verify-deb.sh
```

### Install

```sh
sudo dpkg -i packaging/dist/linux-device-manager_0.1.0-1_amd64.deb
```

The package installs:

- App + bundled JRE -> `/opt/linux-device-manager/`
- Launcher -> `/usr/bin/linux-device-manager`
- Privileged helper -> `/usr/libexec/ldm-helper`
- Polkit policy -> `/usr/share/polkit-1/actions/org.ldm.policy`
- Desktop entry -> `/usr/share/applications/linux-device-manager.desktop`
- App icon -> `/usr/share/icons/hicolor/scalable/apps/linux-device-manager.svg`

### Uninstall

```sh
sudo dpkg -r linux-device-manager
```

### AppImage

Deferred for V1. GTK4 library bundling in AppImage is non-trivial (see V1 design spec).
