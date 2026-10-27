# InKognito credits

InKognito ("Less bloat. More ink.") is a small layer by **matanmi12** that runs on top of other people's
work. Without them this ROM would not exist. Thank you.

License details for each part are in [LICENSES.md](LICENSES.md). The license texts are in [licenses/](licenses/).

## The base system

- **TrebleDroid**: the Android 16 GSI that InKognito is built on. Thanks to **phhusson** and all TrebleDroid
  contributors for years of work that lets generic Android run on devices like this one.
  - Releases: https://github.com/TrebleDroid/treble_experimentations/releases/tag/ci-20250617
  - Source: https://github.com/TrebleDroid (`treble_manifest`, `device_phh_treble`, `vendor_hardware_overlay`,
    `treble_app` and the patched AOSP repositories, branch `android-16.0`)
- **The Android Open Source Project (AOSP)**: https://source.android.com
- **Magisk** by **topjohnwu** and contributors: root, the module system, and the boot image patching that
  InKognito's first-boot setup relies on. https://github.com/topjohnwu/Magisk

## Apps

- **Lawnchair** by the Lawnchair team: the default home screen. https://github.com/LawnchairLauncher/lawnchair
- **KOReader** by the KOReader contributors: the e-book reader. https://github.com/koreader/koreader
- KOReader plugins:
  - **ZenOS** by **AnthonyGress** / **xZenLabs**: a clean, minimal KOReader interface.
    https://github.com/xZenLabs/zen-os (formerly `AnthonyGress/zen_ui.koplugin`)
  - **derainbowify** by **Euphoriyy**: removes Kaleido rainbow artifacts in KOReader.
    https://github.com/Euphoriyy/derainbowify.koplugin. Its author also credits Its-my-right
    (pocketbook_cfa_interference_breaker), axu2 and Kindle Comic Converter, Blendman974, and the PFFFT library by
    Julien Pommier.
  - **Bookshelf** by **AndyHazz** (if included in this build): a home screen for KOReader.
    https://github.com/AndyHazz/bookshelf.koplugin
- **MindTheGapps** by the MindTheGapps project: the packaging of Google's core apps used here.
  https://github.com/MindTheGapps. The Google apps themselves are made by Google LLC.

## E-ink display work

- **awong-dev**, [hibreak-pro-color](https://github.com/awong-dev/hibreak-pro-color): the HWC shim
  (`tools/hwcshim`) that makes the Bigme e-ink display work under a GSI. InKognito's display shim started as a
  port of it to the B1051C Pro. The original design and code are its author's.
- **Bigme** made the B1051C Pro and its stock firmware. This release reuses parts of that firmware (the stock
  kernel, the boot logo partition, the color lookup tables, the stock icons). See LICENSES.md.

## Fonts and icons

- **Rubik** by The Rubik Project Authors (https://github.com/googlefonts/rubik): system font and UI font.
- **Lilita One** by Juan Montoreano (https://github.com/google/fonts/tree/main/ofl/lilitaone): display font in
  E-Ink Control.
- **Material Design icons** by Google (https://github.com/google/material-design-icons): Quick Settings tile
  glyphs in E-Ink Control.
- Fonts that ship inside ZenOS: **Readerly** and **Libron** by Nico Verbruggen
  (https://github.com/nicoverbruggen/readerly, https://github.com/nicoverbruggen/libron; based on Newsreader by
  the Newsreader Project Authors), **Hyperreadable** by Mad Simple (https://github.com/MadSimple/hyperreadable),
  and **Symbols Nerd Font** (https://github.com/ryanoasis/nerd-fonts).
- Fonts that ship inside Bookshelf: Caveat, Inter and Roboto Condensed (their licenses come with the plugin).

## Guides and tools

- **Vasu's Journal** (vbh.ai), the bootloader unlock and root guide for the HiBreak Pro, whose steps also apply to
  the B1051C Pro: https://vbh.ai/unlocking-the-bootloader-and-rooting-the-hibreak-pro/
- **dbenjaminb**, [BigmeB1051CProPatcher](https://github.com/dbenjaminb/BigmeB1051CProPatcher): confirmed that the
  same unlock steps work on the B1051C Pro.
- **bkerler**, [mtkclient](https://github.com/bkerler/mtkclient): full partition backups before flashing. We
  strongly recommend it. It is not included in this release.
- Used while building and checking the release (not included): the Android NDK and SDK build tools, AOSP `avbtool`,
  Magisk's `magiskboot`, e2fsprogs (`debugfs`, `e2fsck`), Pillow, and
  [claude-android-audit-skill](https://github.com/FelixGraeber/claude-android-audit-skill) by Felix Graeber
  (review checklists).

## The HiBreak and Bigme community

Thanks to everyone who shared notes, dumps, guides and findings about Bigme devices on XDA, GitHub and elsewhere.

## Trademarks and affiliation

InKognito is an independent community project. It is not affiliated with, endorsed by, or supported by Bigme,
Google, MediaTek, E Ink, TrebleDroid, Magisk, Lawnchair, KOReader or MindTheGapps. Android, Google, Google Play and
other names and logos are trademarks of their owners. They are used here only to say what the software is and what
it works with.
