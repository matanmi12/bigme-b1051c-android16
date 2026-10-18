# Signing keys (not in this repository)

- `platform.pk8` / `platform.x509.pem`: the **public AOSP test platform key**. TrebleDroid/phh GSIs are signed with it,
  so the SystemUI plugin (`apps/einkui`) must be signed with it to get `com.android.systemui.permission.PLUGIN`.
  Fetch it from AOSP with `tools/fetch-aosp-testkeys.sh` (certificate SHA-256 `C8A2E9BC…1192AB8`). Check that your
  GSI's SystemUI uses the same certificate first: `apksigner verify --print-certs SystemUI.apk`.
- `apps/debug.keystore`: created locally by `apps/einkctl/build.sh` on first build (any key works for that app).

Nothing in `keys/` is committed (see `.gitignore`).
