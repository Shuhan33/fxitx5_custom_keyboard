/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.res.Configuration

object Locales {

    lateinit var fcitxLocale: String
        private set

    lateinit var language: String
        private set

    lateinit var languageWithCountry: String
        private set

    fun onLocaleChange(configuration: Configuration) {
        // slei 定制版固定简体中文，不跟随系统语言。
        fcitxLocale = "zh_CN:zh"
        languageWithCountry = "zh_CN"
        language = "zh"
    }

}