/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_BACK_PREVIEW;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.hardware.HardwareBuffer;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.BackNavigationInfo;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

/**
 * Controller to handle actions related to the back gesture on the server side.
 */
class BackNavigationController {

    private static final String TAG = "BackNavigationController";
    private static final String BACK_PREDICTABILITY_PROP = "persist.debug.back_predictability";

    @Nullable
    private TaskSnapshotController mTaskSnapshotController;

    /**
     * Returns true if the back predictability feature is enabled
     */
    static boolean isEnabled() {
        return SystemProperties.getInt(BACK_PREDICTABILITY_PROP, 0) > 0;
    }

    /**
     * Set up the necessary leashes and build a {@link BackNavigationInfo} instance for an upcoming
     * back gesture animation.
     *
     * @param task the currently focused {@link Task}.
     * @return a {@link BackNavigationInfo} instance containing the required leashes and metadata
     * for the animation.
     */
    @Nullable
    BackNavigationInfo startBackNavigation(@NonNull Task task) {
        return startBackNavigation(task, null);
    }

    /**
     * @param tx, a transaction to be used for the attaching the animation leash.
     *            This is used in tests. If null, the object will be initialized with a new {@link
     *            android.view.SurfaceControl.Transaction}
     * @see #startBackNavigation(Task)
     */
    @VisibleForTesting
    @Nullable
    BackNavigationInfo startBackNavigation(@NonNull Task task,
            @Nullable SurfaceControl.Transaction tx) {

        if (tx == null) {
            tx = new SurfaceControl.Transaction();
        }

        int backType = BackNavigationInfo.TYPE_UNDEFINED;
        Task prevTask = task;
        ActivityRecord prev;
        WindowContainer<?> removedWindowContainer;
        ActivityRecord activityRecord;
        SurfaceControl animationLeashParent;
        WindowConfiguration taskWindowConfiguration;
        SurfaceControl animLeash;
        HardwareBuffer screenshotBuffer = null;
        int prevTaskId;
        int prevUserId;

        synchronized (task.mWmService.mGlobalLock) {
            activityRecord = task.topRunningActivity();
            removedWindowContainer = activityRecord;
            taskWindowConfiguration = task.getTaskInfo().configuration.windowConfiguration;

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "startBackNavigation task=%s, topRunningActivity=%s",
                    task, activityRecord);

            // IME is visible, back gesture will dismiss it, nothing to preview.
            if (task.getDisplayContent().getImeContainer().isVisible()) {
                return null;
            }

            // Current Activity is home, there is no previous activity to display
            if (activityRecord.isActivityTypeHome()) {
                return null;
            }

            prev = task.getActivity(
                    (r) -> !r.finishing && r.getTask() == task && !r.isTopRunningActivity());

            if (prev != null) {
                backType = BackNavigationInfo.TYPE_CROSS_ACTIVITY;
            } else if (task.returnsToHomeRootTask()) {
                prevTask = null;
                removedWindowContainer = task;
                backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
            } else if (activityRecord.isRootOfTask()) {
                // TODO(208789724): Create single source of truth for this, maybe in
                //  RootWindowContainer
                // TODO: Also check Task.shouldUpRecreateTaskLocked() for prev logic
                prevTask = task.mRootWindowContainer.getTaskBelow(task);
                removedWindowContainer = task;
                if (prevTask.isActivityTypeHome()) {
                    backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
                } else {
                    prev = prevTask.getTopNonFinishingActivity();
                    backType = BackNavigationInfo.TYPE_CROSS_TASK;
                }
            }

            prevTaskId = prevTask != null ? prevTask.mTaskId : 0;
            prevUserId = prevTask != null ? prevTask.mUserId : 0;

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Previous Activity is %s",
                    prev != null ? prev.mActivityComponent : null);

            //TODO(207481538) Remove once the infrastructure to support per-activity screenshot is
            // implemented. For now we simply have the mBackScreenshots hash map that dumbly
            // saves the screenshots.
            if (needsScreenshot(backType) && prev != null && prev.mActivityComponent != null) {
                screenshotBuffer = getActivitySnapshot(task, prev.mActivityComponent);
            }

            // Prepare a leash to animate the current top window
            animLeash = removedWindowContainer.makeAnimationLeash()
                    .setName("BackPreview Leash")
                    .setHidden(false)
                    .setBLASTLayer()
                    .build();
            removedWindowContainer.reparentSurfaceControl(tx, animLeash);

            animationLeashParent = removedWindowContainer.getAnimationLeashParent();
        }

        SurfaceControl.Builder builder = new SurfaceControl.Builder()
                .setName("BackPreview Screenshot")
                .setParent(animationLeashParent)
                .setHidden(false)
                .setBLASTLayer();
        SurfaceControl screenshotSurface = builder.build();

        // Find a screenshot of the previous activity

        if (needsScreenshot(backType) && prevTask != null) {
            if (screenshotBuffer == null) {
                screenshotBuffer = getTaskSnapshot(prevTaskId, prevUserId);
            }
        }
        tx.apply();

        WindowContainer<?> finalRemovedWindowContainer = removedWindowContainer;
        try {
            activityRecord.token.linkToDeath(
                    () -> resetSurfaces(finalRemovedWindowContainer), 0);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to link to death", e);
            resetSurfaces(removedWindowContainer);
            return null;
        }

        return new BackNavigationInfo(backType,
                animLeash,
                screenshotSurface,
                screenshotBuffer,
                taskWindowConfiguration,
                new RemoteCallback(result -> resetSurfaces(finalRemovedWindowContainer
                )));
    }


    private HardwareBuffer getActivitySnapshot(@NonNull Task task,
            ComponentName activityComponent) {
        // Check if we have a screenshot of the previous activity, indexed by its
        // component name.
        SurfaceControl.ScreenshotHardwareBuffer backBuffer = task.mBackScreenshots
                .get(activityComponent.flattenToString());
        return backBuffer != null ? backBuffer.getHardwareBuffer() : null;

    }

    private HardwareBuffer getTaskSnapshot(int taskId, int userId) {
        if (mTaskSnapshotController == null) {
            return null;
        }
        TaskSnapshot snapshot = mTaskSnapshotController.getSnapshot(taskId,
                userId, true /* restoreFromDisk */, false  /* isLowResolution */);
        return snapshot != null ? snapshot.getHardwareBuffer() : null;
    }

    private boolean needsScreenshot(int backType) {
        switch (backType) {
            case BackNavigationInfo.TYPE_RETURN_TO_HOME:
            case BackNavigationInfo.TYPE_DIALOG_CLOSE:
                return false;
        }
        return true;
    }

    private void resetSurfaces(@NonNull WindowContainer<?> windowContainer) {
        synchronized (windowContainer.mWmService.mGlobalLock) {
            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Back: Reset surfaces");
            SurfaceControl.Transaction tx = windowContainer.getSyncTransaction();
            SurfaceControl surfaceControl = windowContainer.getSurfaceControl();
            if (surfaceControl != null) {
                tx.reparent(surfaceControl,
                        windowContainer.getParent().getSurfaceControl());
                tx.apply();
            }
        }
    }

    void setTaskSnapshotController(@Nullable TaskSnapshotController taskSnapshotController) {
        mTaskSnapshotController = taskSnapshotController;
    }
}