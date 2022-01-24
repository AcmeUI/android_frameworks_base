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

package android.window;

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.hardware.HardwareBuffer;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.view.SurfaceControl;

/**
 * Information to be sent to SysUI about a back event.
 *
 * @hide
 */
public final class BackNavigationInfo implements Parcelable {

    /**
     * The target of the back navigation is undefined.
     */
    public static final int TYPE_UNDEFINED = -1;

    /**
     * Navigating back will close the currently visible dialog
     */
    public static final int TYPE_DIALOG_CLOSE = 0;

    /**
     * Navigating back will bring the user back to the home screen
     */
    public static final int TYPE_RETURN_TO_HOME = 1;

    /**
     * Navigating back will bring the user to the previous activity in the same Task
     */
    public static final int TYPE_CROSS_ACTIVITY = 2;

    /**
     * Navigating back will bring the user to the previous activity in the previous Task
     */
    public static final int TYPE_CROSS_TASK = 3;

    /**
     * Defines the type of back destinations a back even can lead to. This is used to define the
     * type of animation that need to be run on SystemUI.
     */
    @IntDef(prefix = "TYPE_", value = {
            TYPE_UNDEFINED,
            TYPE_DIALOG_CLOSE,
            TYPE_RETURN_TO_HOME,
            TYPE_CROSS_ACTIVITY,
            TYPE_CROSS_TASK})
    @interface BackTargetType {
    }

    private final int mType;
    @Nullable
    private final SurfaceControl mDepartingWindowContainer;
    @Nullable
    private final SurfaceControl mScreenshotSurface;
    @Nullable
    private final HardwareBuffer mScreenshotBuffer;
    @Nullable
    private final RemoteCallback mRemoteCallback;
    @Nullable
    private final WindowConfiguration mTaskWindowConfiguration;

    /**
     * Create a new {@link BackNavigationInfo} instance.
     *
     * @param type  The {@link BackTargetType} of the destination (what will be displayed after
     *              the back action)
     * @param topWindowLeash      The leash to animate away the current topWindow. The consumer
     *                            of the leash is responsible for removing it.
     * @param screenshotSurface The screenshot of the previous activity to be displayed.
     * @param screenshotBuffer      A buffer containing a screenshot used to display the activity.
     *                            See {@link  #getScreenshotHardwareBuffer()} for information
     *                            about nullity.
     * @param taskWindowConfiguration The window configuration of the Task being animated
     *                            beneath.
     * @param onBackNavigationDone   The callback to be called once the client is done with the back
     *                           preview.
     */
    public BackNavigationInfo(@BackTargetType int type,
            @Nullable SurfaceControl topWindowLeash,
            @Nullable SurfaceControl screenshotSurface,
            @Nullable HardwareBuffer screenshotBuffer,
            @Nullable WindowConfiguration taskWindowConfiguration,
            @NonNull RemoteCallback onBackNavigationDone) {
        mType = type;
        mDepartingWindowContainer = topWindowLeash;
        mScreenshotSurface = screenshotSurface;
        mScreenshotBuffer = screenshotBuffer;
        mTaskWindowConfiguration = taskWindowConfiguration;
        mRemoteCallback = onBackNavigationDone;
    }

    private BackNavigationInfo(@NonNull Parcel in) {
        mType = in.readInt();
        mDepartingWindowContainer = in.readTypedObject(SurfaceControl.CREATOR);
        mScreenshotSurface = in.readTypedObject(SurfaceControl.CREATOR);
        mScreenshotBuffer = in.readTypedObject(HardwareBuffer.CREATOR);
        mTaskWindowConfiguration = in.readTypedObject(WindowConfiguration.CREATOR);
        mRemoteCallback = requireNonNull(in.readTypedObject(RemoteCallback.CREATOR));
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeTypedObject(mDepartingWindowContainer, flags);
        dest.writeTypedObject(mScreenshotSurface, flags);
        dest.writeTypedObject(mScreenshotBuffer, flags);
        dest.writeTypedObject(mTaskWindowConfiguration, flags);
        dest.writeTypedObject(mRemoteCallback, flags);
    }

    /**
     * Returns the type of back navigation that is about to happen.
     * @see BackTargetType
     */
    public @BackTargetType int getType() {
        return mType;
    }

    /**
     * Returns a leash to the top window container that needs to be animated. This can be null if
     * the back animation is controlled by the application.
     */
    @Nullable
    public SurfaceControl getDepartingWindowContainer() {
        return mDepartingWindowContainer;
    }

    /**
     *  Returns the {@link SurfaceControl} that should be used to display a screenshot of the
     *  previous activity.
     */
    @Nullable
    public SurfaceControl getScreenshotSurface() {
        return mScreenshotSurface;
    }

    /**
     * Returns the {@link HardwareBuffer} containing the screenshot the activity about to be
     * shown. This can be null if one of the following conditions is met:
     * <ul>
     *     <li>The screenshot is not available
     *     <li> The previous activity is the home screen ( {@link  #TYPE_RETURN_TO_HOME}
     *     <li> The current window is a dialog ({@link  #TYPE_DIALOG_CLOSE}
     *     <li> The back animation is controlled by the application
     * </ul>
     */
    @Nullable
    public HardwareBuffer getScreenshotHardwareBuffer() {
        return mScreenshotBuffer;
    }

    /**
     * Returns the {@link WindowConfiguration} of the current task. This is null when the top
     * application is controlling the back animation.
     */
    @Nullable
    public WindowConfiguration getTaskWindowConfiguration() {
        return mTaskWindowConfiguration;
    }

    /**
     * Callback to be called when the back preview is finished in order to notify the server that
     * it can clean up the resources created for the animation.
     */
    public void onBackNavigationFinished() {
        mRemoteCallback.sendResult(null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BackNavigationInfo> CREATOR = new Creator<BackNavigationInfo>() {
        @Override
        public BackNavigationInfo createFromParcel(Parcel in) {
            return new BackNavigationInfo(in);
        }

        @Override
        public BackNavigationInfo[] newArray(int size) {
            return new BackNavigationInfo[size];
        }
    };

    @Override
    public String toString() {
        return "BackNavigationInfo{"
                + "mType=" + typeToString(mType) + " (" + mType + ")"
                + ", mDepartingWindowContainer=" + mDepartingWindowContainer
                + ", mScreenshotSurface=" + mScreenshotSurface
                + ", mTaskWindowConfiguration= " + mTaskWindowConfiguration
                + ", mScreenshotBuffer=" + mScreenshotBuffer
                + ", mRemoteCallback=" + mRemoteCallback
                + '}';
    }

    /**
     * Translates the {@link BackNavigationInfo} integer type to its String representation
     */
    public static String typeToString(@BackTargetType int type) {
        switch (type) {
            case  TYPE_UNDEFINED:
                return "TYPE_UNDEFINED";
            case TYPE_DIALOG_CLOSE:
                return "TYPE_DIALOG_CLOSE";
            case TYPE_RETURN_TO_HOME:
                return "TYPE_RETURN_TO_HOME";
            case TYPE_CROSS_ACTIVITY:
                return "TYPE_CROSS_ACTIVITY";
            case TYPE_CROSS_TASK:
                return "TYPE_CROSS_TASK";
        }
        return String.valueOf(type);
    }
}