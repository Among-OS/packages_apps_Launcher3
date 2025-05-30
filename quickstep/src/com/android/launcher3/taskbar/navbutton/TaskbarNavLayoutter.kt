/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.launcher3.taskbar.navbutton

import android.content.res.Resources
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import com.android.launcher3.R
import com.android.launcher3.taskbar.TaskbarActivityContext
import com.android.launcher3.taskbar.TaskbarManager.NAV_BAR_INVERSE
import com.android.launcher3.taskbar.TaskbarManager.NAV_BAR_LAYOUT
import com.android.launcher3.util.SettingsCache

/** Layoutter for rendering task bar in large screen, both in 3-button and gesture nav mode. */
class TaskbarNavLayoutter(
    resources: Resources,
    navBarContainer: LinearLayout,
    endContextualContainer: ViewGroup,
    startContextualContainer: ViewGroup,
    imeSwitcher: ImageView?,
    a11yButton: ImageView?,
    space: Space?
) :
    AbstractNavButtonLayoutter(
        resources,
        navBarContainer,
        endContextualContainer,
        startContextualContainer,
        imeSwitcher,
        a11yButton,
        space
    ) {

    override fun layoutButtons(context: TaskbarActivityContext, isA11yButtonPersistent: Boolean) {
        val layoutMode = SettingsCache.INSTANCE.get(homeButton!!.context).getIntValue(NAV_BAR_LAYOUT, 0)

        // Add spacing after the end of the last nav button
        var navMarginEnd =
            resources.getDimension(context.deviceProfile.inv.inlineNavButtonsEndSpacing).toInt()

        val cutout = context.display.cutout
        val bottomRect = cutout?.boundingRectBottom
        if (bottomRect != null && !bottomRect.isEmpty) {
            navMarginEnd = bottomRect.width()
        }

        val contextualWidth = endContextualContainer.width
        // If contextual buttons are showing, we check if the end margin is enough for the
        // contextual button to be showing - if not, move the nav buttons over a smidge
        if (isA11yButtonPersistent && navMarginEnd < contextualWidth) {
            // Additional spacing, eat up half of space between last icon and nav button
            navMarginEnd += resources.getDimensionPixelSize(R.dimen.taskbar_hotseat_nav_spacing) / 2
        }

        val endFactor = when (layoutMode) {
            2 -> 0.4f  // left
            3 -> 1.6f  // right
            else -> 1f // normal & compact
        }
        val navButtonParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        navButtonParams.apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            marginEnd = (endFactor * navMarginEnd.toFloat()).toInt()
        }
        navButtonContainer.orientation = LinearLayout.HORIZONTAL
        navButtonContainer.layoutParams = navButtonParams

        if (backButton != null && homeButton != null && recentsButton != null) {
            navButtonContainer.removeAllViews()

            if (SettingsCache.INSTANCE.get(context).getValue(NAV_BAR_INVERSE, 0)) {
                navButtonContainer.addView(recentsButton)
                navButtonContainer.addView(homeButton)
                navButtonContainer.addView(backButton)
            } else {
                navButtonContainer.addView(backButton)
                navButtonContainer.addView(homeButton)
                navButtonContainer.addView(recentsButton)
            }
        }

        // Add the spaces in between the nav buttons
        val spaceInBetween = resources.getDimensionPixelSize(R.dimen.taskbar_button_space_inbetween)
        val spaceInBetweenDiv = if (layoutMode == 0) 1 else 4
        for (i in 0 until navButtonContainer.childCount) {
            val navButton = navButtonContainer.getChildAt(i)
            val buttonLayoutParams = navButton.layoutParams as LinearLayout.LayoutParams
            buttonLayoutParams.weight = 0f
            when (i) {
                0 -> {
                    buttonLayoutParams.marginEnd = spaceInBetween / 2
                }
                navButtonContainer.childCount - 1 -> {
                    buttonLayoutParams.marginStart = spaceInBetween / 2
                }
                else -> {
                    buttonLayoutParams.marginStart = (spaceInBetween / 2) / spaceInBetweenDiv
                    buttonLayoutParams.marginEnd = (spaceInBetween / 2) / spaceInBetweenDiv
                }
            }
        }

        endContextualContainer.removeAllViews()
        startContextualContainer.removeAllViews()

        if (!context.deviceProfile.isGestureMode) {
            val contextualMargin =
                resources.getDimensionPixelSize(R.dimen.taskbar_contextual_button_padding)
            repositionContextualContainer(endContextualContainer, WRAP_CONTENT, 0, 0, Gravity.END)
            repositionContextualContainer(
                startContextualContainer,
                WRAP_CONTENT,
                contextualMargin,
                contextualMargin,
                Gravity.START
            )

            if (imeSwitcher != null) {
                val imeStartMargin =
                    resources.getDimensionPixelSize(
                        R.dimen.taskbar_ime_switcher_button_margin_start
                    )
                startContextualContainer.addView(imeSwitcher)
                val imeSwitcherButtonParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                imeSwitcherButtonParams.apply {
                    marginStart = imeStartMargin
                    gravity = Gravity.CENTER_VERTICAL
                }
                imeSwitcher.layoutParams = imeSwitcherButtonParams
            }
            if (a11yButton != null) {
                endContextualContainer.addView(a11yButton)
                a11yButton.layoutParams = getParamsToCenterView()
            }
        }
    }
}
