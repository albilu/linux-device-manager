# Linux Device Manager

[![Test CI](https://github.com/albilu/linux-device-manager/actions/workflows/test-ci.yml/badge.svg)](https://github.com/albilu/linux-device-manager/actions/workflows/test-ci.yml)
[![Release CI](https://github.com/albilu/linux-device-manager/actions/workflows/release-ci.yml/badge.svg)](https://github.com/albilu/linux-device-manager/actions/workflows/release-ci.yml)
[![GitHub release](https://img.shields.io/github/v/release/albilu/linux-device-manager)](https://github.com/albilu/linux-device-manager/releases)
[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
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

- GTK 4 (>= 4.14.5)
- policykit-1, udev/systemd
- Optional detail enrichment: `lspci` (pciutils), `lsusb` (usbutils), `modinfo` (kmod), `lshw`, `journalctl`, `dmesg`, `udevadm`

</details>

Packages are produced for Debian/Ubuntu (`.deb`), Fedora/RHEL (`.rpm`), Arch (`pkg.tar.zst`), plus portable AppImage and Flatpak. Each bundles a trimmed Java 25 runtime via `jlink`; GTK4 comes from the host (or GNOME runtime for Flatpak). Artifacts land in `packaging/dist/`.

```sh
# Debian/Ubuntu
sudo apt install ./packaging/dist/linux-device-manager_*.deb  # or sudo dpkg -i packaging/dist/linux-device-manager_*.deb

# Fedora/RHEL
sudo rpm -i packaging/dist/linux-device-manager-*.rpm

# Arch
sudo pacman -U packaging/dist/linux-device-manager-*.pkg.tar.zst

# AppImage (needs host GTK4)
./packaging/dist/LinuxDeviceManager-*-x86_64.AppImage

# Flatpak
flatpak install --user ./packaging/dist/org.ldm.LinuxDeviceManager.flatpak
flatpak run org.ldm.LinuxDeviceManager
```

> Flatpak sandbox note: inspection works fully; the privileged enable/disable helper is degraded in the sandbox.

## Building from source

Source builds use the `ldm-dev` Docker image (`eclipse-temurin:25-jdk` + GTK4 dev libs, hardware tooling, Xvfb, packaging toolchain) so host and CI share one environment:

```sh
make build     # build the ldm-dev Docker image
make compile   # compile and package check
make test      # full suite under Xvfb (headless GTK env pre-set)
make package   # build .deb, .rpm, pkg.tar.zst, AppImage
make run       # launch the app with GUI forwarding
make debug     # launch with suspended JDWP on port 5005
```

