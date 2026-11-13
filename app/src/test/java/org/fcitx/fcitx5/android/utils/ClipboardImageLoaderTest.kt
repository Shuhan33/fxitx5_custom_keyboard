/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardImageLoaderTest {
    @Test
    fun extremeAspectRatioIsSampledByItsLongDimension() {
        val sampleSize = calculateInSampleSize(12_000, 50, 192, 192)

        assertTrue(sampleSize > 1)
        assertTrue(12_000 / sampleSize <= 384)
        assertTrue(50 / sampleSize >= 1)
    }
}
