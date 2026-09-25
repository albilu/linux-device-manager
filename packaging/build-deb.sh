#!/bin/sh
# Deprecated shim: the unified builder is packaging/build-packages.sh
# (or `make package`). Kept so existing callers and the Maven package-deb
# profile keep working.
set -eu
exec "$(dirname "$0")/build-packages.sh" "$@"
