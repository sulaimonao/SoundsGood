# Mac distribution build

This checkout contains the packaging recipe and artwork, not the commercial
Android app or the Mac implementation. Supply an authorized local Git checkout
containing the pinned `companion/macos` tree. The recipe reads the plist and
seven named production Swift files from Git objects, never from a built app,
an active worktree, or a user's library. It needs installed macOS tools and a
Swift compiler and SDK capable of targeting macOS 13 on both architectures.

```sh
bash scripts/package_mac_companion.sh /path/to/authorized/checkout /path/to/new-output
```

By default the result is ad-hoc signed. To build a Developer ID review copy,
set `SIGN_IDENTITY` to the SHA-1 of your authorized **Developer ID Application**
certificate. This offline recipe disables network timestamping; it does not
submit for notarization and is not the final public signing procedure. The
app is sealed only after the icon is embedded. The DMG is not separately signed.
Rebuilding reproduces the layout and pinned source inputs, not necessarily
byte-identical signatures, filesystem metadata or disk-image bytes.

`branding/soundsgood-icon-v1.png` is the original master. The opaque store
image is a derivative, not a replacement Android adaptive launcher. Regenerate
with `sips -z 512 512 branding/soundsgood-icon-v1.png --out branding/soundsgood-store-v1-512.png`.
Keep generated apps, DMGs, scratch files and signing material outside Git.
