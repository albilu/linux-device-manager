# Linux Device Manager Agent Guide

## Repository Shape
- The root Maven reactor builds `core` and `gui-gtk`; `helper/` holds the
  polkit-protected privileged helper script + policy (not a Maven module).
- `core` owns device enumeration (sysfs), categorization, detail providers,
  the action service, and the `udev`/tool integrations (`lspci`, `lsusb`,
  `modinfo`, `journalctl`, `dmesg`, `udevadm` via `ToolLocator`); `gui-gtk`
  owns the GTK4 application and depends on `core`.
- The production entry point is `org.ldm.gtk.LinuxDeviceManagerApp`; the
  in-code GTK application id is `io.github.getldm.linux-device-manager`
  (matching the Flatpak id, so no `--own-name` permission is needed, and
  `StartupWMClass` mirrors it for window matching).
- The build targets Java 25 (java-gi bindings). The Docker image is based on
  `eclipse-temurin:25-jdk` and supplies the native tools and GTK libraries.

## Commands
- Use `make build` to build the `ldm-dev` Docker image. Every other `make`
  target except `clean` also rebuilds the image first.
- Use `make compile` for the canonical compile/package check; it runs
  `mvn clean compile package -DskipTests=true` in Docker.
- Use `make test` for the full suite; it starts Xvfb in Docker and runs
  `mvn test` with the headless GTK env (`GTK_A11Y=none GDK_BACKEND=x11
  GSK_RENDERER=cairo`). The image provides pciutils, usbutils, lshw, kmod,
  udev/systemd, GTK4, xdotool, and Xvfb.
- Run one test with `mvn -pl core -Dtest=ClassName#methodName test` inside
  the prepared Java/native-tool environment.
- Use `make run` to launch the GTK application with X11 forwarding; use
  `make debug` for the suspended JDWP server on port 5005.
- Use `make package` to create `.deb`, `.rpm`, `.pkg.tar.zst`, and AppImage
  artifacts under `packaging/dist/` from the single `packaging/stage` tree
  (JARs + bundled jlink runtime); use `packaging/flatpak/build-flatpak.sh`
  for the Flatpak bundle, which consumes the same stage.

## Testing Constraints
- Core tests are hermetic (fake sysfs/command runners); `NativeWorkflowTest`
  drives real pointer/key events via `xdotool` and needs a display — prefer
  the Docker commands over host execution when dependencies are missing.
- The native interaction tests never disable host hardware.
- Add tests under the module they cover using JUnit 5 `*Test.java` naming.
- `packaging/verify-packages.sh` enforces byte-identical `opt`+`usr`
  payloads across deb/rpm/arch plus a bounded launcher smoke test; the
  legacy `packaging/verify-deb.sh` (+ `packaging/tests/test_verify_deb.py`)
  still guards the .deb alone.

## GTK Boundaries
- The UI is defined in `gui-gtk/src/main/resources/main.ui` (Cambalache) and
  loaded via `GtkBuilder`; never build widgets programmatically. Preserve
  widget IDs — lookup is fail-fast, so an ID mismatch breaks window
  construction.
- Do not redesign or casually edit the existing `.ui` layout.
- Icon artwork is canonical in
  `gui-gtk/src/main/resources/icons/hicolor/` (SVG + generated PNGs +
  `index.theme`); regenerate with `packaging/render-icons.sh` after edits.
  The About dialog logo (`/linux-device-manager.svg` on the classpath) is
  a separate copy — keep both in sync.

## Packaging Rules
- One stage tree (`packaging/stage`) feeds every format; never assemble a
  format from a private file list. `packaging/build-packages.sh` owns the
  whole pipeline (version → JARs → jlink → stage → deb/rpm/arch/AppImage).
- The version in `pom.xml` is the source of truth (`0.1.0-SNAPSHOT` →
  package `0.1.0-1`); an explicit argument must match it. CI never rewrites
  versions — tags (`v*`) must equal the Maven version.
- The `.deb` `Depends` floor (`libgtk-4-1 >= 4.14.5`, `policykit-1`, `udev`)
  is load-bearing for the device action dialogs; keep the Dockerfile GUI
  libs, the rpm spec, and the PKGBUILD in sync with it.
- AppStream needs raster icons (64x64 mandatory) and the metainfo stock
  `<icon>` must match the installed id; Flatpak renames desktop/metainfo/
  icons to the app-id at build time.
- Upstream has no `LICENSE` file yet: `UNKNOWN`/`custom:UNKNOWN`/
  `LicenseRef-UPSTREAM-TBD` placeholders mark every metadata slot (see
  `packaging/flatpak/flathub/FLATHUB.md`). Replace them all when a license
  is declared — never invent one.

## Project Rules
- Do not leave methods unimplemented.
- Keep changes aligned with the existing sysfs/udev and native Linux tool model.
- The app must honor `XDG_*_HOME` with fallbacks; never hardcode
  `~/.config` or `~/.local/share` (breaks sandboxed installs).
- Do not add README, example, or demo files unless explicitly requested.
