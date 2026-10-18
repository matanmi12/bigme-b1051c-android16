# b1051c-gapps: minimal Google apps Magisk module (NOT installed)

For: Bigme B1051C Pro running TrebleDroid Android 16 GSI `treble_arm64_bvS` (userdebug, test-keys, SDK 36), Magisk 30.7.
Prepared offline on the Mac on 2026-10-18. Nothing was pushed to the device.

## Source and checksums

- Upstream: MindTheGapps **MindTheGapps-16.0.0-arm64-20260915_222150** (latest release, published 2026-09-15)
  https://github.com/MindTheGapps/16.0.0-arm64/releases/tag/MindTheGapps-16.0.0-arm64-20260915_222150
- MTG zip SHA-256: `d60f550e4dc96606644fbbf886a9892d1391a607225e861ceaef27bd999bc6a3`
  - matches the published `.zip.sha256sum` and the GitHub API asset digest
  - the SignApk whole-file signature verifies against the published `release.x509.pem` (SHA-256 fingerprint 91:37:0E:89:...:F5:67:76). This is MTG's own release key, not the public AOSP testkey.
- APK signatures (apksigner): GmsCore uses the standard Google lineage f0fd6c5b… → 7ce83c1b… → 5f239127… (shared-UID capability). GSF and Phonesky are signed with 7ce83c1b….
- Module zip: `module/b1051c-gapps-16.0.0-arm64-20260915_222150.zip`
  SHA-256 `c4f7695bd42c60a3edd30c71c8d1d96bafcb38c8ef95acab5c5fe7c3bc7f485a`
- Per-file hashes are in `SHA256SUMS.txt`.

## What is included and why

| Module path (magic-mounted under /system) | Size | Why |
|---|---|---|
| `product/priv-app/GmsCore/GmsCore.apk` | 175,350,630 | Google Play services 25.15.32 (com.google.android.gms), minSdk 35 / targetSdk 36 |
| `product/priv-app/Phonesky/Phonesky.apk` | 88,697,354 | Play Store 45.7.17 (com.android.vending), minSdk 31 / targetSdk 35 |
| `system_ext/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk` | 4,387,851 | GSF 16-13343139 (com.google.android.gsf), minSdk 36. It shares `com.google.uid.shared` with GmsCore, so it is required. |
| `product/overlay/GmsOverlay.apk` | 12,680 | MTG static RRO for `android`. Required because it adds gms and vending to `config_forceQueryablePackages`. GmsCore does not declare `forceQueryable`, and the GSI default list is only settings/providers.settings. Without this, apps targeting API 30+ cannot see Play services or Play Store ("Google Play services missing", billing fails). It also sets the Credential Manager provider and shared-account auth for vending. |
| `product/etc/permissions/privapp-permissions-google-product.xml` | 11,690 | Privileged-permission allowlist: the gms and vending blocks only (taken verbatim from the MTG file) |
| `system_ext/etc/permissions/privapp-permissions-google-system-ext.xml` | 1,596 | Allowlist: the gsf block only |
| `product/etc/sysconfig/google.xml` | 1,795 | Doze/data-saver allowlists for gms/vending, implicit broadcasts (checkin, gservices, c2dm), gms backup transports, update-ownership (vending updates gms), and app-links for gms/vending |
| `product/etc/sysconfig/google-hiddenapi-package-allowlist.xml` | 420 | Hidden-API exemption for gsf only |
| `module.prop`, `customize.sh`, `post-fs-data.sh`, `META-INF/...` | small | Magisk plumbing. `update-binary` and `updater-script` are copied verbatim from b1051c-eink-compat. `customize.sh` aborts unless API 36 and arm64, then runs `set_perm_recursive $MODPATH/system 0 0 0755 0644`. `post-fs-data.sh` runs `resetprop ro.control_privapp_permissions log`. |

The four XMLs are subsets that `tools/trim_xml.py` generated from the MTG originals: whole elements for gms, gsf and vending kept, everything else removed. A full MTG copy would also have granted extra privileged permissions to the GSI's own `/system_ext/priv-app/EmergencyInfo` and added hidden-API exemptions for AOSP apps.

**Native libraries:** GmsCore and Phonesky ship their libs stored uncompressed (`extractNativeLibs=false`). All APKs are 4 KB and 16 KB page-aligned.
**uses-library:** none of the three APKs declares a *required* `uses-library`. Every entry is `uses-library-not-required`.

## Excluded (and why)

- **com.google.android.maps.jar + com.google.android.maps.xml:** **not shipped by MTG 16 at all.** They are missing from the release zip and from the `vendor_gapps` `baklava` source tree. Impact: Play hides the few legacy apps that hard-require the long-dead Maps v1 library.
- **GooglePermissionController / GoogleExtShared:** not in MTG 16. On Android 16 these are the mainline APEX modules (com.android.permission, extservices) that the GSI already ships. Not needed.
- **split-permissions XML:** none in MTG 16.
- **default-permissions-google.xml / default-permissions-mtg.xml:** no entries for gms, gsf or vending (they cover restore, assistant, talkback, clock, tachyon, photos and gearhead). Not needed.
- **sysconfig-mtg.xml** (GOOGLE_BUILD / GOOGLE_EXPERIENCE / EXCHANGE_6_2 / CONTEXTUAL_SEARCH features), **d2d_cable_migration_feature.xml**, **wellbeing.xml:** feature flags only, not needed for sign-in or downloads. Add them later only if Play filters a specific Google app.
- **gapps.rc** (sets `ro.com.google.gmsversion`, `ro.opa.eligible_device`): informational/Assistant only. A module's init .rc is also parsed too late to take effect.
- **gms_fsverity_cert.der:** an optional fs-verity trust cert. GMS works without it.
- **GmsSettingsOverlay** (Bluetooth Fast Pair slice), **GmsSettingsProviderOverlay** (default backup transport, applies only to a fresh settings DB), **GmsSetupWizardOverlay** (targets org.lineageos.setupwizard, which is absent).
- **SetupWizard, GooglePartnerSetup, GoogleRestore, Velvet/VelvetTitan, AndroidAutoStub, FamilyLink, Wellbeing, GoogleFeedback, Calendar/Contacts sync adapters, Exchange, Markup, SpeechServices, TalkBack, libjni_latinimegoogle.so, dialer.support jar/xml, privapp-permissions-mtg.xml (Velvet):** not requested and not required.

## Privileged-permission cross-check

`tools/privapp_check.py` compares each priv-app's requested permissions (aapt2, maxSdkVersion < 36 ignored) with the protection levels in the GSI's own `framework-res.apk`. The GSI image was read offline with debugfs. It then checks the result against the module's allowlist for the same partition.

| Package | Privileged platform perms requested | Allowlisted | Missing |
|---|---|---|---|
| com.google.android.gms | 115 | 115 | **0** |
| com.android.vending | 36 | 38 | **0** |
| com.google.android.gsf | 18 | 18 | **0** |

The subsets are identical to MTG's original blocks for these packages. The GSI's `system_ext/etc/build.prop` already sets `ro.control_privapp_permissions=disable`, but vendor props could override it. The `log` value forced by `post-fs-data.sh` still grants (it only logs). None of the GSI image's system/system_ext/product privapp XMLs has `deny-permission` entries (vendor XMLs were not checked), so switching from `disable` to `log` changes nothing for the GSI's own apps.

## Install (only after you approve it)

```sh
adb push module/b1051c-gapps-16.0.0-arm64-20260915_222150.zip /data/local/tmp/
adb shell su -c '/data/adb/magisk/magisk --install-module /data/local/tmp/b1051c-gapps-16.0.0-arm64-20260915_222150.zip'
adb reboot
```

The first boot is slow while GmsCore (175 MB) is dexopted, and the module uses about 270 MB in /data/adb/modules.

## Post-install

1. **Account:** there is no Google setup wizard. Add the account in Settings > Passwords & accounts > Add account > Google, or open Play Store. On an uncertified device, do step 2 first.
2. **Play Protect "device is not certified":** connect to Wi-Fi and wait a few minutes for GSF to check in. Then read the GSF Android ID (decimal) as root:
   ```sh
   adb shell
   su
   sqlite3 /data/data/com.google.android.gsf/databases/gservices.db "select * from main where name='android_id';"
   ```
   `sqlite3` exists in `/system/bin` of this TrebleDroid image, but it may be missing on other builds. Fallback: copy `gservices.db` and its `-wal` file with `adb exec-out su -c 'cat …'` and query them with the Mac's sqlite3.
   Register the number at https://www.google.com/android/uncertified, wait (minutes to hours), then reboot or clear Play Store data (`pm clear com.android.vending`).
   **Do not clear GSF data** afterwards: that creates a new Android ID, which must be registered again.
3. **Play Integrity:** this is a userdebug/test-keys build with an unlocked bootloader. Expect device/strong integrity to fail, so some apps (banking, DRM) may be hidden or refuse to run.
4. **Factory Reset Protection:** GmsOverlay sets `config_persistentDataPackageName=com.google.android.gms`. If a Google account and a secure lock screen are both set, GMS can arm FRP, and there is no Google setup wizard to clear it after a data wipe. **Remove the Google account before any factory reset or data wipe.**

## Disable / remove if boot fails

- Disable (works during boot on this userdebug build): `adb shell touch /data/adb/modules/b1051c-gapps/disable` (use `adb root` first, or `su -c` once Magisk is up), then `adb reboot`.
- Remove: `adb shell su -c 'touch /data/adb/modules/b1051c-gapps/remove'`, then `adb reboot`. Play Store updates of GMS, GSF or Play Store installed into /data/app stay behind as user apps. Uninstall them afterwards with `pm uninstall com.google.android.gms` (and likewise for com.android.vending and com.google.android.gsf if they were updated).

## Files

- `download/`: MTG zip, `.sha256sum`, `release.x509.pem` (untrusted inputs). `download/vendor_gapps_testkey/`: AOSP testkey from MTG's repo, used only for the negative signature test.
- `extracted/`: the unpacked MTG zip. Its installer was read as text and never run.
- `gsi-ref/`: framework-res.apk, build.prop and sysconfig files, read-only from `work/gsi/system-td-arm64-vanilla.img`.
- `tools/`: `trim_xml.py`, `privapp_check.py`, `signapk_footer.py` (run with `python3.12 -I`).
- `module/b1051c-gapps/` (tree) and `module/b1051c-gapps-16.0.0-arm64-20260915_222150.zip`.

## Installed 2026-10-18 04:26 (local)
- Installed via `/data/adb/magisk/magisk --install-module`, rebooted; boot OK (~30 s to boot_completed).
- GMS 25.15.32 crashed 4x at first boot (`IllegalArgumentException: Unexpected safety source: AdvancedProtection`,
  the GSI's Safety Center config lacks that source), then self-updated to 26.40.32 within 3 minutes; no crash since.
- GSF/Android ID (for google.com/android/uncertified, decimal): <GSF_ID>.
  On current GMS it lives in `/data/data/com.google.android.gms/databases/gservices.db` (table `main`, name `android_id`),
  not in the GSF database.
