# Android keyboard reliability, privacy, and resource audit

**Date:** 2026-11-12
**Scope:** Android IME lifecycle and view construction, font loading/cache invalidation, input and clipboard logging, exported IPC, backup rules, clipboard thumbnail allocation, bounded memory caches, and the available FxDebug build/test path.

## Summary

The supplied Samsung SM-G981W / Android 13 Duo trace records a 2,738 ms bind-time prewarm and a 1,513.3 ms first text-keyboard build, with the reported font-related phase at 1,374.8 ms (about 90.8%). That aggregate does not establish that all of the phase is file parsing. These are baseline timings supplied with the incident, not measurements from this checkout after the changes. Source inspection confirmed that font reads used a shared synchronized path, so background preloading could hold a lock needed by the UI thread. The fix moves font file and Typeface construction to a coalesced background worker and lets UI lookups read an immutable snapshot or a temporary fallback.

The trace then records `finishInputView`, hide/trim, and unbind events. Those framework lifecycle events do not prove that the IME caused Duo to dismiss the keyboard. The project’s `markInputRequested` point is `onStartInput`, not `onBindInput`; reported request-to-show timings therefore do not include the earlier bind-time prewarm. No post-fix device trace or PSS measurement was available.

## Findings and changes

### High — font loading could stall the UI thread

`DefaultFontProvider` now builds a generation-tagged immutable cache off the UI thread. UI resolution reads the current snapshot and can return a fallback before the custom font is ready. Cache invalidation advances the generation so stale worker results cannot replace newer configuration. `FontProviders` uses an atomic revision, clears cached fallback sizes when a font snapshot publishes, and guards fallback size writes against a concurrent revision change. Candidate and picker adapters independently compare revisions so one view consuming a refresh cannot leave another view permanently on fallback. A failed load clears its monitor state so a later load can retry. See `DefaultFontProvider.kt:20-220`, `FontProviders.kt:32-224`, `HorizontalCandidateViewAdapter.kt:27-34`, `PagingCandidateViewAdapter.kt:22-29`, and `PickerPagesAdapter.kt:84-85,308-315`.

The eager `onCreate` scheduling of the all-layout input-view prewarm was removed. Normal view construction remains in `onCreateInputView` and is now demand-driven (`FcitxInputMethodService.kt:622-665,1178-1189`; the unused helper remains at line 443). This removes unnecessary eager hierarchy work, but does not remove the work needed for the first requested layout or establish a measured end-to-end speedup.

### High — exported remote service methods lacked per-transaction caller checks

The manifest exports `.FcitxRemoteService` without a manifest-level permission (`AndroidManifest.xml:277-282`). `authorizeCaller` now captures and validates the actual Binder transaction UID synchronously, and all nine AIDL methods call it before scheduling work or changing state (`FcitxRemoteService.kt:145-159,174-287`). Transformer registrations are serialized with duplicate checks inside the service coroutine; unregister verifies the owner UID. Binder death recipients are removed and unlinked on unregister, death, and service destruction (`:160-171,196-245,305-313`). Existing opt-in package-prefix compatibility behavior is preserved; package-prefix matching is not certificate authentication.

The IME itself remains exported only with Android’s `BIND_INPUT_METHOD` protection (`AndroidManifest.xml:262-275`). The floating-voice receiver is registered with the app’s signature-level `.permission.PLUGIN` permission and unregistered with the service lifecycle (`FcitxInputMethodService.kt:650-664`).

### Medium — input and endpoint details were written to logs

Voice partial and commit logs, composing-text logs, raw virtual/Fcitx key-event dumps, and key code / scan code / Unicode logs now retain only length, action, or event category (`FcitxInputMethodService.kt:365,372,701,754,991,1325,1342,1523,1957`; `VoiceInputProviderManager.kt:382-394`). Clipboard-sync logs no longer include queued clipboard text or configured server URLs (`MainService.kt:536,1734,1737,1743,2149`; `SyncClient.kt:173,183`). Clipboard image staging failure logs omit the URI (`ClipboardManager.kt:519`).

Existing input safeguards remain in place: `IME_FLAG_NO_PERSONALIZED_LEARNING` maps to `Sensitive`, and password detection evaluates class together with variation (`CapabilityFlag.kt:99-110,146-153,181-188`). In particular, Duo’s `0x80013` has `TYPE_CLASS_PHONE`; the app retains its explicit phone-plus-number-password compatibility heuristic. An IME cannot reliably infer every OTP field from `inputType` alone.

### Medium — clipboard history could be included in backups

The clipboard database `clbdb` and its WAL/SHM/journal files are excluded from both credential-encrypted and device-encrypted database domains in legacy backup rules, cloud backup, and device transfer (`full_backup_content.xml:2-11`; `data_extraction_rules.xml:3-12,26-35`). The application’s `allowBackup` remains `true` to preserve other settings (`AndroidManifest.xml:68-73`). This change does not delete existing clipboard history or prevent local storage. It does not establish that all preferences, sync credentials, word lists, or other app data are excluded from backup.

### Low — extreme image aspect ratios could retain oversized clipboard previews

`calculateInSampleSize` now keeps sampling while either working dimension remains above the target, keeps dimensions used in the sampling calculation at least one, and guards sample-factor overflow (`ClipboardImageLoader.kt:96-122`). The added test uses a 12,000 × 50 source and a 192 × 192 target to check that the long axis is sampled (`ClipboardImageLoaderTest.kt:9-18`).

The existing caches are bounded: keyboard row reuse is capped at 12 entries (`BaseKeyboard.kt:192-200`), and clipboard thumbnails use an 8 MiB byte-counted LRU (`ClipboardAdapter.kt:41-47`). `onTrimMemory` trims clipboard thumbnails; font caches are retained through ordinary trims and released only on `onLowMemory` (`FcitxApplication.kt:265-280`). Keyboard layout instances are cached lazily. There is no device PSS or critical-memory benchmark here, so retained inactive row/layout memory remains unquantified.

## Existing network and dependency boundaries

Core keyboard input does not require network access, but this app is not globally offline: the manifest declares `INTERNET` and permits cleartext traffic, and the network security config permits cleartext (`AndroidManifest.xml:14-17,75-80`; `network_security_config.xml:1-4`). Quick clipboard sync defaults off (`MainService.kt:63-67`); voice input and network-backed features remain available when configured.

The one resolved FxDebug runtime graph reports `okhttp:4.12.0` with `okio:3.6.0`, and `commons-compress:1.27.1` with `commons-lang3:3.16.0` (`build/keyboard-audit-dependencies-fxdebug-runtime.log`). Okio 3.6.0 is above the upstream fixed threshold for the older CVE-2023-3635. OkHttp 4.12.1 adds an eMudhra intermediate-certificate block; this audit did not establish that the reported device or its trust store is affected. The resolved `commons-lang3:3.16.0` is within the Apache advisory range fixed in 3.18.0; app reachability of the affected `ClassUtils.getClass` path was not established, and Commons Compress 1.28 release notes state that Compress itself is not affected. Consider a bounded dependency patch and reachability assessment separately; no dependency upgrade was made here.

- [Apache Commons Lang advisory](https://lists.apache.org/thread/bgv0lpswokgol11tloxnjfzdl7yrc1g1)
- [Commons Compress release notes](https://github.com/apache/commons-compress/blob/master/RELEASE-NOTES.txt)
- [OkHttp 4.x change log](https://lysine.dev/okhttp/changelogs/changelog_4x/)

## Verification

The final `:app:testFxDebugUnitTest` task compiled the final FxDebug app Kotlin and Java sources and passed **13 tests, 0 failures, 0 errors, 0 skipped**: 7 Romaji composer, 2 string escaping, 3 theme serialization, and 1 clipboard-image sampling test. The previously failing theme assertion was stale: it labeled a 2.0 fixture “no migration” even though the current format is 2.1 and the 2.1 migration adds candidate color fields. The test now expects 2.0 → 2.1 migration and checks the mapped values; a current-version 2.1 round trip separately checks that migration does not occur. No theme product behavior changed.

The successful full-suite command was:

```powershell
$env:ANDROID_USER_HOME='D:\Lei\Documents\android_keyboard\build\android-user-home'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_RO_DEP_CACHE='C:\Users\lei\.gradle\caches'
& 'D:\Lei\Documents\android_keyboard\build\gradle-audit-runtime\bin\gradle.bat' `
  -g 'D:\Lei\Documents\android_keyboard\.gradle' --no-daemon `
  '-Pkotlin.compiler.execution.strategy=in-process' :app:testFxDebugUnitTest `
  --stacktrace --console=plain
```

The console log and per-class XML results are saved in `build/keyboard-audit-results/final-suite/`. The separate successful font/app compile is recorded in `build/keyboard-audit-compile-fxdebug-online-escalated.log`. The one runtime dependency graph is in `build/keyboard-audit-dependencies-fxdebug-runtime.log`.

A full `:app:assembleFxDebug` was attempted but did not produce an APK. It stopped at `:lib:fcitx5:configureCMakeDebug[arm64-v8a]`: the project requires CMake 3.31.6, while the configured SDK/PATH only supplied 3.26.4. The configured Android SDK has no `sdkmanager`/`cmdline-tools`, so the exact required CMake package could not be installed through the available SDK manager. No toolchain version or project CMake requirement was changed, and no APK was installed or published. See `build/keyboard-audit-assemble-fxdebug.log`.

## Remaining checks

- Re-run a cold and warm Duo OTP entry session on the reported Samsung/Android 13 device, verify the first key input and font refresh, and capture lifecycle timings. This audit did not reproduce the disappearance.
- Measure process PSS and retained keyboard hierarchy after layout switching and memory pressure; no post-change memory or latency figure is available.
- Review backup coverage for preferences and other private data beyond `clbdb`; local clipboard retention remains unchanged.
- Review remaining diagnostic metadata paths (for example, remote content URIs) before treating all clipboard-related logs as content-free.
- Resolve the CMake 3.31.6 package/tooling availability before claiming a packaged debug build.
