# Third-party notices

This repository is a customized distribution of Fcitx5 for Android. Copyright
in upstream files remains with the original authors. Shuhan Lei's modifications
do not replace, remove, or claim ownership of those upstream contributions.

## Main upstream projects

- Fcitx5 for Android — `LGPL-2.1-or-later` and per-file licenses
  - https://github.com/fcitx5-android/fcitx5-android
- fxliang/fcitx5-android — upstream fork and implementation reference; per-file licenses
  - https://github.com/fxliang/fcitx5-android/tree/fx
- Fcitx5 — `LGPL-2.1-or-later` and per-file licenses
  - https://github.com/fcitx/fcitx5
- libime — `LGPL-2.1-or-later`
  - https://github.com/fcitx/libime
- fcitx5-chinese-addons — mixed `LGPL-2.1-or-later` / `GPL-2.0-or-later`
  - https://github.com/fcitx/fcitx5-chinese-addons

The complete dependency and native-component inventory is generated into the
application by AboutLibraries from Gradle metadata and `app/licenses`. Each Git
submodule retains its own copyright notices and license files. Those component
licenses continue to govern their respective files; the root license does not
replace them.

## slei modifications

Original slei code and modifications are released under
`LGPL-2.1-or-later` unless a file states otherwise. Native patches retain the
original copyright lines and add a dated modification notice for Shuhan Lei.

The distributed APK includes the complete LGPL 2.1 text at
`assets/licenses/LGPL-2.1.txt`, and the application can display it without a
network connection.

## Xime

Xime is a GPLv3 project and is not copied, linked, bundled, or redistributed by
this repository. General input-method architecture and performance ideas may be
studied and independently reimplemented; no Xime source expression is included.

Xime source and license: https://github.com/ximeiorg/Xime
