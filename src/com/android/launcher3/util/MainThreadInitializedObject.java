/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.launcher3.util;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.android.launcher3.LauncherApplication;
import com.android.launcher3.util.ResourceBasedOverride.Overrides;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Utility class for defining singletons which are initiated on main thread.
 *
 * TODO(b/361850561): Do not delete MainThreadInitializedObject until we find a way to
 * unregister and understand how singleton objects are destroyed in dagger graph.
 */
public class MainThreadInitializedObject<T extends SafeCloseable> {

    private final ObjectProvider<T> mProvider;
    private T mValue;

    public MainThreadInitializedObject(ObjectProvider<T> provider) {
        mProvider = provider;
    }

    public T get(Context context) {
        Context app = context.getApplicationContext();
        if (app instanceof ObjectSandbox sc) {
            return sc.getObject(this);
        }

        if (mValue == null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mValue = TraceHelper.allowIpcs("main.thread.object", () -> mProvider.get(app));
            } else {
                try {
                    return MAIN_EXECUTOR.submit(() -> get(context)).get();
                } catch (InterruptedException|ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return mValue;
    }

    /**
     * Executes the callback is the value is already created
     * @return true if the callback was executed, false otherwise
     */
    public boolean executeIfCreated(Consumer<T> callback) {
        T v = mValue;
        if (v != null) {
            callback.accept(v);
            return true;
        } else {
            return false;
        }
    }

    @VisibleForTesting
    public void initializeForTesting(T value) {
        mValue = value;
    }

    /**
     * Initializes a provider based on resource overrides
     */
    public static <T extends ResourceBasedOverride & SafeCloseable> MainThreadInitializedObject<T>
            forOverride(Class<T> clazz, int resourceId) {
        return new MainThreadInitializedObject<>(c -> Overrides.getObject(clazz, c, resourceId));
    }

    public interface ObjectProvider<T> {

        T get(Context context);
    }

    /** Sandbox for isolating {@link MainThreadInitializedObject} instances from Launcher. */
    public interface ObjectSandbox {

        /**
         * Find a cached object from mObjectMap if we have already created one. If not, generate
         * an object using the provider.
         */
        <T extends SafeCloseable> T getObject(MainThreadInitializedObject<T> object);


        /**
         * Put a value into cache, can be used to put mocked MainThreadInitializedObject
         * instances.
         */
        <T extends SafeCloseable> void putObject(MainThreadInitializedObject<T> object, T value);

        /**
         * Returns whether this sandbox should cleanup all objects when its destroyed or leave it
         * to the GC.
         * These objects can have listeners attached to the system server and mey not be able to get
         * GCed themselves when running on a device.
         * Some environments like Robolectric tear down the whole system at the end of the test,
         * so manual cleanup may not be required.
         */
        default boolean shouldCleanUpOnDestroy() {
            return true;
        }

        @UiThread
        default <T extends SafeCloseable> T createObject(MainThreadInitializedObject<T> object) {
            return object.mProvider.get((Context) this);
        }
    }

    /**
     * Abstract Context which allows custom implementations for
     * {@link MainThreadInitializedObject} providers
     */
    public static class SandboxContext extends LauncherApplication implements ObjectSandbox {

        private static final String TAG = "SandboxContext";

        private final Map<MainThreadInitializedObject, Object> mObjectMap = new HashMap<>();
        private final ArrayList<SafeCloseable> mOrderedObjects = new ArrayList<>();

        private final Object mDestroyLock = new Object();
        private boolean mDestroyed = false;

        public SandboxContext(Context base) {
            attachBaseContext(base);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public boolean shouldCleanUpOnDestroy() {
            return (getBaseContext().getApplicationContext() instanceof ObjectSandbox os)
                    ? os.shouldCleanUpOnDestroy() : true;
        }

        public void onDestroy() {
            if (shouldCleanUpOnDestroy()) {
                cleanUpObjects();
            }
        }

        protected void cleanUpObjects() {
            getAppComponent().getDaggerSingletonTracker().close();
            synchronized (mDestroyLock) {
                // Destroy in reverse order
                for (int i = mOrderedObjects.size() - 1; i >= 0; i--) {
                    mOrderedObjects.get(i).close();
                }
                mDestroyed = true;
            }
        }

        @Override
        public <T extends SafeCloseable> T getObject(MainThreadInitializedObject<T> object) {
            synchronized (mDestroyLock) {
                if (mDestroyed) {
                    Log.e(TAG, "Static object access with a destroyed context");
                }
                T t = (T) mObjectMap.get(object);
                if (t != null) {
                    return t;
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    t = createObject(object);
                    mObjectMap.put(object, t);
                    mOrderedObjects.add(t);
                    return t;
                }
            }

            try {
                return MAIN_EXECUTOR.submit(() -> getObject(object)).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <T extends SafeCloseable> void putObject(
                MainThreadInitializedObject<T> object, T value) {
            mObjectMap.put(object, value);
        }
    }
}
