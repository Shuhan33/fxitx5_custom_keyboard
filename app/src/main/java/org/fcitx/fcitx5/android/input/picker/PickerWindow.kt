/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Transition
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

class PickerWindow(
    override val key: Key,
    private val data: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerPageUi.Density,
    private val switchKey: KeyDef,
    private val popupPreview: Boolean = true,
    private val followKeyBorder: Boolean = true,
    private val policy: PickerPolicy = DefaultPickerPolicy()
) : InputWindow.ExtendedInputWindow<PickerWindow>(), EssentialWindow {

    enum class Key : EssentialWindow.Key {
        Symbol,
        Emoji,
        Emoticon
    }

    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    private val keyBorder by ThemeManager.prefs.keyBorder

    private lateinit var pickerLayout: PickerLayout
    private lateinit var pickerPagesAdapter: PickerPagesAdapter

    private val iconThemeListener = IconThemeManager.OnIconThemeChangeListener {
        returnKeyDrawable.onIconThemeChanged()
        refreshIconTheme()
    }

    private fun refreshIconTheme() {
        if (!::pickerLayout.isInitialized) return
        pickerLayout.embeddedKeyboard.refreshIconTheme()
        pickerLayout.backspace.reapplyIconThemeOverride()
    }

    override fun enterAnimation(lastWindow: InputWindow): Transition? = null

    override fun exitAnimation(nextWindow: InputWindow): Transition? = null

    private val keyActionListener = KeyActionListener { it, source ->
        when (it) {
            is KeyAction.LayoutSwitchAction -> {
                (windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow)
                    .switchLayout(it.act, fromUserKey = true)
                ContextCompat.getMainExecutor(context).execute {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            is KeyAction.FcitxKeyAction -> {
                commonKeyActionListener.listener.onKeyAction(KeyAction.CommitAction(it.act), source)
            }

            else -> {
                if (it is KeyAction.CommitAction) {
                    pickerPagesAdapter.insertRecent(it.text)
                }
                commonKeyActionListener.listener.onKeyAction(it, source)
            }
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        PopupActionListener {
            when (it) {
                is PopupAction.PreviewAction -> {
                    if (!popupPreview) return@PopupActionListener
                }
                is PopupAction.ShowKeyboardAction -> {
                    pickerLayout.allowStripScroll = false
                    pickerLayout.strip.stopScroll()
                }
                is PopupAction.DismissAction -> {
                    pickerLayout.allowStripScroll = true
                }
                else -> {}
            }
            popup.listener.onPopupAction(it)
        }
    }

    override fun onCreateView() = PickerLayout(context, theme, switchKey).apply {
        pickerLayout = this
        val bordered = followKeyBorder && keyBorder
        pickerPagesAdapter = PickerPagesAdapter(
            theme, keyActionListener, popupActionListener, data,
            density, key.name, bordered, policy
        )
        backspace.repeatEnabled = true
        tabsUi.apply {
            setTabs(pickerPagesAdapter.getCategoryList())
            setOnTabClickListener { i ->
                pickerPagesAdapter.showCategory(i)
                strip.scrollToPosition(0)
                activateTab(i)
            }
            activateTab(1)
        }
        strip.apply {
            layoutManager = GridLayoutManager(context, density.rowCount, RecyclerView.HORIZONTAL, false)
            adapter = pickerPagesAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(density.columnCount * density.rowCount)
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val width = v.width
                if (width > 0 && pickerPagesAdapter.relayoutIfWidthChanged(width)) {
                    pickerPagesAdapter.notifyItemRangeChanged(0, pickerPagesAdapter.itemCount)
                }
            }
        }
        pickerPagesAdapter.showCategory(1)
    }

    override fun onCreateBarExtension() = pickerLayout.tabsUi.root

    override fun onAttached() {
        IconThemeManager.addOnChangedListener(iconThemeListener)
        pickerLayout.embeddedKeyboard.also {
            pickerPagesAdapter.refreshIfNeeded()
            refreshIconTheme()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onReturnDrawableOverride(returnKeyDrawable.iconThemeDrawable)
            it.keyActionListener = keyActionListener
            it.onAttach()
            it.reapplyTextScale()
        }
        if (FontProviders.checkAndClearRefreshFlag()) {
            pickerLayout.embeddedKeyboard.refreshStyle()
            pickerPagesAdapter.notifyDataSetChanged()
        }
    }

    override fun onDetached() {
        IconThemeManager.removeOnChangedListener(iconThemeListener)
        popup.dismissAll()
        pickerLayout.allowStripScroll = true
        pickerLayout.embeddedKeyboard.also {
            it.onDetach()
            it.keyActionListener = null
        }
    }

    override val showTitle = false
}
