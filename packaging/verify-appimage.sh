#!/bin/bash
# Run in a clean Ubuntu 22.04+ container with Xvfb, xauth, xdotool, dbus-x11,
# fontconfig, fonts-dejavu-core, libharfbuzz0b, libfribidi0, libwayland-client0,
# file and python3, but WITHOUT GTK4 or Java. These are the desktop libraries
# AppImage's excludelist requires us to leave on the host.
set -euo pipefail
APPIMAGE=$(realpath "${1:?Pass the AppImage to verify}")
if ldconfig -p | grep 'libgtk-4\.so' >/dev/null; then
    echo 'AppImage portability must be tested without host GTK4' >&2
    exit 1
fi
CHECK_ROOT=$(mktemp -d)
trap 'chmod -R u+w "$CHECK_ROOT"; rm -rf "$CHECK_ROOT"' EXIT
# Exercise relocation, spaces, and a read-only AppDir (as with a FUSE mount).
mkdir "$CHECK_ROOT/path with spaces"
cp "$APPIMAGE" "$CHECK_ROOT/app.AppImage"
chmod +x "$CHECK_ROOT/app.AppImage"
(cd "$CHECK_ROOT/path with spaces" && "$CHECK_ROOT/app.AppImage" --appimage-extract >/dev/null)
APPDIR="$CHECK_ROOT/path with spaces/squashfs-root"
test -x "$APPDIR/AppRun"
test -s "$APPDIR/usr/lib/ldm/libgtk-4.so.1"
test "$(file -Lb --mime-type "$APPDIR/.DirIcon")" = image/png
cmp "$APPDIR/usr/share/appdata/linux-device-manager.appdata.xml" \
    "$APPDIR/usr/share/metainfo/io.github.getldm.linux-device-manager.metainfo.xml"
if find "$APPDIR" -name 'libc.so*' -o -name 'ld-linux*' | grep . >/dev/null; then
    echo 'AppImage must use the host libc and loader' >&2
    exit 1
fi
chmod -R a-w "$APPDIR"
mkdir "$CHECK_ROOT/tmp"
export TMPDIR="$CHECK_ROOT/tmp"
export XDG_CONFIG_HOME="$CHECK_ROOT/config" XDG_DATA_HOME="$CHECK_ROOT/data"
export XDG_STATE_HOME="$CHECK_ROOT/state"
export XDG_CACHE_HOME="$CHECK_ROOT/cache" LANG=C.UTF-8 GTK_A11Y=none GDK_BACKEND=x11
unset LD_LIBRARY_PATH GDK_PIXBUF_MODULEDIR GDK_PIXBUF_MODULE_FILE

timeout -k 5s 90s xvfb-run -a -s '-screen 0 1280x900x24' dbus-run-session -- \
    python3 - "$APPDIR" "$CHECK_ROOT" <<'PY'
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

appdir, root = map(Path, sys.argv[1:])
gui_jars = list((appdir / 'opt/linux-device-manager/lib').glob('ldm-gui-gtk-*.jar'))
assert len(gui_jars) == 1, f'Expected exactly one GUI JAR: {gui_jars}'
version = gui_jars[0].name.removeprefix('ldm-gui-gtk-').removesuffix('.jar')
log = root / 'startup.log'
with log.open('w') as output:
    app = subprocess.Popen([str(appdir / 'AppRun')], stdout=output,
                           stderr=subprocess.STDOUT, start_new_session=True)
    try:
        deadline = time.monotonic() + 40
        window = None
        while time.monotonic() < deadline:
            assert app.poll() is None, f'AppImage exited early: {app.returncode}'
            search = subprocess.run(['xdotool', 'search', '--onlyvisible', '--name',
                                     '^Linux Device Manager$'], capture_output=True, text=True)
            if search.returncode == 0:
                window = search.stdout.splitlines()[0]
                break
            time.sleep(0.5)
        assert window, 'AppImage did not display its main window'
        # Exercise the actual rendered GTK UI without changing hardware state.
        subprocess.run(['xdotool', 'windowfocus', '--sync', window], check=True)
        subprocess.run(['xdotool', 'key', 'ctrl+f'], check=True)
        subprocess.run(['xdotool', 'type', '--clearmodifiers', 'usb'], check=True)
        subprocess.run(['xdotool', 'key', 'Escape'], check=True)
        # Match the catalog's 30-second survival check, and prove that GTK was
        # loaded from the relocated AppDir rather than an accidental system copy.
        time.sleep(30)
        assert app.poll() is None, f'AppImage crashed: {app.returncode}'
        mappings = ''
        for child in Path(f'/proc/{app.pid}/task/{app.pid}/children').read_text().split():
            mappings += Path(f'/proc/{child}/maps').read_text()
        for library in ('libgtk-4.so.1', 'libglib-2.0.so.0', 'libgobject-2.0.so.0',
                        'libgio-2.0.so.0', 'libgdk_pixbuf-2.0.so.0'):
            assert str(appdir / 'usr/lib/ldm' / library) in mappings, library
        text = log.read_text()
        for error in ('Exception', 'CRITICAL', 'symbol lookup error', 'not found', 'Startup failed'):
            assert error not in text, text
        app_log = (root / 'state/linux-device-manager/linux-device-manager.log').read_text()
        assert f'Linux Device Manager {version} starting' in app_log, app_log
        print(f'OK: AppImage {version} displayed a window, accepted keyboard input and survived 30 seconds without host GTK4')
    finally:
        if app.poll() is None:
            os.killpg(app.pid, signal.SIGTERM)
        try:
            app.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(app.pid, signal.SIGKILL)
            app.wait()
        print(log.read_text())
assert not list((root / 'tmp').glob('ldm-appimage.*')), 'AppRun leaked its loader cache'
PY
