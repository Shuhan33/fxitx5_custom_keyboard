/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.common.ipc.IClipboardEntryTransformer
import org.fcitx.fcitx5.android.common.ipc.IFcitxRemoteService
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.core.reloadQuickPhrase
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.HostClipboardFilter
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.desc
import org.fcitx.fcitx5.android.utils.descEquals
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

class FcitxRemoteService : Service() {

    companion object {
        private const val BUILTIN_ALLOWED_PLUGIN_PREFIX = "org.fcitx.fcitx5.android"
    }

    private val clipboardTransformerLock = Mutex()

    private val scope = MainScope() + CoroutineName("FcitxRemoteService")

    private val clipboardTransformers = CopyOnWriteArrayList<IClipboardEntryTransformer>()
    private data class TransformerRegistration(
        val ownerUid: Int,
        val deathRecipient: IBinder.DeathRecipient
    )
    private val clipboardTransformerRegistrations =
        ConcurrentHashMap<IBinder, TransformerRegistration>()

    private fun orderedClipboardTransformers() =
        clipboardTransformers.toList()
            .sortedWith(compareByDescending<IClipboardEntryTransformer> { it.priority }.thenBy { it.desc })

    private fun transformClipboard(source: String): String {
        var result = HostClipboardFilter.transform(source)
        orderedClipboardTransformers().forEach {
            try {
                result = it.transform(result)!!
            } catch (e: Exception) {
                Timber.w("Exception while calling clipboard transformer '${it.desc}'")
                Timber.w(e)
            }
        }
        return result
    }

    private suspend fun updateClipboardManager() = clipboardTransformerLock.withLock {
        ClipboardManager.transformer =
            if (clipboardTransformers.isEmpty() && !HostClipboardFilter.isEnabled()) null else ::transformClipboard
        val transformers = buildList {
            HostClipboardFilter.description()?.let(::add)
            addAll(orderedClipboardTransformers().map { it.desc })
        }
        Timber.d("All clipboard transformers: ${transformers.joinToString()}")
    }

    private data class AuthorizedCaller(val uid: Int, val packageName: String)

    /**
     * Check if the calling package is allowed based on IPC compatibility mode
     */
    private fun packageMatchesPrefix(packageName: String, prefix: String): Boolean {
        val normalized = prefix.trim().removeSuffix(".")
        if (normalized.isEmpty()) return false
        return packageName == normalized || packageName.startsWith("$normalized.")
    }

    private fun isCallerAllowed(callingPackage: String?): Boolean {
        if (callingPackage == null) return false

        // Always allow self
        if (callingPackage == packageName) return true

        if (AppPrefs.getInstance().advanced.allowOriginalPlugins.getValue()) {
            // When enabled: always allow built-in prefix + user-defined extra prefixes
            val prefixes = AppPrefs.getInstance().advanced.allowedPluginPrefixes.getValue() +
                    BUILTIN_ALLOWED_PLUGIN_PREFIX
            return prefixes.any { packageMatchesPrefix(callingPackage, it) }
        } else {
            // When disabled: only allow same-signed builds (self-built)
            return try {
                val mainSig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    ).signingInfo?.apkContentsSigners.orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_SIGNATURES
                    ).signatures.orEmpty()
                }
                val callerSig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageManager.getPackageInfo(
                        callingPackage,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    ).signingInfo?.apkContentsSigners.orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(
                        callingPackage,
                        PackageManager.GET_SIGNATURES
                    ).signatures.orEmpty()
                }
                mainSig.isNotEmpty() && callerSig.isNotEmpty() && mainSig.contentEquals(callerSig)
            } catch (e: Exception) {
                Timber.w(e)
                false
            }
        }
    }

    /**
     * Validate the UID for the current Binder transaction. This must run synchronously,
     * before any coroutine or identity-clearing operation, because a later callback would
     * observe this service's UID instead of the original client.
     */
    private fun authorizeCaller(operation: String): AuthorizedCaller {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return AuthorizedCaller(uid, packageName)

        val packageNames = packageManager.getPackagesForUid(uid).orEmpty()
        val allowedPackage = packageNames.firstOrNull(::isCallerAllowed)
        if (allowedPackage != null) return AuthorizedCaller(uid, allowedPackage)

        Timber.w(
            "Rejected IPC operation=$operation uid=$uid packages=${packageNames.joinToString()} " +
                "allowOriginalPlugins=${AppPrefs.getInstance().advanced.allowOriginalPlugins.getValue()}"
        )
        throw SecurityException("IPC compatibility mode does not allow access for UID $uid")
    }

    private fun removeClipboardEntryTransformer(
        transformer: IClipboardEntryTransformer,
        ownerUid: Int
    ): Boolean {
        val binderHandle = transformer.asBinder()
        val registration = clipboardTransformerRegistrations[binderHandle] ?: return false
        if (registration.ownerUid != ownerUid ||
            !clipboardTransformerRegistrations.remove(binderHandle, registration)
        ) return false
        runCatching { binderHandle.unlinkToDeath(registration.deathRecipient, 0) }
        return clipboardTransformers.removeAll { it.asBinder() == binderHandle }
    }

    private val binder = object : IFcitxRemoteService.Stub() {
        override fun getVersionName(): String {
            authorizeCaller("getVersionName")
            return Const.versionName
        }

        override fun getPid(): Int {
            authorizeCaller("getPid")
            return Process.myPid()
        }

        override fun getLoadedPlugins(): MutableMap<String, String> {
            authorizeCaller("getLoadedPlugins")
            return DataManager.getLoadedPlugins().map {
                it.packageName to it.versionName
            }.let { mutableMapOf<String, String>().apply { putAll(it) } }
        }

        override fun restartFcitx() {
            authorizeCaller("restartFcitx")
            FcitxDaemon.restartFcitx()
        }

        override fun registerClipboardEntryTransformer(transformer: IClipboardEntryTransformer) {
            val caller = authorizeCaller("registerClipboardEntryTransformer")
            Timber.d("registerClipboardEntryTransformer: ${transformer.desc} from ${caller.packageName}")
            if (transformer.description.isNullOrBlank()) {
                Timber.w("Cannot register ClipboardEntryTransformer of null or empty description")
                return
            }
            scope.launch {
                val binderHandle = transformer.asBinder()
                if (clipboardTransformerRegistrations.containsKey(binderHandle)) {
                    Timber.w("ClipboardEntryTransformer binder is already registered")
                    return@launch
                }
                if (clipboardTransformers.any { it.descEquals(transformer) }) {
                    Timber.w("ClipboardEntryTransformer description is already registered")
                    return@launch
                }
                val deathRecipient = IBinder.DeathRecipient {
                    scope.launch {
                        if (removeClipboardEntryTransformer(transformer, caller.uid)) {
                            updateClipboardManager()
                        }
                    }
                }
                val registration = TransformerRegistration(caller.uid, deathRecipient)
                clipboardTransformerRegistrations[binderHandle] = registration
                clipboardTransformers.add(transformer)
                val deathLink = runCatching {
                    binderHandle.linkToDeath(deathRecipient, 0)
                }
                if (deathLink.isFailure) {
                    removeClipboardEntryTransformer(transformer, caller.uid)
                    Timber.w(deathLink.exceptionOrNull(), "Clipboard transformer was already dead")
                    updateClipboardManager()
                    return@launch
                }
                clipboardTransformers.sortByDescending { it.priority }
                updateClipboardManager()
            }
        }

        override fun unregisterClipboardEntryTransformer(transformer: IClipboardEntryTransformer) {
            val caller = authorizeCaller("unregisterClipboardEntryTransformer")
            Timber.d("unregisterClipboardEntryTransformer: ${transformer.desc} from ${caller.packageName}")
            scope.launch {
                if (!removeClipboardEntryTransformer(transformer, caller.uid)) {
                    Timber.w("Rejected clipboard transformer removal: caller does not own ${transformer.desc}")
                    return@launch
                }
                updateClipboardManager()
            }
        }

        override fun importRemoteClipboardEntry(
            text: String,
            originalText: String,
            originalRootUri: String,
            type: String,
            timestamp: Long,
            sensitive: Boolean
        ) {
            authorizeCaller("importRemoteClipboardEntry")
            val filteredText = HostClipboardFilter.transform(text)
            runBlocking {
                ClipboardManager.importRemoteEntry(
                    text = filteredText,
                    originalText = when {
                        originalText.isNotEmpty() -> originalText
                        filteredText != text -> text
                        else -> ""
                    },
                    originalRootUri = originalRootUri,
                    type = type,
                    timestamp = timestamp,
                    sensitive = sensitive,
                    notifyListeners = false
                )
            }
        }

        override fun reloadPinyinDict() {
            authorizeCaller("reloadPinyinDict")
            FcitxDaemon.getFirstConnectionOrNull()?.runIfReady { reloadPinyinDict() }
        }

        override fun reloadQuickPhrase() {
            authorizeCaller("reloadQuickPhrase")
            FcitxDaemon.getFirstConnectionOrNull()?.runIfReady { reloadQuickPhrase() }
        }
    }

    override fun onCreate() {
        Timber.d("FcitxRemoteService onCreate")
        super.onCreate()
        runBlocking { updateClipboardManager() }
    }

    override fun onBind(intent: Intent): IBinder {
        // Service callbacks are delivered by the system and are not a reliable place to
        // identify the original client. Every Binder transaction is authorized above.
        Timber.d("FcitxRemoteService onBind action=${intent.action}")
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Timber.d("FcitxRemoteService onUnbind action=${intent.action}")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Timber.d("FcitxRemoteService onDestroy")
        scope.cancel()
        clipboardTransformerRegistrations.forEach { (binderHandle, registration) ->
            runCatching { binderHandle.unlinkToDeath(registration.deathRecipient, 0) }
        }
        clipboardTransformers.clear()
        clipboardTransformerRegistrations.clear()
        runBlocking { updateClipboardManager() }
    }
}
