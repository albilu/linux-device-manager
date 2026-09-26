# Linux Device Manager

[![Test CI](https://github.com/getldm/linux-device-manager/actions/workflows/test-ci.yml/badge.svg)](https://github.com/getldm/linux-device-manager/actions/workflows/test-ci.yml)
[![Release CI](https://github.com/getldm/linux-device-manager/actions/workflows/release-ci.yml/badge.svg)](https://github.com/getldm/linux-device-manager/actions/workflows/release-ci.yml)
[![GitHub release](https://img.shields.io/github/v/release/getldm/linux-device-manager)](https://github.com/getldm/linux-device-manager/releases)
[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform: Linux](https://img.shields.io/badge/Platform-Linux-lightgrey.svg)](packaging/)

> A native GTK device manager for Linux — every bus, driver, and kernel event in one clean category tree, with safe enable/disable.

Linux Device Manager (LDM) combines sysfs, udev, and proven tools — `lspci`, `lsusb`, `modinfo`, `journalctl`, `dmesg` — with detail views, driver info, and a polkit-protected privileged helper for device actions.

[Features](#features) · [Installation](#installation) · [Building from source](#building-from-source)

![LDM main window](docs/wiki/images/screenshot.png)

## Features

- Category tree — Multimedia, Network, Storage, USB, PCI, and more, with functional categories taking priority
- Detail tabs — General, Advanced, Driver, and Kernel logs/events, loaded lazily and cached until refresh
- Safe Enable / Disable — USB authorization + driver bind/unbind via polkit helper, with state verification
- Accurate identity — canonical sysfs paths deduplicate aliases; controllers stay separate from their disks/NICs
- USB composites classified by all functions; Driver tab shows each binding and its owning module
- Kernel logs as current-boot snapshots matched to device/interface IDs, not shared driver names
- Fast search — type in tree, `Ctrl+F`, or search field; `Esc` returns focus; right-click / `Shift+F10` / Menu key menu
- Responsive scans — coalesced refreshes with status spinner until all work finishes
- Native GTK4 UI defined in Cambalache (`main.ui`), XDG-compliant, no hardcoded home paths

## Installation

<details>
<summary>Requirements</summary>

- GTK 4 (>= 4.14.5) for native packages; bundled in the AppImage
- policykit-1, udev/systemd
- Optional detail enrichment: `lspci` (pciutils), `lsusb` (usbutils), `modinfo` (kmod), `lshw`, `journalctl`, `dmesg`, `udevadm`

</details>

Packages are produced for Debian/Ubuntu (`.deb`), Fedora/RHEL (`.rpm`), Arch (`pkg.tar.zst`), plus a portable AppImage. Each bundles a trimmed Java 25 runtime via `jlink`. The AppImage also bundles GTK4 and runs on glibc 2.35+ systems (Ubuntu 22.04 or newer) without installing GTK or Java. Native packages use the host GTK4. Device enable/disable requires the host-installed privileged helper and polkit policy provided by a native package.

```sh
# Debian/Ubuntu
sudo apt install ./linux-device-manager_*.deb  # or sudo dpkg -i linux-device-manager_*.deb

# Fedora/RHEL
sudo rpm -i linux-device-manager-*.rpm

# Arch
sudo pacman -U linux-device-manager-*.pkg.tar.zst

# AppImage (bundles GTK4 and Java)
chmod +x LinuxDeviceManager-*-x86_64.AppImage
./LinuxDeviceManager-*-x86_64.AppImage
```

## Building from source

Source builds use the `ldm-dev` Docker image (`eclipse-temurin:25-jdk-jammy` + GTK 4.14.5 built from pinned sources, hardware tooling, Xvfb, packaging toolchain) so host and CI share one environment. Building GTK on Ubuntu 22.04 keeps the AppImage's glibc requirement at 2.35; its X11 and Wayland backends are both included.

```sh
make build     # build the ldm-dev Docker image
make compile   # compile and package check
make test      # full suite under Xvfb (headless GTK env pre-set)
make package   # build .deb, .rpm, pkg.tar.zst, AppImage
make run       # launch the app with GUI forwarding
make debug     # launch with suspended JDWP on port 5005
```
