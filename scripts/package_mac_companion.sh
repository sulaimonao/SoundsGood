#!/bin/bash
set -euo pipefail
if [[ $# != 2 ]]; then echo 'Usage: bash scripts/package_mac_companion.sh LOCAL_COMPANION_REPOSITORY NEW_OUTPUT_DIRECTORY' >&2; exit 2; fi
root=$(cd "$(dirname "$0")/.." && pwd)
source_repo=$(cd "$1" && pwd)
source_tree=b6e5953aa31d5a6057bb23248c4ee28ecec86c36
asset=SoundsGoodSync-0.3.1-macOS-universal.dmg
sdk=$(xcrun --sdk macosx --show-sdk-path)
compiler=$(xcrun --find swiftc)
git -C "$source_repo" cat-file -e "$source_tree^{tree}"
mkdir "$2"
output=$(cd "$2" && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/SoundsGoodPackage.XXXXXXXX")
trap 'rm -rf "$scratch"' EXIT
cd "$scratch"
mkdir -p input/Sources payload cache AppIcon.iconset
for source in SyncCore TLSIdentity TransferDiagnostics TransferReport HTTPServer OfflineFolderExport SoundsGoodSyncApp; do
    git -C "$source_repo" show "$source_tree:Sources/$source.swift" > "input/Sources/$source.swift"
done
app="$scratch/payload/SoundsGoodSync.app/Contents"
mkdir -p "$app/MacOS" "$app/Resources"
git -C "$source_repo" show "$source_tree:Info.plist" > "$app/Info.plist"
[[ $(plutil -extract CFBundleShortVersionString raw "$app/Info.plist") == 0.3.1 ]]
plutil -insert CFBundleIconFile -string AppIcon.icns "$app/Info.plist"
for size in 16 32 128 256 512; do
    for scale in 1 2; do
        pixels=$((size * scale)); suffix=''; if [[ $scale == 2 ]]; then suffix=@2x; fi
        sips -z "$pixels" "$pixels" "$root/branding/soundsgood-icon-v1.png" --out "AppIcon.iconset/icon_${size}x${size}${suffix}.png" >/dev/null
    done
done
iconutil -c icns AppIcon.iconset -o "$app/Resources/AppIcon.icns"
flags=(-O -swift-version 5 -sdk "$sdk" -module-cache-path "$scratch/cache")
if grep -q kSecImportToMemoryOnly "$sdk/System/Library/Frameworks/Security.framework/Headers/SecImportExport.h"; then
    flags+=(-D SOUNDSGOOD_MEMORY_ONLY_IMPORT_AVAILABLE)
fi
for arch in arm64 x86_64; do
    "$compiler" "${flags[@]}" -target "$arch-apple-macosx13.0" -module-name SoundsGoodSync input/Sources/*.swift -o "SoundsGoodSync-$arch"
done
lipo -create SoundsGoodSync-arm64 SoundsGoodSync-x86_64 -output "$app/MacOS/SoundsGoodSync"
case "$(lipo -archs "$app/MacOS/SoundsGoodSync")" in
    'x86_64 arm64'|'arm64 x86_64') ;;
    *) echo 'Expected exactly Apple silicon and Intel slices' >&2; exit 1 ;;
esac
identity=${SIGN_IDENTITY:--}
codesign --force --options runtime --timestamp=none --sign "$identity" "${app%/Contents}"
codesign --verify --all-architectures --strict "${app%/Contents}"
cp -X "$root/companion/INSTALL.txt" payload/INSTALL.txt
cp -X "$root/LICENSE" payload/Personal-Edition-License.txt
ln -s /Applications payload/Applications
hdiutil create -fs HFS+ -format UDZO -volname 'SoundsGood Sync 0.3.1' -srcfolder payload "$output/$asset"
hdiutil verify "$output/$asset"
shasum -a 256 "$output/$asset"
