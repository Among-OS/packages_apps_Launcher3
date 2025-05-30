/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.launcher3.graphics;

import static android.content.res.Configuration.UI_MODE_NIGHT_NO;
import static android.content.res.Configuration.UI_MODE_NIGHT_YES;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.launcher3.LauncherSettings.Favorites.TABLE_NAME;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import android.app.WallpaperColors;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceControlViewHost.SurfacePackage;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.Workspace;
import com.android.launcher3.graphics.LauncherPreviewRenderer.PreviewContext;
import com.android.launcher3.model.BaseLauncherBinder;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.BgDataModel.Callbacks;
import com.android.launcher3.model.GridSizeMigrationDBController;
import com.android.launcher3.model.LoaderTask;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.provider.LauncherDbUtils;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.RunnableList;
import com.android.launcher3.util.Themes;
import com.android.launcher3.widget.LocalColorExtractor;
import com.android.systemui.shared.Flags;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Render preview using surface view. */
@SuppressWarnings("NewApi")
public class PreviewSurfaceRenderer {

    private static final String TAG = "PreviewSurfaceRenderer";

    private static final int FADE_IN_ANIMATION_DURATION = 200;

    private static final String KEY_HOST_TOKEN = "host_token";
    private static final String KEY_VIEW_WIDTH = "width";
    private static final String KEY_VIEW_HEIGHT = "height";
    private static final String KEY_DISPLAY_ID = "display_id";
    private static final String KEY_COLORS = "wallpaper_colors";
    private static final String KEY_COLOR_RESOURCE_IDS = "color_resource_ids";
    private static final String KEY_COLOR_VALUES = "color_values";
    private static final String KEY_DARK_MODE = "use_dark_mode";

    private Context mContext;
    private final IBinder mHostToken;
    private final int mWidth;
    private final int mHeight;
    private String mGridName;

    private final int mDisplayId;
    private final Display mDisplay;
    private final WallpaperColors mWallpaperColors;
    private SparseIntArray mPreviewColorOverride;
    @Nullable private Boolean mDarkMode;
    private final RunnableList mLifeCycleTracker;

    private final SurfaceControlViewHost mSurfaceControlViewHost;

    private boolean mDestroyed = false;
    private boolean mHideQsb;
    @Nullable private FrameLayout mViewRoot = null;

    public PreviewSurfaceRenderer(
            Context context, RunnableList lifecycleTracker, Bundle bundle) throws Exception {
        mContext = context;
        mLifeCycleTracker = lifecycleTracker;
        mGridName = bundle.getString("name");
        bundle.remove("name");
        if (mGridName == null) {
            mGridName = InvariantDeviceProfile.getCurrentGridName(context);
        }
        mWallpaperColors = bundle.getParcelable(KEY_COLORS);
        if (Flags.newCustomizationPickerUi()) {
            updateColorOverrides(bundle);
        }
        mHideQsb = bundle.getBoolean(GridCustomizationsProvider.KEY_HIDE_BOTTOM_ROW);

        mHostToken = bundle.getBinder(KEY_HOST_TOKEN);
        mWidth = bundle.getInt(KEY_VIEW_WIDTH);
        mHeight = bundle.getInt(KEY_VIEW_HEIGHT);
        mDisplayId = bundle.getInt(KEY_DISPLAY_ID);
        mDisplay = context.getSystemService(DisplayManager.class)
                .getDisplay(mDisplayId);
        if (mDisplay == null) {
            throw new IllegalArgumentException("Display ID does not match any displays.");
        }

        mSurfaceControlViewHost = MAIN_EXECUTOR.submit(() -> new MySurfaceControlViewHost(
                mContext,
                context.getSystemService(DisplayManager.class).getDisplay(DEFAULT_DISPLAY),
                mHostToken,
                mLifeCycleTracker))
                .get(5, TimeUnit.SECONDS);
        mLifeCycleTracker.add(this::destroy);
    }

    public int getDisplayId() {
        return mDisplayId;
    }

    public IBinder getHostToken() {
        return mHostToken;
    }

    public SurfacePackage getSurfacePackage() {
        return mSurfaceControlViewHost.getSurfacePackage();
    }

    private void destroy() {
        mDestroyed = true;
    }

    /**
     * A function that queries for the launcher app widget span info
     *
     * @return A SparseArray with the app widget id being the key and the span info being the values
     */
    @WorkerThread
    @Nullable
    public SparseArray<Size> getLoadedLauncherWidgetInfo() {
        final SparseArray<Size> widgetInfo = new SparseArray<>();
        final String query = LauncherSettings.Favorites.ITEM_TYPE + " = "
                + LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET;

        ModelDbController mainController =
                LauncherAppState.getInstance(mContext).getModel().getModelDbController();
        try (Cursor c = mainController.query(TABLE_NAME,
                new String[] {
                        LauncherSettings.Favorites.APPWIDGET_ID,
                        LauncherSettings.Favorites.SPANX,
                        LauncherSettings.Favorites.SPANY
                }, query, null, null)) {
            final int appWidgetIdIndex = c.getColumnIndexOrThrow(
                    LauncherSettings.Favorites.APPWIDGET_ID);
            final int spanXIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SPANX);
            final int spanYIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SPANY);
            while (c.moveToNext()) {
                final int appWidgetId = c.getInt(appWidgetIdIndex);
                final int spanX = c.getInt(spanXIndex);
                final int spanY = c.getInt(spanYIndex);

                widgetInfo.append(appWidgetId, new Size(spanX, spanY));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying for launcher widget info", e);
            return null;
        }

        return widgetInfo;
    }

    /**
     * Generates the preview in background
     */
    public void loadAsync() {
        MODEL_EXECUTOR.execute(this::loadModelData);
    }

    /**
     * Update the grid of the launcher preview
     *
     * @param gridName Name of the grid, e.g. normal, practical
     */
    public void updateGrid(@NonNull String gridName) {
        if (gridName.equals(mGridName)) {
            return;
        }
        mGridName = gridName;
        loadAsync();
    }

    /**
     * Hides the components in the bottom row.
     *
     * @param hide True to hide and false to show.
     */
    public void hideBottomRow(boolean hide) {
        mHideQsb = hide;
        loadAsync();
    }

    /**
     * Updates the colors of the preview.
     *
     * @param bundle Bundle with an int array of color ids and an int array of overriding colors.
     */
    public void previewColor(Bundle bundle) {
        updateColorOverrides(bundle);
        loadAsync();
    }

    private void updateColorOverrides(Bundle bundle) {
        mDarkMode =
                bundle.containsKey(KEY_DARK_MODE) ? bundle.getBoolean(KEY_DARK_MODE) : null;
        int[] ids = bundle.getIntArray(KEY_COLOR_RESOURCE_IDS);
        int[] colors = bundle.getIntArray(KEY_COLOR_VALUES);
        if (ids != null && colors != null) {
            mPreviewColorOverride = new SparseIntArray();
            for (int i = 0; i < ids.length; i++) {
                mPreviewColorOverride.put(ids[i], colors[i]);
            }
        } else {
            mPreviewColorOverride = null;
        }
    }

    /***
     * Generates a new context overriding the theme color and the display size without affecting the
     * main application context
     */
    private Context getPreviewContext() {
        Context context = mContext.createDisplayContext(mDisplay);
        if (mDarkMode != null) {
            Configuration configuration = new Configuration(
                    context.getResources().getConfiguration());
            if (mDarkMode) {
                configuration.uiMode &= ~UI_MODE_NIGHT_NO;
                configuration.uiMode |= UI_MODE_NIGHT_YES;
            } else {
                configuration.uiMode &= ~UI_MODE_NIGHT_YES;
                configuration.uiMode |= UI_MODE_NIGHT_NO;
            }
            context = context.createConfigurationContext(configuration);
        }
        if (Flags.newCustomizationPickerUi()) {
            if (mPreviewColorOverride != null) {
                LocalColorExtractor.newInstance(context)
                        .applyColorsOverride(context, mPreviewColorOverride);
            } else if (mWallpaperColors != null) {
                LocalColorExtractor.newInstance(context)
                        .applyColorsOverride(context, mWallpaperColors);
            }
            if (mWallpaperColors != null) {
                return new ContextThemeWrapper(context,
                        Themes.getActivityThemeRes(context, mWallpaperColors.getColorHints()));
            } else {
                return new ContextThemeWrapper(context,
                        Themes.getActivityThemeRes(context));
            }
        } else {
            if (mWallpaperColors == null) {
                return new ContextThemeWrapper(context,
                        Themes.getActivityThemeRes(context));
            }
            LocalColorExtractor.newInstance(context)
                    .applyColorsOverride(context, mWallpaperColors);
            return new ContextThemeWrapper(context,
                    Themes.getActivityThemeRes(context, mWallpaperColors.getColorHints()));
        }
    }

    @WorkerThread
    private void loadModelData() {
        final Context inflationContext = getPreviewContext();
        final InvariantDeviceProfile idp = new InvariantDeviceProfile(inflationContext, mGridName);
        if (GridSizeMigrationDBController.needsToMigrate(inflationContext, idp)) {
            // Start the migration
            PreviewContext previewContext = new PreviewContext(inflationContext, idp);
            // Copy existing data to preview DB
            LauncherDbUtils.copyTable(LauncherAppState.getInstance(mContext)
                            .getModel().getModelDbController().getDb(),
                    TABLE_NAME,
                    LauncherAppState.getInstance(previewContext)
                            .getModel().getModelDbController().getDb(),
                    TABLE_NAME,
                    mContext);
            LauncherAppState.getInstance(previewContext)
                    .getModel().getModelDbController().clearEmptyDbFlag();

            BgDataModel bgModel = new BgDataModel();
            new LoaderTask(
                    LauncherAppState.getInstance(previewContext),
                    /* bgAllAppsList= */ null,
                    bgModel,
                    LauncherAppState.getInstance(previewContext).getModel().getModelDelegate(),
                    new BaseLauncherBinder(LauncherAppState.getInstance(previewContext), bgModel,
                            /* bgAllAppsList= */ null, new Callbacks[0]),
                    LauncherAppState.getInstance(
                            previewContext).getModel().getWidgetsFilterDataProvider()) {

                @Override
                public void run() {
                    DeviceProfile deviceProfile = idp.getDeviceProfile(previewContext);
                    String query =
                            LauncherSettings.Favorites.SCREEN + " = " + Workspace.FIRST_SCREEN_ID
                                    + " or " + LauncherSettings.Favorites.CONTAINER + " = "
                                    + LauncherSettings.Favorites.CONTAINER_HOTSEAT;
                    if (deviceProfile.isTwoPanels) {
                        query += " or " + LauncherSettings.Favorites.SCREEN + " = "
                                + Workspace.SECOND_SCREEN_ID;
                    }
                    loadWorkspace(new ArrayList<>(), query, null, null);

                    final SparseArray<Size> spanInfo = getLoadedLauncherWidgetInfo();
                    MAIN_EXECUTOR.execute(() -> {
                        renderView(previewContext, mBgDataModel, mWidgetProvidersMap, spanInfo,
                                idp);
                        mLifeCycleTracker.add(previewContext::onDestroy);
                    });
                }
            }.run();
        } else {
            LauncherAppState.getInstance(inflationContext).getModel().loadAsync(dataModel -> {
                if (dataModel != null) {
                    MAIN_EXECUTOR.execute(() -> renderView(inflationContext, dataModel, null,
                            null, idp));
                } else {
                    Log.e(TAG, "Model loading failed");
                }
            });
        }
    }

    @UiThread
    private void renderView(Context inflationContext, BgDataModel dataModel,
            Map<ComponentKey, AppWidgetProviderInfo> widgetProviderInfoMap,
            @Nullable final SparseArray<Size> launcherWidgetSpanInfo, InvariantDeviceProfile idp) {
        if (mDestroyed) {
            return;
        }
        LauncherPreviewRenderer renderer;
        if (Flags.newCustomizationPickerUi()) {
            renderer = new LauncherPreviewRenderer(inflationContext, idp, mPreviewColorOverride,
                    mWallpaperColors, launcherWidgetSpanInfo);
        } else {
            renderer = new LauncherPreviewRenderer(inflationContext, idp,
                    mWallpaperColors, launcherWidgetSpanInfo);
        }
        renderer.hideBottomRow(mHideQsb);
        View view = renderer.getRenderedView(dataModel, widgetProviderInfoMap);
        // This aspect scales the view to fit in the surface and centers it
        final float scale = Math.min(mWidth / (float) view.getMeasuredWidth(),
                mHeight / (float) view.getMeasuredHeight());
        view.setScaleX(scale);
        view.setScaleY(scale);
        view.setPivotX(0);
        view.setPivotY(0);
        view.setTranslationX((mWidth - scale * view.getWidth()) / 2);
        view.setTranslationY((mHeight - scale * view.getHeight()) / 2);
        if (!Flags.newCustomizationPickerUi()) {
            view.setAlpha(0);
            view.animate().alpha(1)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .setDuration(FADE_IN_ANIMATION_DURATION)
                    .start();
            mSurfaceControlViewHost.setView(
                    view,
                    view.getMeasuredWidth(),
                    view.getMeasuredHeight()
            );
            return;
        }

        if (mViewRoot == null) {
            mViewRoot = new FrameLayout(inflationContext);
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, // Width
                    FrameLayout.LayoutParams.WRAP_CONTENT  // Height
            );
            mViewRoot.setLayoutParams(layoutParams);
            mViewRoot.addView(view);
            mViewRoot.setAlpha(0);
            mViewRoot.animate().alpha(1)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .setDuration(FADE_IN_ANIMATION_DURATION)
                    .start();
            mSurfaceControlViewHost.setView(
                    mViewRoot,
                    view.getMeasuredWidth(),
                    view.getMeasuredHeight()
            );
        } else  {
            mViewRoot.removeAllViews();
            mViewRoot.addView(view);
        }
    }

    private static class MySurfaceControlViewHost extends SurfaceControlViewHost {

        private final RunnableList mLifecycleTracker;

        MySurfaceControlViewHost(Context context, Display display, IBinder hostToken,
                RunnableList lifeCycleTracker) {
            super(context, display, hostToken);
            mLifecycleTracker = lifeCycleTracker;
            mLifecycleTracker.add(this::release);
        }

        @Override
        public void release() {
            super.release();
            // RunnableList ensures that the callback is only called once
            MAIN_EXECUTOR.execute(mLifecycleTracker::executeAllAndDestroy);
        }
    }

}
