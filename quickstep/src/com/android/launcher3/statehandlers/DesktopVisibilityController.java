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
 * limitations under the License.
 */
package com.android.launcher3.statehandlers;

import static android.view.View.VISIBLE;
import static android.window.DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.content.Context;
import android.os.Debug;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.statemanager.BaseState;
import com.android.launcher3.statemanager.StatefulActivity;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.views.ActivityContext;
import com.android.quickstep.GestureState;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.fallback.RecentsState;
import com.android.wm.shell.desktopmode.IDesktopTaskListener;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * Controls the visibility of the workspace and the resumed / paused state when desktop mode
 * is enabled.
 */
public class DesktopVisibilityController {

    private static final String TAG = "DesktopVisController";
    private static final boolean DEBUG = false;
    private final Set<DesktopVisibilityListener> mDesktopVisibilityListeners = new HashSet<>();
    private final Set<TaskbarDesktopModeListener> mTaskbarDesktopModeListeners = new HashSet<>();

    private int mVisibleDesktopTasksCount;
    private boolean mInOverviewState;
    private boolean mBackgroundStateEnabled;
    private boolean mGestureInProgress;

    @Nullable
    private DesktopTaskListenerImpl mDesktopTaskListener;

    @Nullable
    private Context mContext;

    public DesktopVisibilityController(@NonNull Context context) {
        setContext(context);
    }

    /** Sets the context and re-registers the System Ui listener */
    private void setContext(@Nullable Context context) {
        unregisterSystemUiListener();
        mContext = context;
        registerSystemUiListener();
    }

    /** Register a listener with System UI to receive updates about desktop tasks state */
    private void registerSystemUiListener() {
        if (mContext == null) {
            return;
        }
        if (mDesktopTaskListener != null) {
            return;
        }
        mDesktopTaskListener = new DesktopTaskListenerImpl(this, mContext.getDisplayId());
        SystemUiProxy.INSTANCE.get(mContext).setDesktopTaskListener(mDesktopTaskListener);
    }

    /**
     * Clear listener from System UI that was set with {@link #registerSystemUiListener()}
     */
    private void unregisterSystemUiListener() {
        if (mContext == null) {
            return;
        }
        if (mDesktopTaskListener == null) {
            return;
        }
        SystemUiProxy.INSTANCE.get(mContext).setDesktopTaskListener(null);
        mDesktopTaskListener.release();
        mDesktopTaskListener = null;
    }

    /**
     * Whether desktop tasks are visible in desktop mode.
     */
    public boolean areDesktopTasksVisible() {
        boolean desktopTasksVisible = mVisibleDesktopTasksCount > 0;
        if (DEBUG) {
            Log.d(TAG, "areDesktopTasksVisible: desktopVisible=" + desktopTasksVisible);
        }
        return desktopTasksVisible;
    }

    /**
     * Number of visible desktop windows in desktop mode.
     */
    public int getVisibleDesktopTasksCount() {
        return mVisibleDesktopTasksCount;
    }

    /** Registers a listener for Desktop Mode visibility updates. */
    public void registerDesktopVisibilityListener(DesktopVisibilityListener listener) {
        mDesktopVisibilityListeners.add(listener);
    }

    /** Removes a previously registered Desktop Mode visibility listener. */
    public void unregisterDesktopVisibilityListener(DesktopVisibilityListener listener) {
        mDesktopVisibilityListeners.remove(listener);
    }

    /** Registers a listener for Taskbar changes in Desktop Mode. */
    public void registerTaskbarDesktopModeListener(TaskbarDesktopModeListener listener) {
        mTaskbarDesktopModeListeners.add(listener);
    }

    /** Removes a previously registered listener for Taskbar changes in Desktop Mode. */
    public void unregisterTaskbarDesktopModeListener(TaskbarDesktopModeListener listener) {
        mTaskbarDesktopModeListeners.remove(listener);
    }

    /**
     * Sets the number of desktop windows that are visible and updates launcher visibility based on
     * it.
     */
    public void setVisibleDesktopTasksCount(int visibleTasksCount) {
        if (mContext == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "setVisibleDesktopTasksCount: visibleTasksCount=" + visibleTasksCount
                    + " currentValue=" + mVisibleDesktopTasksCount);
        }

        if (visibleTasksCount != mVisibleDesktopTasksCount) {
            final boolean wasVisible = mVisibleDesktopTasksCount > 0;
            final boolean isVisible = visibleTasksCount > 0;
            final boolean wereDesktopTasksVisibleBefore = areDesktopTasksVisible();
            mVisibleDesktopTasksCount = visibleTasksCount;
            final boolean areDesktopTasksVisibleNow = areDesktopTasksVisible();
            if (wereDesktopTasksVisibleBefore != areDesktopTasksVisibleNow) {
                notifyDesktopVisibilityListeners(areDesktopTasksVisibleNow);
            }

            if (!ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue()
                    && wasVisible != isVisible) {
                // TODO: b/333533253 - Remove after flag rollout
                if (mVisibleDesktopTasksCount > 0) {
                    setLauncherViewsVisibility(View.INVISIBLE);
                    if (!mInOverviewState) {
                        // When desktop tasks are visible & we're not in overview, we want launcher
                        // to appear paused, this ensures that taskbar displays.
                        markLauncherPaused();
                    }
                } else {
                    setLauncherViewsVisibility(View.VISIBLE);
                    // If desktop tasks aren't visible, ensure that launcher appears resumed to
                    // behave normally.
                    markLauncherResumed();
                }
            }
        }
    }

    public void onLauncherStateChanged(LauncherState state) {
        onLauncherStateChanged(
                state, state == LauncherState.BACKGROUND_APP, state.isRecentsViewVisible);
    }

    public void onLauncherStateChanged(RecentsState state) {
        onLauncherStateChanged(
                state, state == RecentsState.BACKGROUND_APP, state.isRecentsViewVisible());
    }

    /**
     * Process launcher state change and update launcher view visibility based on desktop state
     */
    public void onLauncherStateChanged(
            BaseState<?> state, boolean isBackgroundAppState, boolean isRecentsViewVisible) {
        if (DEBUG) {
            Log.d(TAG, "onLauncherStateChanged: newState=" + state);
        }
        setBackgroundStateEnabled(isBackgroundAppState);
        // Desktop visibility tracks overview and background state separately
        setOverviewStateEnabled(!isBackgroundAppState && isRecentsViewVisible);
    }

    private void setOverviewStateEnabled(boolean overviewStateEnabled) {
        if (mContext == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "setOverviewStateEnabled: enabled=" + overviewStateEnabled
                    + " currentValue=" + mInOverviewState);
        }
        if (overviewStateEnabled != mInOverviewState) {
            mInOverviewState = overviewStateEnabled;
            final boolean areDesktopTasksVisibleNow = areDesktopTasksVisible();

            if (ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue()) {
                return;
            }
            // TODO: b/333533253 - Clean up after flag rollout

            if (mInOverviewState) {
                setLauncherViewsVisibility(View.VISIBLE);
                markLauncherResumed();
            } else if (areDesktopTasksVisibleNow && !mGestureInProgress) {
                // Switching out of overview state and gesture finished.
                // If desktop tasks are still visible, hide launcher again.
                setLauncherViewsVisibility(View.INVISIBLE);
                markLauncherPaused();
            }
        }
    }

    private void notifyDesktopVisibilityListeners(boolean areDesktopTasksVisible) {
        if (mContext == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "notifyDesktopVisibilityListeners: visible=" + areDesktopTasksVisible);
        }
        for (DesktopVisibilityListener listener : mDesktopVisibilityListeners) {
            listener.onDesktopVisibilityChanged(areDesktopTasksVisible);
        }
        DisplayController.INSTANCE.get(mContext).notifyConfigChange();
    }

    private void notifyTaskbarDesktopModeListeners(boolean doesAnyTaskRequireTaskbarRounding) {
        if (DEBUG) {
            Log.d(TAG, "notifyTaskbarDesktopModeListeners: doesAnyTaskRequireTaskbarRounding="
                    + doesAnyTaskRequireTaskbarRounding);
        }
        for (TaskbarDesktopModeListener listener : mTaskbarDesktopModeListeners) {
            listener.onTaskbarCornerRoundingUpdate(doesAnyTaskRequireTaskbarRounding);
        }
    }

    /**
     * TODO: b/333533253 - Remove after flag rollout
     */
    private void setBackgroundStateEnabled(boolean backgroundStateEnabled) {
        if (DEBUG) {
            Log.d(TAG, "setBackgroundStateEnabled: enabled=" + backgroundStateEnabled
                    + " currentValue=" + mBackgroundStateEnabled);
        }
        if (backgroundStateEnabled != mBackgroundStateEnabled) {
            mBackgroundStateEnabled = backgroundStateEnabled;
            if (mBackgroundStateEnabled) {
                setLauncherViewsVisibility(View.VISIBLE);
                markLauncherResumed();
            } else if (areDesktopTasksVisible() && !mGestureInProgress) {
                // Switching out of background state. If desktop tasks are visible, pause launcher.
                setLauncherViewsVisibility(View.INVISIBLE);
                markLauncherPaused();
            }
        }
    }

    /**
     * Whether recents gesture is currently in progress.
     *
     * TODO: b/333533253 - Remove after flag rollout
     */
    public boolean isRecentsGestureInProgress() {
        return mGestureInProgress;
    }

    /**
     * Notify controller that recents gesture has started.
     *
     * TODO: b/333533253 - Remove after flag rollout
     */
    public void setRecentsGestureStart() {
        if (DEBUG) {
            Log.d(TAG, "setRecentsGestureStart");
        }
        setRecentsGestureInProgress(true);
    }

    /**
     * Notify controller that recents gesture finished with the given
     * {@link com.android.quickstep.GestureState.GestureEndTarget}
     *
     * TODO: b/333533253 - Remove after flag rollout
     */
    public void setRecentsGestureEnd(@Nullable GestureState.GestureEndTarget endTarget) {
        if (DEBUG) {
            Log.d(TAG, "setRecentsGestureEnd: endTarget=" + endTarget);
        }
        setRecentsGestureInProgress(false);

        if (endTarget == null) {
            // Gesture did not result in a new end target. Ensure launchers gets paused again.
            markLauncherPaused();
        }
    }

    /**
     * TODO: b/333533253 - Remove after flag rollout
     */
    private void setRecentsGestureInProgress(boolean gestureInProgress) {
        if (gestureInProgress != mGestureInProgress) {
            mGestureInProgress = gestureInProgress;
        }
    }

    /**
     * TODO: b/333533253 - Remove after flag rollout
     */
    private void setLauncherViewsVisibility(int visibility) {
        if (mContext == null) {
            return;
        }
        if (ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue()) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "setLauncherViewsVisibility: visibility=" + visibility + " "
                    + Debug.getCaller());
        }
        if (!(mContext instanceof ActivityContext activity)) {
            return;
        }
        View dragLayer = activity.getDragLayer();
        if (dragLayer != null) {
            dragLayer.setVisibility(visibility);
        }
        if (!(activity instanceof Launcher launcher)) {
            return;
        }
        View workspaceView = launcher.getWorkspace();
        if (workspaceView != null) {
            workspaceView.setVisibility(visibility);
        }
        if (launcher instanceof QuickstepLauncher ql
                && ql.getTaskbarUIController() != null
                && mVisibleDesktopTasksCount != 0) {
            ql.getTaskbarUIController().onLauncherVisibilityChanged(visibility == VISIBLE);
        }
    }

    /**
     * TODO: b/333533253 - Remove after flag rollout
     */
    private void markLauncherPaused() {
        if (mContext == null) {
            return;
        }
        if (ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue()) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "markLauncherPaused " + Debug.getCaller());
        }
        StatefulActivity<LauncherState> activity =
                QuickstepLauncher.ACTIVITY_TRACKER.getCreatedContext();
        if (activity != null) {
            activity.setPaused();
        }
    }

    /**
     * TODO: b/333533253 - Remove after flag rollout
     */
    private void markLauncherResumed() {
        if (mContext == null) {
            return;
        }
        if (ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue()) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "markLauncherResumed " + Debug.getCaller());
        }
        StatefulActivity<LauncherState> activity =
                QuickstepLauncher.ACTIVITY_TRACKER.getCreatedContext();
        // Check activity state before calling setResumed(). Launcher may have been actually
        // paused (eg fullscreen task moved to front).
        // In this case we should not mark the activity as resumed.
        if (activity != null && activity.isResumed()) {
            activity.setResumed();
        }
    }

    public void onDestroy() {
        setContext(null);
    }

    public void dumpLogs(String prefix, PrintWriter pw) {
        pw.println(prefix + "DesktopVisibilityController:");

        pw.println(prefix + "\tmDesktopVisibilityListeners=" + mDesktopVisibilityListeners);
        pw.println(prefix + "\tmVisibleDesktopTasksCount=" + mVisibleDesktopTasksCount);
        pw.println(prefix + "\tmInOverviewState=" + mInOverviewState);
        pw.println(prefix + "\tmBackgroundStateEnabled=" + mBackgroundStateEnabled);
        pw.println(prefix + "\tmGestureInProgress=" + mGestureInProgress);
        pw.println(prefix + "\tmDesktopTaskListener=" + mDesktopTaskListener);
        pw.println(prefix + "\tmContext=" + mContext);
    }

    /** A listener for when the user enters/exits Desktop Mode. */
    public interface DesktopVisibilityListener {
        /**
         * Callback for when the user enters or exits Desktop Mode
         *
         * @param visible whether Desktop Mode is now visible
         */
        void onDesktopVisibilityChanged(boolean visible);
    }

    /**
     * Wrapper for the IDesktopTaskListener stub to prevent lingering references to the launcher
     * activity via the controller.
     */
    private static class DesktopTaskListenerImpl extends IDesktopTaskListener.Stub {

        private DesktopVisibilityController mController;
        private final int mDisplayId;

        DesktopTaskListenerImpl(@NonNull DesktopVisibilityController controller, int displayId) {
            mController = controller;
            mDisplayId = displayId;
        }

        /**
         * Clears any references to the controller.
         */
        void release() {
            mController = null;
        }

        @Override
        public void onTasksVisibilityChanged(int displayId, int visibleTasksCount) {
            MAIN_EXECUTOR.execute(() -> {
                if (mController != null && displayId == mDisplayId) {
                    if (DEBUG) {
                        Log.d(TAG, "desktop visible tasks count changed=" + visibleTasksCount);
                    }
                    mController.setVisibleDesktopTasksCount(visibleTasksCount);
                }
            });
        }

        @Override
        public void onStashedChanged(int displayId, boolean stashed) {
            Log.w(TAG, "DesktopTaskListenerImpl: onStashedChanged is deprecated");
        }

        @Override
        public void onTaskbarCornerRoundingUpdate(boolean doesAnyTaskRequireTaskbarRounding) {
            MAIN_EXECUTOR.execute(() -> {
                if (mController != null && DesktopModeStatus.useRoundedCorners()) {
                    Log.d(TAG, "DesktopTaskListenerImpl: doesAnyTaskRequireTaskbarRounding= "
                            + doesAnyTaskRequireTaskbarRounding);
                    mController.notifyTaskbarDesktopModeListeners(
                            doesAnyTaskRequireTaskbarRounding);
                }
            });
        }

        public void onEnterDesktopModeTransitionStarted(int transitionDuration) {

        }

        @Override
        public void onExitDesktopModeTransitionStarted(int transitionDuration) {

        }
    }

    /** A listener for Taskbar in Desktop Mode. */
    public interface TaskbarDesktopModeListener {
        /**
         * Callback for when task is resized in desktop mode.
         *
         * @param doesAnyTaskRequireTaskbarRounding whether task requires taskbar corner roundness.
         */
        void onTaskbarCornerRoundingUpdate(boolean doesAnyTaskRequireTaskbarRounding);
    }
}
