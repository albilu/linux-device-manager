#!/usr/bin/env python3
"""Copy GTK's native dependency closure into the staged AppDir."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def run(*args):
    return subprocess.check_output(args, text=True)


appdir = Path(sys.argv[1]).resolve()
prefix = Path(run("pkg-config", "--variable=prefix", "gtk4").strip())
subprocess.run(["pkg-config", "--atleast-version=4.14.5", "gtk4"], check=True)
libdir = appdir / "usr/lib/ldm"
libdir.mkdir(parents=True, exist_ok=True)
licenses = appdir / "usr/share/doc/linux-device-manager/native-libraries"
licenses.mkdir(parents=True, exist_ok=True)

# These belong to the host C/C++ runtime, display and font stack. Follow
# AppImage/AppImages' excludelist so newer host graphics drivers cannot load
# older X11/Wayland/font libraries from our GTK closure. Match versioned
# SONAMEs as well as unversioned names; never copy libc.so.6.
excluded = re.compile(
    r"(?:ld-linux.*|lib(?:c|m|mvec|dl|rt|pthread|resolv|nsl|util|anl|BrokenLocale|cidn|thread_db|"
    r"nss_[^.]+|stdc\+\+|gcc_s|GL|GLX|GLdispatch|EGL|OpenGL|vulkan|drm|glapi|gbm|"
    r"xcb|X11|X11-xcb|wayland-client|fontconfig|freetype|harfbuzz|fribidi|expat|uuid|z)\.so(?:\..*)?)$"
)
search = [prefix / "lib", Path("/usr/lib/x86_64-linux-gnu")]


def resolve(name):
    for directory in search:
        candidate = directory / name
        if candidate.is_file():
            return candidate
    raise RuntimeError(f"Required native library is missing: {name}")


copied = set()
packages = set()


def bundle(source, destination=None):
    source = Path(source)
    name = source.name
    if excluded.fullmatch(name) or name in copied:
        return
    copied.add(name)
    output = run("ldd", str(source))
    if "not found" in output:
        raise RuntimeError(f"Unresolved dependencies for {source}:\n{output}")
    target = destination or libdir / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source.resolve(), target)
    # Relative RUNPATH keeps libraries private to this JVM. Host tools such
    # as udevadm/journalctl must not inherit an AppImage LD_LIBRARY_PATH.
    rpath = os.path.relpath(libdir, target.parent)
    subprocess.run(["patchelf", "--set-rpath", f"$ORIGIN/{rpath}", str(target)], check=True)
    for line in output.splitlines():
        match = re.match(r"\s*\S+\s+=>\s+(/\S+)\s+\(", line)
        if match:
            bundle(match[1])
    if not source.is_relative_to(prefix):
        # Carry the distro's copyright/source notices alongside its libraries.
        for candidate in (source, source.resolve()):
            result = subprocess.run(["dpkg-query", "-S", str(candidate)], text=True,
                                    capture_output=True)
            if result.returncode == 0:
                packages.add(result.stdout.split(": ", 1)[0].split(":", 1)[0])
                break


for name in ("libgtk-4.so.1", "libglib-2.0.so.0", "libgobject-2.0.so.0",
             "libgio-2.0.so.0", "libgmodule-2.0.so.0", "libgdk_pixbuf-2.0.so.0",
             "libpangocairo-1.0.so.0", "libcairo-gobject.so.2",
             "libharfbuzz-gobject.so.0"):
    bundle(resolve(name))

# GdkPixbuf dlopens image loaders; ldd on GTK alone cannot discover them.
pixbuf_dir = Path(run("pkg-config", "--variable=gdk_pixbuf_moduledir", "gdk-pixbuf-2.0").strip())
for loader in sorted(pixbuf_dir.glob("*.so")):
    bundle(loader, libdir / "gdk-pixbuf-loaders" / loader.name)
query = Path(run("pkg-config", "--variable=gdk_pixbuf_query_loaders", "gdk-pixbuf-2.0").strip())
if not query.is_file() or not (libdir / "gdk-pixbuf-loaders/libpixbufloader-svg.so").is_file():
    raise RuntimeError("GdkPixbuf loader tools or the SVG loader are missing")
bundle(query, appdir / "usr/libexec/gdk-pixbuf-query-loaders")

share = appdir / "usr/share"
schemas = share / "glib-2.0/schemas"
schemas.mkdir(parents=True, exist_ok=True)
for schema in (prefix / "share/glib-2.0/schemas").glob("*.xml"):
    shutil.copy2(schema, schemas / schema.name)
subprocess.run([str(prefix / "bin/glib-compile-schemas"), str(schemas)], check=True)
for name in ("icons/Adwaita", "mime"):
    shutil.copytree(Path("/usr/share") / name, share / name, symlinks=False, dirs_exist_ok=True)
shutil.copytree(prefix / "share/locale", share / "locale", dirs_exist_ok=True)
shutil.copytree(prefix / "share/doc/ldm-gtk", licenses / "ldm-gtk", dirs_exist_ok=True)
for package in sorted(packages):
    copyright_file = Path("/usr/share/doc") / package / "copyright"
    if copyright_file.is_file():
        shutil.copy2(copyright_file, licenses / f"{package}-copyright")
(licenses / "packages.txt").write_text(run("dpkg-query", "-W", "-f=${Package} ${Version}\n", *sorted(packages)))
print(f"Bundled {len(copied)} native libraries/tools with GTK {run('pkg-config', '--modversion', 'gtk4').strip()}")
