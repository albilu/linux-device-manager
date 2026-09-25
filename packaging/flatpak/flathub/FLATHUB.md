# Flathub submission guide — Linux Device Manager

Bundle id: `org.ldm.LinuxDeviceManager` (GNOME 48 runtime).

## Local bundle (available now)

```sh
make package                                   # builds packaging/stage (+ deb/rpm/arch/AppImage)
packaging/flatpak/build-flatpak.sh             # -> packaging/org.ldm.LinuxDeviceManager.flatpak
flatpak install --user packaging/org.ldm.LinuxDeviceManager.flatpak
flatpak run org.ldm.LinuxDeviceManager
```

## Flathub submission (remaining steps)

1. **Declare a license.** Upstream has no `LICENSE` file yet. Flathub
   requires one; replace the `LicenseRef-UPSTREAM-TBD` placeholder in
   `packaging/resources/org.ldm.LinuxDeviceManager.metainfo.xml`,
   `License: UNKNOWN` in `packaging/rpm/linux-device-manager.spec`,
   `custom:UNKNOWN` in `packaging/arch/PKGBUILD`, and the license paragraph
   in `packaging/resources/copyright`.
2. **Vendor Maven deps offline.** Flathub builders have no network access:
   ```sh
   python3 flatpak-maven-generator.py --output maven-sources.json pom.xml
   ```
   Drop `maven-sources.json` into `packaging/flatpak/flathub/` and uncomment
   the `maven-deps` module in the Flathub manifest.
3. **Wire the source build.** Replace `__TAG__`/`__COMMIT__` with the release
   tag, implement the offline `mvn -o … install`, `jlink`, and install steps
   (mirror `packaging/build-packages.sh` + the renames in
   `packaging/flatpak/org.ldm.LinuxDeviceManager.yml`).
4. **Submit.** Fork `flathub/flathub`, add
   `org.ldm.LinuxDeviceManager.yml` (+ generated sources), open a PR, and
   address the `flathubbot` build + AppStream validation (`appstreamcli
   validate --pedantic`).
5. **Publish releases.** After acceptance, tag `vX.Y.Z`; verify the Flathub
   build matches the GitHub release artifacts (`verify-packages.sh` payload
   equality covers deb/rpm/arch — eyeball the Flatpak file list too).

## Known sandbox limitations

- Device enumeration is read-only best-effort: `/sys` visibility and host
  tools (`lspci`, `lsusb`, `modinfo`, `dmesg`, `journalctl`, `udevadm`) are
  limited inside the sandbox. Future work: reach the host via
  `flatpak-spawn --host` (permission `--talk-name=org.freedesktop.Flatpak`
  is already granted).
- Enable/Disable actions need the privileged helper + polkit on the host and
  are degraded in the sandbox; the UI remains fully usable for inspection.
- The app honors `XDG_*_HOME` with fallbacks (no hardcoded `~/.config`),
  so per-app data dirs work under Flatpak without changes.
