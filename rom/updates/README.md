# Module updates through the Magisk app

Since beta 1 the three code modules of InKognito carry an `updateJson` line in their `module.prop`:

| Module | What it is |
|---|---|
| `b1051c-eink-compat` | the display layer (HWC shim, input helper, props, SELinux rules) |
| `b1051c-launcher3` | the patched Launcher3 (taskbar, Recents) |
| `b1051c-heliboard` | the HeliBoard keyboard as a system app + its key look |

The Magisk app reads `<id>.json` from this folder (branch `main`). When its `versionCode` is higher than the installed
one, Magisk offers the update and downloads `zipUrl`, a file attached to a GitHub release of this repository.
`<id>.md` is the changelog Magisk shows. The other modules (boot animation, fonts, icons, Google apps) only change
with a full release and have no update link.

To publish a module update: attach the new zip to a release, then raise `version`, `versionCode` and `zipUrl` in the
module's json here and add a section to its md. A json that names the installed version offers nothing.

Status: the links are in place since beta 1; a module update through the Magisk app has not been run on a tablet yet.
