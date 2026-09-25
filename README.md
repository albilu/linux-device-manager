# Linux Device Manager

A Windows Device Manager-like graphical tool for Linux. Provides a clean, category-based interface to view, inspect, and manage hardware devices via sysfs/udev.

![Screenshot](docs/wili/images/screenshot.png)

## Features

- **Category tree** — devices grouped by type: Multimedia, Network, Storage, USB, PCI, etc.
- **Detail tabs** — General info, Advanced details, Driver info, Kernel logs/events
- **Device actions** — Enable / Disable devices (via polkit-protected privileged helper)
- **Status bar** — Loading spinner and messages for scans, detail loading, and device actions
- **GTK4 UI** — Defined via Cambalache `.ui` file (no programmatic widget creation)

Discovery includes Linux bus devices and block, network, sound, input, camera, and display class
entries. Canonical sysfs paths deduplicate aliases; a controller and its disk or network interface
remain separate entries. Functional categories take priority, with a PCI group for otherwise
unclassified PCI devices. USB composites are classified by all their functions, and Driver details
show each functional binding and its owning module.

Enable/Disable uses USB authorization for physical USB devices and driver binding for supported
bus devices. Enable and Disable support are checked separately; infrastructure such as RAM and CPUs
and class entries without a direct bus operation expose details only. RAM/CPU state uses their online
attributes. Each action carries the scanned device instance so the privileged helper can reject
disconnection or replacement at the same path before writing. The helper verifies the resulting state
before reporting success; authorizing a USB device does not require a kernel interface driver.
Deliberate driver unbinds are remembered for
the current application session; USB authorization is read from the kernel. After restarting the
app, an unbound device on a supported driver bus is shown as having no driver rather than assumed to
be disabled. USB functional categories are retained for the same connected instance during this
session when disabling removes its interfaces; replacement devices do not inherit that category.

The context menu is available with right-click, Shift+F10, or the Menu key. Type a device name in the
tree, use Ctrl+F, or use the search field to select matching names. Escape returns focus to the tree.
Detail tabs load when selected and are cached until the next inventory refresh. Explicit device
actions cancel pending detail work before starting. Refreshes are coalesced,
and the status spinner stays active until all outstanding work finishes. Kernel logs are snapshots
of the current boot, matched to device/interface identifiers rather than shared driver names.

## Architecture

Multi-module Maven project:

| Module | Path | Description |
|--------|------|-------------|
| `core` | `core/` | Device enumeration, categorization, detail providers, action service |
| `gui-gtk` | `gui-gtk/` | GTK4 UI (java-gi bindings) |
| `gui-qt` | `gui-qt/` | Qt UI — **V2 (planned)** |

Device data is gathered from sysfs, udev, and system tools (`lspci`, `lsusb`, `lshw`, `dmesg`, `modinfo`, etc.). Embedded static binaries are preferred over OS dependencies.

## Prerequisites

The reproducible dev/test/packaging environment is the `ldm-dev` Docker
image (`eclipse-temurin:25-jdk` + GTK4 dev libs, hardware tooling, Xvfb,
and the deb/rpm/Arch packaging toolchain). All workflows below run in it:

| Tool | Version | Notes |
|------|---------|-------|
| Docker | — | Builds the `ldm-dev` image |
| JDK | 25+ | java-gi GTK binding targets OpenJDK 25 (in the image) |
| Maven | 3.8+ | Build and tests (in the image) |
| GTK | 4.14.5+ | Minimum tested runtime for device action confirmation/error dialogs |

```sh
make build      # Build the ldm-dev Docker image (rebuilt by every target but clean)
make compile    # mvn clean compile package -DskipTests=true in Docker
make test       # Full suite under Xvfb in Docker (headless GTK env pre-set)
make run        # Launch the GTK app with X11 forwarding
make debug      # Suspended JDWP server on port 5005
```

To run Maven directly on the host, JDK 25 is required. If the default
`java` is older, set `JAVA_HOME` before running Maven:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn test
```

To run the native interaction tests on the host as well, install Xvfb,
Xauth, and `xdotool` and use:

```sh
GTK_A11Y=none GDK_BACKEND=x11 GSK_RENDERER=cairo G_DEBUG=fatal-criticals \
  JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 xvfb-run -a mvn test
```

Native tests exercise real pointer/key events and GTK dialogs with simulated device actions.
They never disable host hardware.

## Build

```sh
# Preferred: compile and package check in Docker
make compile

# Direct Maven equivalents
mvn test                        # Compile and run tests
mvn install -DskipTests         # Compile only (skip tests)
```

## Package

One stage tree (`packaging/stage`: application JARs + trimmed `jlink`
JRE + launcher + helper/policy + desktop/metainfo/icons) feeds every
format. `packaging/build-packages.sh` owns the pipeline; the version comes
from `pom.xml` (`0.1.0-SNAPSHOT` → package `0.1.0-1`).

```sh
make package                                   # .deb + .rpm + Arch + AppImage in Docker
packaging/flatpak/build-flatpak.sh             # Flatpak bundle from the same stage
packaging/verify-packages.sh 0.1.0            # cross-format + runtime + smoke checks
```

Output under `packaging/`:

| Artifact | Target |
|----------|--------|
| `linux-device-manager_0.1.0-1_amd64.deb` | Debian / Ubuntu |
| `linux-device-manager-0.1.0-1.x86_64.rpm` | Fedora / RHEL |
| `linux-device-manager-0.1.0-1-x86_64.pkg.tar.zst` | Arch Linux |
| `LinuxDeviceManager-0.1.0-x86_64.AppImage` | Portable (needs host GTK4) |
| `org.ldm.LinuxDeviceManager.flatpak` | Flatpak bundle (GNOME runtime) |

### Debian / Ubuntu (.deb)

The .deb bundles a trimmed JRE (via `jlink`), application JARs, a launcher script, a polkit-protected privileged helper, a `.desktop` entry, AppStream metainfo, and icons.

```sh
# Via Docker (preferred)
make package

# Directly on a Debian host
packaging/build-packages.sh

# Or via Maven profile
mvn package -Ppackage-deb -DskipTests
```

### Verify the packages

```sh
packaging/verify-packages.sh 0.1.0   # deb/rpm/arch equality + runtime + launcher smoke
packaging/verify-deb.sh               # .deb-only structural check (legacy)
python3 packaging/tests/test_verify_deb.py
```

Structural verification rejects incorrect metadata, missing runtime/application JARs, unsafe file
permissions, and missing launcher/helper/policy/install scripts. `verify-packages.sh`
additionally enforces byte-identical `opt`+`usr` payloads across formats and runs
the real launcher bounded under `xvfb-run` (exit 124 = ran until timeout = OK).

The optional `DPKG_INSTALL=1` mode of `verify-deb.sh` requires root in a disposable environment. It installs the
package, expects the smoke launch to run until its timeout, and removes the temporary installation
on both success and failure. It refuses to replace an existing installation.

### Install

```sh
sudo dpkg -i packaging/linux-device-manager_0.1.0-1_amd64.deb
```

Installed layout:

| Path | Content |
|------|---------|
| `/opt/linux-device-manager/runtime/` | Trimmed JRE (jlink) |
| `/opt/linux-device-manager/lib/` | Application JARs + dependencies |
| `/usr/bin/linux-device-manager` | Launcher script |
| `/usr/libexec/ldm-helper` | Privileged device helper |
| `/usr/share/polkit-1/actions/org.ldm.policy` | Polkit policy |
| `/usr/share/applications/linux-device-manager.desktop` | Desktop entry |
| `/usr/share/metainfo/org.ldm.LinuxDeviceManager.metainfo.xml` | AppStream metainfo |
| `/usr/share/icons/hicolor/` | App icons (SVG + raster PNGs) |

### Uninstall

```sh
sudo dpkg -r linux-device-manager
```

### AppImage

The AppImage reuses the same stage (bundled JRE + JARs) with an `AppRun`
launcher; like the `.deb` it requires the host GTK4 runtime:

```sh
./packaging/LinuxDeviceManager-0.1.0-x86_64.AppImage
```

### Flatpak / Flathub

The Flatpak manifest consumes the same stage and ships the identical
payload with desktop/metainfo/icons renamed to the app-id
(`org.ldm.LinuxDeviceManager`); GTK comes from the GNOME runtime:

```sh
packaging/flatpak/build-flatpak.sh
flatpak install --user packaging/org.ldm.LinuxDeviceManager.flatpak
flatpak run org.ldm.LinuxDeviceManager
```

Sandbox notes: device enumeration is read-only best-effort and the
privileged enable/disable helper is degraded in the sandbox; inspection
works fully. See `packaging/flatpak/flathub/FLATHUB.md` for the Flathub
submission checklist (license declaration + offline Maven vendoring).

## Roadmap

- **V1** — Complete functionality with GTK4 UI
- **V2** — Qt UI with all V1 features

## Development

```
linux-device-manager/
├── core/                   # Core device management library
├── gui-gtk/                # GTK4 UI (java-gi)
├── gui-qt/                 # Qt UI (planned)
├── helper/                 # Privileged helper script + polkit policy
├── packaging/              # .deb build scripts, desktop files, control files
└── docs/                   # Design specs and implementation plans
```
