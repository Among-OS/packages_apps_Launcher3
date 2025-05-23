/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.folder

import android.R
import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.launcher3.LauncherPrefs.Companion.THEMED_ICONS
import com.android.launcher3.LauncherPrefs.Companion.get
import com.android.launcher3.graphics.PreloadIconDrawable
import com.android.launcher3.icons.FastBitmapDrawable
import com.android.launcher3.icons.UserBadgeDrawable
import com.android.launcher3.icons.mono.MonoThemedBitmap
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_ARCHIVED
import com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_INSTALL_SESSION_ACTIVE
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.ActivityContextWrapper
import com.android.launcher3.util.Executors
import com.android.launcher3.util.FlagOp
import com.android.launcher3.util.LauncherLayoutBuilder
import com.android.launcher3.util.LauncherModelHelper
import com.android.launcher3.util.TestUtil
import com.android.launcher3.util.UserIconInfo
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for [PreviewItemManager] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PreviewItemManagerTest {

    private lateinit var previewItemManager: PreviewItemManager
    private lateinit var context: Context
    private lateinit var folderItems: ArrayList<ItemInfo>
    private lateinit var modelHelper: LauncherModelHelper
    private lateinit var folderIcon: FolderIcon

    private var defaultThemedIcons = false

    @Before
    fun setup() {
        getInstrumentation().runOnMainSync {
            folderIcon =
                FolderIcon(ActivityContextWrapper(ApplicationProvider.getApplicationContext()))
        }
        context = getInstrumentation().targetContext
        previewItemManager = PreviewItemManager(folderIcon)
        modelHelper = LauncherModelHelper()
        modelHelper
            .setupDefaultLayoutProvider(
                LauncherLayoutBuilder()
                    .atWorkspace(0, 0, 1)
                    .putFolder(R.string.copy)
                    .addApp(LauncherModelHelper.TEST_PACKAGE, LauncherModelHelper.TEST_ACTIVITY)
                    .addApp(LauncherModelHelper.TEST_PACKAGE, LauncherModelHelper.TEST_ACTIVITY2)
                    .addApp(LauncherModelHelper.TEST_PACKAGE, LauncherModelHelper.TEST_ACTIVITY3)
                    .addApp(LauncherModelHelper.TEST_PACKAGE, LauncherModelHelper.TEST_ACTIVITY4)
                    .build()
            )
            .loadModelSync()
        folderItems = modelHelper.bgDataModel.collections.valueAt(0).getContents()
        folderIcon.mInfo = modelHelper.bgDataModel.collections.valueAt(0) as FolderInfo
        folderIcon.mInfo.getContents().addAll(folderItems)

        // Use getAppContents() to "cast" contents to WorkspaceItemInfo so we can set bitmaps
        val folderApps = modelHelper.bgDataModel.collections.valueAt(0).getAppContents()
        // Set first icon to be themed.
        folderApps[0].bitmap.themedBitmap =
            MonoThemedBitmap(
                folderApps[0].bitmap.icon,
                Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888),
            )

        // Set second icon to be non-themed.
        folderApps[1].bitmap.themedBitmap = null

        // Set third icon to be themed with badge.
        folderApps[2].bitmap.themedBitmap =
            MonoThemedBitmap(
                folderApps[2].bitmap.icon,
                Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888),
            )
        folderApps[2].bitmap = folderApps[2].bitmap.withFlags(profileFlagOp(UserIconInfo.TYPE_WORK))

        // Set fourth icon to be non-themed with badge.
        folderApps[3].bitmap = folderApps[3].bitmap.withFlags(profileFlagOp(UserIconInfo.TYPE_WORK))
        folderApps[3].bitmap.themedBitmap = null

        defaultThemedIcons = get(context).get(THEMED_ICONS)
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        get(context).put(THEMED_ICONS, defaultThemedIcons)
        modelHelper.destroy()
    }

    @Test
    fun checkThemedIconWithThemingOn_iconShouldBeThemed() {
        get(context).put(THEMED_ICONS, true)
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[0])

        assert((drawingParams.drawable as FastBitmapDrawable).isThemed)
    }

    @Test
    fun checkThemedIconWithThemingOff_iconShouldNotBeThemed() {
        get(context).put(THEMED_ICONS, false)
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[0])

        assert(!(drawingParams.drawable as FastBitmapDrawable).isThemed)
    }

    @Test
    fun checkUnthemedIconWithThemingOn_iconShouldNotBeThemed() {
        get(context).put(THEMED_ICONS, true)
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[1])

        assert(!(drawingParams.drawable as FastBitmapDrawable).isThemed)
    }

    @Test
    fun checkUnthemedIconWithThemingOff_iconShouldNotBeThemed() {
        get(context).put(THEMED_ICONS, false)
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[1])

        assert(!(drawingParams.drawable as FastBitmapDrawable).isThemed)
    }

    @Test
    fun checkThemedIconWithBadgeWithThemingOn_iconAndBadgeShouldBeThemed() {
        get(context).put(THEMED_ICONS, true)
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[2])

        assert((drawingParams.drawable as FastBitmapDrawable).isThemed)
        assert(
            ((drawingParams.drawable as FastBitmapDrawable).badge as UserBadgeDrawable).mIsThemed
        )
    }

    @Test
    fun checkUnthemedIconWithBadgeWithThemingOn_badgeShouldBeThemed() {
        get(context).put(THEMED_ICONS, true)
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[3])

        assert(!(drawingParams.drawable as FastBitmapDrawable).isThemed)
        assert(
            ((drawingParams.drawable as FastBitmapDrawable).badge as UserBadgeDrawable).mIsThemed
        )
    }

    @Test
    fun checkUnthemedIconWithBadgeWithThemingOff_iconAndBadgeShouldNotBeThemed() {
        get(context).put(THEMED_ICONS, false)
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)

        previewItemManager.setDrawable(drawingParams, folderItems[3])

        assert(!(drawingParams.drawable as FastBitmapDrawable).isThemed)
        assert(
            !((drawingParams.drawable as FastBitmapDrawable).badge as UserBadgeDrawable).mIsThemed
        )
    }

    @Test
    fun `Inactive archived app previews are not drawn as preload icon`() {
        // Given
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)
        val archivedApp =
            WorkspaceItemInfo().apply {
                runtimeStatusFlags = runtimeStatusFlags or FLAG_ARCHIVED
                runtimeStatusFlags = runtimeStatusFlags and FLAG_INSTALL_SESSION_ACTIVE.inv()
            }
        // When
        previewItemManager.setDrawable(drawingParams, archivedApp)
        // Then
        assertThat(drawingParams.drawable).isNotInstanceOf(PreloadIconDrawable::class.java)
    }

    @Test
    fun `Actively installing archived app previews are drawn as preload icon`() {
        // Given
        val drawingParams = PreviewItemDrawingParams(0f, 0f, 0f)
        val archivedApp =
            WorkspaceItemInfo().apply {
                runtimeStatusFlags = runtimeStatusFlags or FLAG_ARCHIVED
                runtimeStatusFlags = runtimeStatusFlags or FLAG_INSTALL_SESSION_ACTIVE
            }
        // When
        TestUtil.runOnExecutorSync(Executors.MAIN_EXECUTOR) {
            // Run on main thread because preload drawable triggers animator
            previewItemManager.setDrawable(drawingParams, archivedApp)
        }
        // Then
        assertThat(drawingParams.drawable).isInstanceOf(PreloadIconDrawable::class.java)
    }

    private fun profileFlagOp(type: Int) =
        UserIconInfo(Process.myUserHandle(), type).applyBitmapInfoFlags(FlagOp.NO_OP)
}
