# Offline split-SELinux compile test: TrebleDroid A16 GSI on the B1051C Pro's stock A12 vendor (31.0)

Date: 2026-10-17. Everything ran on the Mac. No device was touched, and no adb, fastboot or mtkclient was used. Image files were read with debugfs `dump` only, never mounted.
Scratch dir (SCR): `/private/tmp/claude-501/-Users-owner-Desktop-Bigme/25eca8b2-1aea-45dc-8b97-e26ee6409819/scratchpad/sepolicy-test/`

## Result

| Combination | secilc 3.8.1 (upstream) | secilc 3.11 (upstream) | secilc AOSP android-16.0.0_r1 |
|---|---|---|---|
| STOCK control, -c 30 | exit 0 | exit 0 | exit 0 |
| STOCK control, -c 31 | exit 0 | exit 0 | exit 0 |
| **GSI, -c 30 (what init uses)** | **exit 0** | **exit 0** | **exit 0** |
| GSI, -c 31 | exit 0 | exit 0 | exit 0 |

No compile ran clean of errors or warnings: secilc printed nothing at all. The GSI combination compiles.

**Control validation.** I decompiled the AOSP-secilc stock -c 30 output to CIL and sorted it. It is identical to the decompiled, sorted vendor `precompiled_sepolicy`, which is the policy the device boots with today. The sorted diff is 0 lines, with 67,177 lines on each side. The headers also match: version 30, config 0xc0000001. That means the file set, the order and the flags reproduce the device's real policy. The byte size differs by 1,164 bytes, which comes only from encoding and ordering.

## Tools and sources
- Host: macOS arm64, Apple clang 21.0.0, GNU make 3.81, flex 2.6.4, e2fsprogs debugfs 1.47.4.
- libsepol-3.8.1.tar.gz `0e78705305f955abd4c0654d37a5477ee26349ab74db9e2b03a7868897ae1ddf`
- secilc-3.8.1.tar.gz `3db2974dd9a3c8403ada0392deff267b0398a74b4e7a0b051af76457270848d1`
- libsepol-3.11.tar.gz `79f3d2c88f44b7eb5cf54d9792e03232297e17f97a179163f2750099a00f164d`
- secilc-3.11.tar.gz `1fb7de005d35ae0ac6ff8f8fc955d08a15b5c1a67e961b97ef987576af0db11a`
  - All four match the SHA256 list on the SELinuxProject wiki Releases page. The 3.11 hashes also match GitHub's asset digests.
  - The GPG `.asc` signatures were downloaded but **not verified**, because gpg is not installed.
- AOSP `platform/external/selinux` at tag android-16.0.0_r1 is commit `89fe589e4b91ea164f01788724d7e3dd73949a44` (upstream 3.7 plus Android patches). I downloaded it as gitiles `+archive` tarballs:
  - libsepol.tar.gz `508aebde6b6e9d511668e24fa7e7adf3df5ed14d35d960bc2626318ef7ecffe0`
  - secilc.tar.gz `bbdcb326a1a42517a1af36d0b34270922f6cbbb0d993ecbb99618d84f331b5c7`
  - Gitiles publishes no checksum, so I can only pin the commit. This is the most faithful build: the Android patch always sets the ANDROID_NETLINK_ROUTE/GETNEIGH config bits, as the device's precompiled policy does.
- Init behaviour came from AOSP `system/core` at android-16.0.0_r1, commit `68be0c2c0006a0740d0b1809abe4717308f90d15`:
  - `init/selinux.cpp` (sha256 `6f445ea9…6b76`)
  - `init/Android.bp`, which has `-DSEPOLICY_VERSION=30`. Init therefore always passes `-c 30` and never detects the kernel's version.
  - `system/sepolicy compat/libgenfslabelsversion/src/lib.rs`
- The GSI's own `/system/bin/init` (sha256 `9f23f00b…8f53`) contains the same policy path strings as that source. The GSI's `/system/bin/secilc` (`4d36fea9…42bc`) is an arm64 Android binary and cannot run on the Mac.
- Built secilc binaries (static libsepol; `otool -L` shows only libSystem):
  - 3.8.1 `28b250ee…de5a`
  - 3.11 `ec81e2ae…664a`
  - AOSP16 `1dba10f5…f154`
  - The upstream sample `test/policy.cil` compiles with both upstream builds.

**macOS build changes.** I made no source edits. I only overrode flags and staged headers:
1. `make -C libsepol/src libsepol.a CFLAGS="-Wall -W -Wundef -Wshadow -O2"`. This drops `-Werror` and `-fno-semantic-interposition`, which clang rejects as an unused argument under -Werror. Building only the `.a` target skips the shared-library link step (`-soname`/`--version-script`). The `__APPLE__` endian path in `private.h` worked unchanged.
2. I copied `libsepol/include/sepol` and `libsepol/cil/include/cil` into `inc/sepol/{…,cil}`, because secilc includes `<sepol/cil/cil.h>`, which normally comes from `make install`.
3. `make -C secilc secilc CFLAGS="-Wall -W -O2 -I<inc>" LDFLAGS="-L<libsepol/src>"`. Only `libsepol.a` exists there, so `-lsepol` links statically.

## How Android 16 init chooses its inputs on this device
- **Precompiled policy is skipped.** The GSI's `plat_sepolicy_and_mapping.sha256` is `8f410f8f…d216`. The vendor's `precompiled_sepolicy.plat_sepolicy_and_mapping.sha256` is `13c3f345…173c`, which matches the stock system. Because they differ, init compiles the policy on the device.
- **Vendor version.** `/vendor/etc/selinux/plat_sepolicy_vers.txt` contains `31.0`. Vendor props: `ro.vndk.version=31`, `ro.board.first_api_level=30`.
- **system_ext and product come from the GSI.** The GSI's `/system_ext` and `/product` are symlinks into `/system`. Its `/system/system_ext/etc/init/config/skip_mount.cfg` skips mounting `/system_ext` and `/product`, so init uses the GSI's own copies, not stock `system_ext_b`/`product_b`.
- **No genfs file.** The vendor has no `/vendor/etc/selinux/genfs_labels_version.txt`, so the default is 202404. `plat_sepolicy_genfs_202404.cil` does not exist, so init adds none. The GSI ships `_202504.cil` and `_202604.cil` (identical; they label sysfs_udc and sysfs_mem_sleep), but neither is used.
- **No odm policy.** There is no `odm_sepolicy.cil` anywhere in the vendor image (`vendor/odm/etc` has no `selinux` dir), and super has no odm partition.
- **No userdebug policy.** There is no `userdebug_plat_sepolicy.cil` in system_ext. The `/debug_ramdisk` one is used only with INIT_FORCE_DEBUGGABLE on an unlocked device, which I did not replicate.
- **No apex policy.** There is no apex sepolicy in this path in A16 `selinux.cpp`.
- **Other .cil files present but not used.** GSI mapping files for 28.0–34.0, 202404 and 202504. The vendor `/etc/selinux` has only the two .cil files listed below.

## Inputs (sha256 of the extracted files, in `SCR/policy/`)

**GSI**: `system-td-arm64-vanilla.img` (android_16.0.0_r1, ci-20250617)

| File | Size (bytes) | sha256 |
|---|---|---|
| `/system/etc/selinux/plat_sepolicy.cil` | 2,434,788 | `127837c57187ca54ae909d26e3408069331dcee388ac42f93cbe6cc1278f8b46` |
| `/system/etc/selinux/mapping/31.0.cil` | 142,085 | `00302fa35d30571876193f10fe059875290badc49b29e33b6aeea664cb9a5d93` |
| `/system/etc/selinux/mapping/31.0.compat.cil` | 156 (comments only) | `adddc30714cef2ec2beb4eecdc24df86acb145e390a708bd2ae5ac41b6fd7630` |
| `/system/system_ext/etc/selinux/system_ext_sepolicy.cil` | 91,256 | `6e79469b50ea3204f6962cc18a32a99ee67dc714fa4c849fb6af5ce8377d9e2d` |
| `/system/system_ext/etc/selinux/mapping/31.0.cil` | 0 | `e3b0c442…b855` |
| `/system/system_ext/etc/selinux/mapping/31.0.compat.cil` | 0 | `e3b0c442…b855` |
| `/system/product/etc/selinux/product_sepolicy.cil` | 1 (a newline) | `01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b` |
| `/system/product/etc/selinux/mapping/31.0.cil` | 0 | `e3b0c442…b855` |

**STOCK**

| File | Size (bytes) | sha256 |
|---|---|---|
| `system_b` `/system/etc/selinux/plat_sepolicy.cil` (A14 UP1A.231005.007) | 2,134,509 | `97237f43dd24ae68f23ac6100de878f0282591a010f74b4afd453b4005688c83` |
| `system_b` `mapping/31.0.cil` | — | `b3427009c183747eef6ccdf4c6f2590d9503003d72cfc3c00f4754cdfac0d6ac` |
| `system_b` `mapping/31.0.compat.cil` | — | `adddc307…7630` |
| `system_ext_b` `/etc/selinux/system_ext_sepolicy.cil` | — | `7535c56967384f16aac2ddecdcb4de4d1e71bee6eddedb449f36ad6698a6e3bd` |
| `system_ext_b` `mapping/31.0.cil` | — | `216c2fd214509e568dc53463e238ca217457aff7da8d177ca9bdab53dd184b07` |
| `system_ext_b` `mapping/31.0.compat.cil` | — | `1e29d01dcf806fcdc88650b42c0355f3ad6b6f3676e0cfba5bd6ab5a91dca1a9` |

`product_b` has no `etc/selinux` at all.

**VENDOR**: `vendor_b.img`, used for both combinations

| File | sha256 |
|---|---|
| `/etc/selinux/plat_pub_versioned.cil` | `c1637652a06ec702a6ec7c9c23704ee9eb5e9c0dd6be3ee434d2d29759683a2a` |
| `/etc/selinux/vendor_sepolicy.cil` | `475d86777c2d92424f16e417b295d0bc36758c3e9fd3300cc10c4b6ac452ca0b` |
| `/etc/selinux/precompiled_sepolicy` (control reference) | `89f07ffb75185f1b45c5716637a30b1a3ec0fd33fbe81d3a07b0ac57f14c8878` |

## Commands
The commands use the order from `selinux.cpp`. The script is `SCR/scripts/compile.sh`, and the literal command lines are in `SCR/build/out-*/log.*.cmd`. `SECILC` is any of the three builds.

GSI:
```
$SECILC gsi/plat_sepolicy.cil -m -M true -G -N -c 30 gsi/plat_mapping_31.0.cil -o sepolicy.gsi.pv30 -f /dev/null \
  gsi/plat_mapping_31.0.compat.cil gsi/system_ext_sepolicy.cil gsi/system_ext_mapping_31.0.cil \
  gsi/system_ext_mapping_31.0.compat.cil gsi/product_sepolicy.cil gsi/product_mapping_31.0.cil \
  vendor/plat_pub_versioned.cil vendor/vendor_sepolicy.cil
```
Stock control: the same, using `stock/plat_sepolicy.cil`, `stock/plat_mapping_31.0{,.compat}.cil`, `stock/system_ext_sepolicy.cil` and `stock/system_ext_mapping_31.0{,.compat}.cil`. There is no product file.

The output files are identical from 3.8.1 and 3.11. AOSP16 output differs only in the config bits.

| Output (AOSP16 build) | Size (bytes) | sha256 |
|---|---|---|
| GSI pv30 | 1,190,427 | `03011aad8e0c9780b41710f49e5d567f468601dd0379715ca16eeadf1e9e1ff6` |
| GSI pv31 | — | `454d3849812a93d926040a85f936c50ab43d7af21dcea56c469e82b25821e68d` |
| stock pv30 | 1,146,110 | `cd6bbb2fa1456d1b20e006215fc2e8c050366060cf25c096be4cb20febc3de1b` |

On the Mac the GSI compile took 0.23 s with 89 MB max RSS.

What the GSI policy (pv30) contains:
- MLS, handleunknown=deny.
- The same 4 policycaps as stock.
- 3 classes stock lacks: `mctp_socket`, `tee_service`, `user_namespace`.

## Extra diagnostics (none of them block the compile)
1. **Neverallow checking (no `-N`).**
   - Both combinations fail: stock with 176 violations, GSI with 1,718. Most of the GSI violations come from TrebleDroid's system_ext rules (`phhsu_daemon`) and from the vendor's own rules.
   - This does not matter on the device, because init always passes `-N`. It does show that `-N` is required even for stock.
2. **Hypothetical: GSI plat + stock `system_ext_b`.**
   - This combination **fails**: `Failed to resolve genfscon statement at system_ext_sepolicy.cil:6` for `(genfscon proc "/wacom" (u object_r xrz_proc_file …))`.
   - `xrz_proc_file` is a Bigme type defined in the stock A14 *plat* policy, and it is absent from the GSI.
   - This matters only if stock system_ext were mounted over `/system_ext`. The GSI's skip_mount.cfg prevents that.
   - The fix would be to make sure the GSI's own system_ext is used, or to add the xrz types to the GSI's policy.
3. **31.0 mapping coverage.**
   - 46 versioned types that `vendor_sepolicy.cil` references are mapped on stock but unmapped on the GSI.
   - Most are MTK system-side daemons that don't exist on the GSI anyway (mdlogger, aee_core_forwarder, em_svr, …).
   - The Bigme-specific ones are `vendor_xrz_prop_31_0`, `xrz_debugfs_31_0` and `xrz_system_file_31_0`.
   - The rules that use them compile to nothing. This can cause denials at runtime (for example, `vendor.xrz.*` properties fall back to the generic vendor property label), but it does not cause a compile failure.
4. **Contexts check.**
   - Every type named in the vendor `*_contexts` files, and in the GSI's plat, system_ext and product contexts files, exists in the compiled GSI policy (0 missing).
   - So invalid contexts should not make init abort with "Failed to initialize property area". Bigme's xrz property and file contexts live only in the stock system and system_ext contexts files, so they are absent on the GSI.

## Conclusion
I am highly confident that Android 16 init on this TrebleDroid GSI will compile the split policy on the device. With the exact inputs and arguments init would use, three independent secilc builds all compile it with exit 0: upstream 3.8.1, upstream 3.11 and the AOSP android-16.0.0_r1 source. Those inputs are the GSI's plat, system_ext and product policy, the 31.0 mapping and compat files, the vendor's `plat_pub_versioned.cil` and `vendor_sepolicy.cil`, no genfs or odm file, and `-c 30`. The stock control also compiles, and its output is semantically identical to the vendor's `precompiled_sepolicy`, which validates the toolchain and the argument list. The remaining caveats:
- I compiled with a macOS build of the AOSP source, not the GSI's arm64 `/system/bin/secilc`. TrebleDroid patches to external/selinux, if any, were not checked.
- Loading the policy into the 4.19 kernel (`security_load_policy`) was not tested. Policy version 30 is within 4.19's maximum of 31.
- The result depends on skip_mount.cfg working as intended. If stock system_ext_b got mounted at `/system_ext`, the compile **would fail** (diagnostic 2).
- The debuggable or userdebug policy path was not replicated.
- Compiling successfully is necessary but not sufficient for a full boot. Runtime denials from the unmapped MTK and xrz types (diagnostic 3), and any later-stage boot problems, are outside this test.
