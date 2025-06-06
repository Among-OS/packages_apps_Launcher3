/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.launcher3;

import static android.app.admin.DevicePolicyManager.ACTION_DEVICE_POLICY_RESOURCE_UPDATED;
import static android.content.Context.RECEIVER_EXPORTED;

import static com.android.launcher3.Flags.enableSmartspaceRemovalToggle;
import static com.android.launcher3.InvariantDeviceProfile.GRID_NAME_PREFS_KEY;
import static com.android.launcher3.LauncherPrefs.DB_FILE;
import static com.android.launcher3.LauncherPrefs.GRID_NAME;
import static com.android.launcher3.LauncherPrefs.ICON_STATE;
import static com.android.launcher3.LauncherPrefs.THEMED_ICONS;
import static com.android.launcher3.model.DeviceGridState.KEY_DB_FILE;
import static com.android.launcher3.model.LoaderTask.SMARTSPACE_ON_HOME_SCREEN;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.launcher3.util.SettingsCache.NOTIFICATION_BADGING_URI;
import static com.android.launcher3.util.SettingsCache.PRIVATE_SPACE_HIDE_WHEN_LOCKED_URI;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ArchiveCompatibilityParams;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.os.BuildCompat;

import com.android.launcher3.graphics.IconShape;
import com.android.launcher3.icons.IconCache;
import com.android.launcher3.icons.IconProvider;
import com.android.launcher3.icons.LauncherIconProvider;
import com.android.launcher3.icons.LauncherIcons;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.model.ModelLauncherCallbacks;
import com.android.launcher3.model.WidgetsFilterDataProvider;
import com.android.launcher3.notification.NotificationListener;
import com.android.launcher3.pm.InstallSessionHelper;
import com.android.launcher3.pm.InstallSessionTracker;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.LockedUserState;
import com.android.launcher3.util.MainThreadInitializedObject;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.RunnableList;
import com.android.launcher3.util.SafeCloseable;
import com.android.launcher3.util.SettingsCache;
import com.android.launcher3.util.SimpleBroadcastReceiver;
import com.android.launcher3.util.Themes;
import com.android.launcher3.util.TraceHelper;
import com.android.launcher3.widget.custom.CustomWidgetManager;

import java.util.Locale;
import java.util.Objects;

public class LauncherAppState implements SafeCloseable {

    public static final String TAG = "LauncherAppState";
    public static final String ACTION_FORCE_ROLOAD = "force-reload-launcher";

    // We do not need any synchronization for this variable as its only written on UI thread.
    public static final MainThreadInitializedObject<LauncherAppState> INSTANCE =
            new MainThreadInitializedObject<>(LauncherAppState::new);

    private final Context mContext;
    private final LauncherModel mModel;
    private final LauncherIconProvider mIconProvider;
    private final IconCache mIconCache;
    private final InvariantDeviceProfile mInvariantDeviceProfile;
    private boolean mIsSafeModeEnabled;

    private final RunnableList mOnTerminateCallback = new RunnableList();

    private boolean mNeedsRestart;

    public static LauncherAppState getInstance(Context context) {
        return INSTANCE.get(context);
    }

    public Context getContext() {
        return mContext;
    }

    @SuppressWarnings("NewApi")
    public LauncherAppState(Context context) {
        this(context, LauncherFiles.APP_ICONS_DB);
        Log.v(Launcher.TAG, "LauncherAppState initiated");
        Preconditions.assertUIThread();

        mIsSafeModeEnabled = TraceHelper.allowIpcs("isSafeMode",
                () -> context.getPackageManager().isSafeMode());
        mInvariantDeviceProfile.addOnChangeListener(modelPropertiesChanged -> {
            if (modelPropertiesChanged) {
                refreshAndReloadLauncher();
            }
        });

        ModelLauncherCallbacks callbacks = mModel.newModelCallbacks();
        LauncherApps launcherApps = mContext.getSystemService(LauncherApps.class);
        launcherApps.registerCallback(callbacks);
        mOnTerminateCallback.add(() ->
                mContext.getSystemService(LauncherApps.class).unregisterCallback(callbacks));

        if (BuildCompat.isAtLeastV() && Flags.enableSupportForArchiving()) {
            ArchiveCompatibilityParams params = new ArchiveCompatibilityParams();
            params.setEnableUnarchivalConfirmation(false);
            params.setEnableIconOverlay(!Flags.useNewIconForArchivedApps());
            launcherApps.setArchiveCompatibility(params);
        }

        SimpleBroadcastReceiver modelChangeReceiver =
                new SimpleBroadcastReceiver(UI_HELPER_EXECUTOR, mModel::onBroadcastIntent);
        final Locale oldLocale = mContext.getResources().getConfiguration().locale;
        modelChangeReceiver.register(
                mContext,
                () -> {
                    // if local has changed before receiver is registered on bg thread,
                    // mModel needs to reload.
                    Locale newLocale = mContext.getResources().getConfiguration().locale;
                    if (!Objects.equals(oldLocale, newLocale)) {
                        mModel.forceReload();
                    }
                },
                Intent.ACTION_LOCALE_CHANGED,
                ACTION_DEVICE_POLICY_RESOURCE_UPDATED);
        if (BuildConfig.IS_STUDIO_BUILD) {
            mContext.registerReceiver(modelChangeReceiver, new IntentFilter(ACTION_FORCE_ROLOAD),
                    RECEIVER_EXPORTED);
        }
        mOnTerminateCallback.add(() -> modelChangeReceiver.unregisterReceiverSafely(mContext));

        SafeCloseable userChangeListener = UserCache.INSTANCE.get(mContext)
                .addUserEventListener(mModel::onUserEvent);
        mOnTerminateCallback.add(userChangeListener::close);

        if (enableSmartspaceRemovalToggle()) {
            OnSharedPreferenceChangeListener firstPagePinnedItemListener =
                    new OnSharedPreferenceChangeListener() {
                        @Override
                        public void onSharedPreferenceChanged(
                                SharedPreferences sharedPreferences, String key) {
                            if (SMARTSPACE_ON_HOME_SCREEN.equals(key)) {
                                mModel.forceReload();
                            }
                        }
                    };
            LauncherPrefs.getPrefs(mContext).registerOnSharedPreferenceChangeListener(
                    firstPagePinnedItemListener);
            mOnTerminateCallback.add(() -> LauncherPrefs.getPrefs(mContext)
                    .unregisterOnSharedPreferenceChangeListener(firstPagePinnedItemListener));
        }

        LockedUserState.get(context).runOnUserUnlocked(() -> {
            CustomWidgetManager cwm = CustomWidgetManager.INSTANCE.get(mContext);
            mOnTerminateCallback.add(cwm.addWidgetRefreshCallback(mModel::rebindCallbacks)::close);

            IconObserver observer = new IconObserver();
            SafeCloseable iconChangeTracker = mIconProvider.registerIconChangeListener(
                    observer, MODEL_EXECUTOR.getHandler());
            mOnTerminateCallback.add(iconChangeTracker::close);
            MODEL_EXECUTOR.execute(observer::verifyIconChanged);
            LauncherPrefs.get(context).addListener(observer, THEMED_ICONS);
            mOnTerminateCallback.add(
                    () -> LauncherPrefs.get(mContext).removeListener(observer, THEMED_ICONS));

            InstallSessionTracker installSessionTracker =
                    InstallSessionHelper.INSTANCE.get(context).registerInstallTracker(callbacks);
            mOnTerminateCallback.add(installSessionTracker::unregister);
        });

        // Register an observer to rebind the notification listener when dots are re-enabled.
        SettingsCache settingsCache = SettingsCache.INSTANCE.get(mContext);
        SettingsCache.OnChangeListener notificationLister = this::onNotificationSettingsChanged;
        settingsCache.register(NOTIFICATION_BADGING_URI, notificationLister);
        onNotificationSettingsChanged(settingsCache.getValue(NOTIFICATION_BADGING_URI));
        mOnTerminateCallback.add(() ->
                settingsCache.unregister(NOTIFICATION_BADGING_URI, notificationLister));
        // Register an observer to notify Launcher about Private Space settings toggle.
        registerPrivateSpaceHideWhenLockListener(settingsCache);

    }

    public LauncherAppState(Context context, @Nullable String iconCacheFileName) {
        mContext = context;

        mInvariantDeviceProfile = InvariantDeviceProfile.INSTANCE.get(context);
        mIconProvider = new LauncherIconProvider(context);
        mIconCache = new IconCache(mContext, mInvariantDeviceProfile,
                iconCacheFileName, mIconProvider);
        mModel = new LauncherModel(context, this, mIconCache,
                WidgetsFilterDataProvider.Companion.newInstance(context), new AppFilter(mContext),
                PackageManagerHelper.INSTANCE.get(context), iconCacheFileName != null);
        mOnTerminateCallback.add(mIconCache::close);
        mOnTerminateCallback.add(mModel::destroy);
    }

    private void onNotificationSettingsChanged(boolean areNotificationDotsEnabled) {
        if (areNotificationDotsEnabled) {
            NotificationListener.requestRebind(new ComponentName(
                    mContext, NotificationListener.class));
        }
    }

    private void registerPrivateSpaceHideWhenLockListener(SettingsCache settingsCache) {
        SettingsCache.OnChangeListener psHideWhenLockChangedListener =
                this::onPrivateSpaceHideWhenLockChanged;
        settingsCache.register(PRIVATE_SPACE_HIDE_WHEN_LOCKED_URI, psHideWhenLockChangedListener);
        mOnTerminateCallback.add(() -> settingsCache.unregister(PRIVATE_SPACE_HIDE_WHEN_LOCKED_URI,
                psHideWhenLockChangedListener));
    }

    private void onPrivateSpaceHideWhenLockChanged(boolean isPrivateSpaceHideOnLockEnabled) {
        mModel.forceReload();
    }

    public void setNeedsRestart() {
        mNeedsRestart = true;
    }

    public void checkIfRestartNeeded() {
        // we destroyed Settings activity with the back button
        // so we force a restart now if needed without waiting for home button press
        if (mNeedsRestart) {
            Utilities.restart(mContext);
        }
    }

    private void refreshAndReloadLauncher() {
        LauncherIcons.clearPool(mContext);
        mIconCache.updateIconParams(
                mInvariantDeviceProfile.fillResIconDpi, mInvariantDeviceProfile.iconBitmapSize);
        mModel.forceReload();
    }

    /**
     * Call from Application.onTerminate(), which is not guaranteed to ever be called.
     */
    @Override
    public void close() {
        mOnTerminateCallback.executeAllAndDestroy();
    }

    public IconProvider getIconProvider() {
        return mIconProvider;
    }

    public IconCache getIconCache() {
        return mIconCache;
    }

    public LauncherModel getModel() {
        return mModel;
    }

    public InvariantDeviceProfile getInvariantDeviceProfile() {
        return mInvariantDeviceProfile;
    }

    public boolean isSafeModeEnabled() {
        return mIsSafeModeEnabled;
    }

    /**
     * Shorthand for {@link #getInvariantDeviceProfile()}
     */
    public static InvariantDeviceProfile getIDP(Context context) {
        return InvariantDeviceProfile.INSTANCE.get(context);
    }

    private class IconObserver
            implements IconProvider.IconChangeListener, LauncherPrefChangeListener {

        @Override
        public void onAppIconChanged(String packageName, UserHandle user) {
            mModel.onAppIconChanged(packageName, user);
        }

        @Override
        public void onSystemIconStateChanged(String iconState) {
            IconShape.INSTANCE.get(mContext).pickBestShape(mContext);
            refreshAndReloadLauncher();
            LauncherPrefs.get(mContext).put(ICON_STATE, iconState);
        }

        void verifyIconChanged() {
            String iconState = mIconProvider.getSystemIconState();
            if (!iconState.equals(LauncherPrefs.get(mContext).get(ICON_STATE))) {
                onSystemIconStateChanged(iconState);
            }
        }

        @Override
        public void onPrefChanged(String key) {
            if (Themes.KEY_THEMED_ICONS.equals(key)) {
                mIconProvider.setIconThemeSupported(Themes.isThemedIconEnabled(mContext));
                verifyIconChanged();
            } else if (GRID_NAME_PREFS_KEY.equals(key)) {
                FileLog.d(TAG, "onPrefChanged GRID_NAME changed: "
                        + LauncherPrefs.get(mContext).get(GRID_NAME));
            } else if (KEY_DB_FILE.equals(key)) {
                FileLog.d(TAG, "onPrefChanged DB_FILE changed: "
                        + LauncherPrefs.get(mContext).get(DB_FILE));
            }
        }
    }
}
