#!/usr/bin/env bash
# flash-inkognito.sh - macOS / Linux front end for inkognito.py, the installer itself. Same commands and options:
#     bash flash-inkognito.sh                       install (the same as: bash flash-inkognito.sh install)
#     bash flash-inkognito.sh --dry-run             show every step and command, touch nothing
#     bash flash-inkognito.sh update | restore-stock --backup <folder> | backup | prepare --backup <folder>
#     bash flash-inkognito.sh install --help        all options
# Needs Python 3.8 or newer (mtkclient needs it too). Read INSTALL.md first. Licensed MIT.
HERE=$(cd "$(dirname "$0")" && pwd)
PY=""
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(sys.version_info < (3, 8))' 2>/dev/null; then PY=$c; break; fi
done
[ -n "$PY" ] || { echo "Python 3.8 or newer is needed (INSTALL.md, step 2)."; exit 1; }
case "${1:-}" in
  install|update|restore-stock|backup|prepare|-h|--help) ;;
  *) set -- install "$@" ;;
esac
exec "$PY" "$HERE/inkognito.py" "$@"
