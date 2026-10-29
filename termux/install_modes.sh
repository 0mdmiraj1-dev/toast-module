#!/data/data/com.termux/files/usr/bin/bash
# Install separate app_process client for V0.5. Leaves existing strongtoast untouched.
set -euo pipefail
: "${PREFIX:?Run under Termux, not a root shell}"
base_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
for tool in javac d8 su python base64; do
  command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }
done
[[ -f "$base_dir/S9ToastModes.java" ]] || { echo 'Java source missing' >&2; exit 1; }
work=$(mktemp -d "$HOME/s9-toast-build.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex" "$HOME/.strongtoast-modes" "$PREFIX/bin"
# Mirror user's active icon config from their current patched StrongToast.java,
# so HyperCeiler/custom drawable survives without modifying the old helper.
python - "$HOME/.strongtoast-build/StrongToast.java" "$HOME/.strongtoast-modes/icon.conf" <<'PY'
from pathlib import Path
import re,sys
src=Path(sys.argv[1]); target=Path(sys.argv[2]); data=src.read_text() if src.exists() else ''
def find(pattern, fallback):
    m=re.search(pattern,data)
    return m.group(1) if m else fallback
pkg=find(r'"package_name"\s*,\s*"([a-zA-Z][a-zA-Z0-9_.]*)"', 'android')
icon=find(r'iconResName\\":\\"([a-z][a-z0-9_]*)', 'sym_def_app_icon')
fmt=find(r'iconFormat\\":\\"(png|svg)', 'png')
if pkg == 'android' and icon != 'sym_def_app_icon':
    pkg,icon,fmt='android','sym_def_app_icon','png'
target.write_text(f'ICON_PACKAGE={pkg}\nICON_NAME={icon}\nICON_FORMAT={fmt}\n')
print(f'Icon: {pkg} / drawable/{icon} ({fmt})')
PY
echo '[1/3] Build separate Java helper + DEX...'
javac --release 8 -d "$work/classes" "$base_dir/S9ToastModes.java"
d8 --min-api 26 --output "$work/dex" "$work/classes/S9ToastModes.class"
[[ -s "$work/dex/classes.dex" ]] || { echo 'DEX not generated.' >&2; exit 1; }
echo '[2/3] Copy DEX as root (one Magisk grant)...'
su -c "mkdir -p /data/local/tmp/s9-toast-modes && cp '$work/dex/classes.dex' /data/local/tmp/s9-toast-modes/classes.dex.new && chmod 644 /data/local/tmp/s9-toast-modes/classes.dex.new && mv -f /data/local/tmp/s9-toast-modes/classes.dex.new /data/local/tmp/s9-toast-modes/classes.dex"
echo '[3/3] Install commands...'
for cmd in s9-toast-client strongtoastRGB toastTimer stopToastTimer; do
  install -m 755 "$base_dir/$cmd" "$PREFIX/bin/$cmd"
done
echo 'Ready: strongtoastRGB [-d seconds] "text" | toastTimer 30 | stopToastTimer'
echo 'Your original strongtoast command was NOT modified.'
