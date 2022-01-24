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

package android.media.tv.interactive;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.tv.AdRequest;
import android.media.tv.AdResponse;
import android.media.tv.BroadcastInfoRequest;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.TvContentRating;
import android.media.tv.TvInputManager;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.os.SomeArgs;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * The TvInteractiveAppService class represents a TV interactive applications RTE.
 */
public abstract class TvInteractiveAppService extends Service {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvInteractiveAppService";

    private static final int DETACH_MEDIA_VIEW_TIMEOUT_MS = 5000;

    // TODO: cleanup and unhide APIs.

    /**
     * This is the interface name that a service implementing a TV Interactive App service should
     * say that it supports -- that is, this is the action it uses for its intent filter. To be
     * supported, the service must also require the
     * android.Manifest.permission#BIND_TV_INTERACTIVE_APP permission so that other applications
     * cannot abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.media.tv.interactive.TvInteractiveAppService";

    /**
     * Name under which a TvInteractiveAppService component publishes information about itself. This
     * meta-data must reference an XML resource containing an
     * <code>&lt;{@link android.R.styleable#TvInteractiveAppService tv-interactive-app}&gt;</code>
     * tag.
     */
    public static final String SERVICE_META_DATA = "android.media.tv.interactive.app";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "INTERACTIVE_APP_SERVICE_COMMAND_TYPE_", value = {
            INTERACTIVE_APP_SERVICE_COMMAND_TYPE_TUNE,
            INTERACTIVE_APP_SERVICE_COMMAND_TYPE_TUNE_NEXT,
            INTERACTIVE_APP_SERVICE_COMMAND_TYPE_TUNE_PREV,
            INTERACTIVE_APP_SERVICE_COMMAND_TYPE_STOP,
            INTERACTIVE_APP_SERVICE_COMMAND_TYPE_SET_STREAM_VOLUME,
            INTERACTIVE_APP_SERVICE_COMMAND_TYPE_SELECT_TRACK
    })
    public @interface InteractiveAppServiceCommandType {}

    /** @hide */
    public static final String INTERACTIVE_APP_SERVICE_COMMAND_TYPE_TUNE = "tune";
    /** @hide */
    public static final String INTERACTIVE_APP_SERVICE_COMMAND_TYPE_TUNE_NEXT = "tune_next";
    /** @hide */
    public static final String INTERACTIVE_APP_SERVICE_COMMAND_TYPE_TUNE_PREV = "tune_previous";
    /** @hide */
    public static final String INTERACTIVE_APP_SERVICE_COMMAND_TYPE_STOP = "stop";
    /** @hide */
    public static final String INTERACTIVE_APP_SERVICE_COMMAND_TYPE_SET_STREAM_VOLUME =
            "set_stream_volume";
    /** @hide */
    public static final String INTERACTIVE_APP_SERVICE_COMMAND_TYPE_SELECT_TRACK = "select_track";
    /** @hide */
    public static final String COMMAND_PARAMETER_KEY_CHANNEL_URI = "command_channel_uri";
    /** @hide */
    public static final String COMMAND_PARAMETER_KEY_INPUT_ID = "command_input_id";
    /** @hide */
    public static final String COMMAND_PARAMETER_KEY_VOLUME = "command_volume";
    /** @hide */
    public static final String COMMAND_PARAMETER_KEY_TRACK_TYPE = "command_track_type";
    /** @hide */
    public static final String COMMAND_PARAMETER_KEY_TRACK_ID = "command_track_id";
    /** @hide */
    public static final String COMMAND_PARAMETER_KEY_TRACK_SELECT_MODE =
            "command_track_select_mode";
    /**
     * Command to quiet channel change. No channel banner or channel info is shown.
     * <p>Refer to HbbTV Spec 2.0.4 chapter A.2.4.3.
     * @hide
     */
    public static final String COMMAND_PARAMETER_KEY_CHANGE_CHANNEL_QUIETLY =
            "command_change_channel_quietly";

    private final Handler mServiceHandler = new ServiceHandler();
    private final RemoteCallbackList<ITvInteractiveAppServiceCallback> mCallbacks =
            new RemoteCallbackList<>();

    /** @hide */
    @Override
    public final IBinder onBind(Intent intent) {
        ITvInteractiveAppService.Stub tvIAppServiceBinder = new ITvInteractiveAppService.Stub() {
            @Override
            public void registerCallback(ITvInteractiveAppServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.register(cb);
                }
            }

            @Override
            public void unregisterCallback(ITvInteractiveAppServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.unregister(cb);
                }
            }

            @Override
            public void createSession(InputChannel channel, ITvInteractiveAppSessionCallback cb,
                    String iAppServiceId, int type) {
                if (cb == null) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = channel;
                args.arg2 = cb;
                args.arg3 = iAppServiceId;
                args.arg4 = type;
                mServiceHandler.obtainMessage(ServiceHandler.DO_CREATE_SESSION, args)
                        .sendToTarget();
            }

            @Override
            public void prepare(int type) {
                onPrepare(type);
            }

            @Override
            public void registerAppLinkInfo(AppLinkInfo appLinkInfo) {
                onRegisterAppLinkInfo(appLinkInfo);
            }

            @Override
            public void unregisterAppLinkInfo(AppLinkInfo appLinkInfo) {
                onUnregisterAppLinkInfo(appLinkInfo);
            }

            @Override
            public void sendAppLinkCommand(Bundle command) {
                onAppLinkCommand(command);
            }
        };
        return tvIAppServiceBinder;
    }

    /**
     * Prepares TV Interactive App service for the given type.
     * @hide
     */
    public void onPrepare(@TvInteractiveAppInfo.InteractiveAppType int type) {
    }

    /**
     * Registers App link info.
     * @hide
     */
    public void onRegisterAppLinkInfo(AppLinkInfo appLinkInfo) {
        // TODO: make it abstract when unhide
    }

    /**
     * Unregisters App link info.
     * @hide
     */
    public void onUnregisterAppLinkInfo(AppLinkInfo appLinkInfo) {
        // TODO: make it abstract when unhide
    }

    /**
     * Sends App link info.
     * @hide
     */
    public void onAppLinkCommand(Bundle command) {
        // TODO: make it abstract when unhide
    }


    /**
     * Returns a concrete implementation of {@link Session}.
     *
     * <p>May return {@code null} if this TV Interactive App service fails to create a session for
     * some reason.
     *
     * @param iAppServiceId The ID of the TV Interactive App associated with the session.
     * @param type The type of the TV Interactive App associated with the session.
     * @hide
     */
    @Nullable
    public Session onCreateSession(
            @NonNull String iAppServiceId,
            @TvInteractiveAppInfo.InteractiveAppType int type) {
        return null;
    }

    /**
     * Notifies the system when the state of the interactive app RTE has been changed.
     *
     * @param type the interactive app type
     * @param state the current state of the service of the given type
     * @param error the error code for error state. {@link TvInteractiveAppManager#ERROR_NONE} is
     *              used when the state is not
     *              {@link TvInteractiveAppManager#SERVICE_STATE_ERROR}.
     * @hide
     */
    public final void notifyStateChanged(
            @TvInteractiveAppInfo.InteractiveAppType int type,
            @TvInteractiveAppManager.ServiceState int state,
            @TvInteractiveAppManager.ErrorCode int error) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = type;
        args.arg2 = state;
        args.arg3 = error;
        mServiceHandler
                .obtainMessage(ServiceHandler.DO_NOTIFY_RTE_STATE_CHANGED, args).sendToTarget();
    }

    /**
     * Base class for derived classes to implement to provide a TV interactive app session.
     * @hide
     */
    public abstract static class Session implements KeyEvent.Callback {
        private final KeyEvent.DispatcherState mDispatcherState = new KeyEvent.DispatcherState();

        private final Object mLock = new Object();
        // @GuardedBy("mLock")
        private ITvInteractiveAppSessionCallback mSessionCallback;
        // @GuardedBy("mLock")
        private final List<Runnable> mPendingActions = new ArrayList<>();

        private final Context mContext;
        final Handler mHandler;
        private final WindowManager mWindowManager;
        private WindowManager.LayoutParams mWindowParams;
        private Surface mSurface;
        private FrameLayout mMediaViewContainer;
        private View mMediaView;
        private MediaViewCleanUpTask mMediaViewCleanUpTask;
        private boolean mMediaViewEnabled;
        private IBinder mWindowToken;
        private Rect mMediaFrame;

        /**
         * Creates a new Session.
         *
         * @param context The context of the application
         */
        public Session(@NonNull Context context) {
            mContext = context;
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mHandler = new Handler(context.getMainLooper());
        }

        /**
         * Enables or disables the media view.
         *
         * <p>By default, the media view is disabled. Must be called explicitly after the
         * session is created to enable the media view.
         *
         * <p>The TV Interactive App service can disable its media view when needed.
         *
         * @param enable {@code true} if you want to enable the media view. {@code false}
         *            otherwise.
         * @hide
         */
        public void setMediaViewEnabled(final boolean enable) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (enable == mMediaViewEnabled) {
                        return;
                    }
                    mMediaViewEnabled = enable;
                    if (enable) {
                        if (mWindowToken != null) {
                            createMediaView(mWindowToken, mMediaFrame);
                        }
                    } else {
                        removeMediaView(false);
                    }
                }
            });
        }

        /**
         * Starts TvInteractiveAppService session.
         */
        public void onStartInteractiveApp() {
        }

        /**
         * Stops TvInteractiveAppService session.
         */
        public void onStopInteractiveApp() {
        }

        /**
         * Resets TvIAppService session.
         * @hide
         */
        public void onResetInteractiveApp() {
        }

        /**
         * Creates broadcast-independent(BI) interactive application.
         *
         * @see #onDestroyBiInteractiveApp(String)
         * @hide
         */
        public void onCreateBiInteractiveApp(@NonNull Uri biIAppUri, @Nullable Bundle params) {
        }


        /**
         * Destroys broadcast-independent(BI) interactive application.
         *
         * @param biIAppId the BI interactive app ID from
         *        {@link #createBiInteractiveApp(Uri, Bundle)}
         *
         * @see #onCreateBiInteractiveApp(Uri, Bundle)
         * @hide
         */
        public void onDestroyBiInteractiveApp(@NonNull String biIAppId) {
        }

        /**
         * To toggle Digital Teletext Application if there is one in AIT app list.
         * @param enable
         * @hide
         */
        public void onSetTeletextAppEnabled(boolean enable) {
        }

        /**
         * Receives current channel URI.
         * @hide
         */
        public void onCurrentChannelUri(@Nullable Uri channelUri) {
        }

        /**
         * Receives logical channel number (LCN) of current channel.
         * @hide
         */
        public void onCurrentChannelLcn(int lcn) {
        }

        /**
         * Receives stream volume.
         * @hide
         */
        public void onStreamVolume(float volume) {
        }

        /**
         * Receives track list.
         * @hide
         */
        public void onTrackInfoList(@NonNull List<TvTrackInfo> tracks) {
        }

        /**
         * Receives current TV input ID.
         * @hide
         */
        public void onCurrentTvInputId(@Nullable String inputId) {
        }

        /**
         * Called when the application sets the surface.
         *
         * <p>The TV Interactive App service should render interactive app UI onto the given
         * surface. When called with {@code null}, the Interactive App service should immediately
         * free any references to the currently set surface and stop using it.
         *
         * @param surface The surface to be used for interactive app UI rendering. Can be
         *                {@code null}.
         * @return {@code true} if the surface was set successfully, {@code false} otherwise.
         */
        public abstract boolean onSetSurface(@Nullable Surface surface);

        /**
         * Called after any structural changes (format or size) have been made to the surface passed
         * in {@link #onSetSurface}. This method is always called at least once, after
         * {@link #onSetSurface} is called with non-null surface.
         *
         * @param format The new PixelFormat of the surface.
         * @param width The new width of the surface.
         * @param height The new height of the surface.
         */
        public void onSurfaceChanged(int format, int width, int height) {
        }

        /**
         * Called when the size of the media view is changed by the application.
         *
         * <p>This is always called at least once when the session is created regardless of whether
         * the media view is enabled or not. The media view container size is the same as the
         * containing {@link TvInteractiveAppView}. Note that the size of the underlying surface can
         * be different if the surface was changed by calling {@link #layoutSurface}.
         *
         * @param width The width of the media view.
         * @param height The height of the media view.
         * @hide
         */
        public void onMediaViewSizeChanged(int width, int height) {
        }

        /**
         * Called when the application requests to create an media view. Each session
         * implementation can override this method and return its own view.
         *
         * @return a view attached to the media window
         * @hide
         */
        @Nullable
        public View onCreateMediaView() {
            return null;
        }

        /**
         * Releases TvInteractiveAppService session.
         * @hide
         */
        public void onRelease() {
        }

        /**
         * Called when the corresponding TV input tuned to a channel.
         * @hide
         */
        public void onTuned(@NonNull Uri channelUri) {
        }

        /**
         * Called when the corresponding TV input selected to a track.
         * @hide
         */
        public void onTrackSelected(int type, String trackId) {
        }

        /**
         * Called when the tracks are changed.
         * @hide
         */
        public void onTracksChanged(List<TvTrackInfo> tracks) {
        }

        /**
         * Called when video is available.
         * @hide
         */
        public void onVideoAvailable() {
        }

        /**
         * Called when video is unavailable.
         * @hide
         */
        public void onVideoUnavailable(int reason) {
        }

        /**
         * Called when content is allowed.
         * @hide
         */
        public void onContentAllowed() {
        }

        /**
         * Called when content is blocked.
         * @hide
         */
        public void onContentBlocked(TvContentRating rating) {
        }

        /**
         * Called when signal strength is changed.
         * @hide
         */
        public void onSignalStrength(@TvInputManager.SignalStrength int strength) {
        }

        /**
         * Called when a broadcast info response is received.
         * @hide
         */
        public void onBroadcastInfoResponse(@NonNull BroadcastInfoResponse response) {
        }

        /**
         * Called when an advertisement response is received.
         * @hide
         */
        public void onAdResponse(AdResponse response) {
        }

        /**
         * TODO: JavaDoc of APIs related to input events.
         * @hide
         */
        @Override
        public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
            return false;
        }

        /**
         * @hide
         */
        @Override
        public boolean onKeyLongPress(int keyCode, @NonNull KeyEvent event) {
            return false;
        }

        /**
         * @hide
         */
        @Override
        public boolean onKeyMultiple(int keyCode, int count, @NonNull KeyEvent event) {
            return false;
        }

        /**
         * @hide
         */
        @Override
        public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
            return false;
        }

        /**
         * @hide
         */
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            return false;
        }

        /**
         * @hide
         */
        public boolean onTrackballEvent(@NonNull MotionEvent event) {
            return false;
        }

        /**
         * @hide
         */
        public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
            return false;
        }

        /**
         * Assigns a size and position to the surface passed in {@link #onSetSurface}. The position
         * is relative to the overlay view that sits on top of this surface.
         *
         * @param left Left position in pixels, relative to the overlay view.
         * @param top Top position in pixels, relative to the overlay view.
         * @param right Right position in pixels, relative to the overlay view.
         * @param bottom Bottom position in pixels, relative to the overlay view.
         */
        public void layoutSurface(final int left, final int top, final int right,
                final int bottom) {
            if (left > right || top > bottom) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "layoutSurface (l=" + left + ", t=" + top
                                    + ", r=" + right + ", b=" + bottom + ",)");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onLayoutSurface(left, top, right, bottom);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in layoutSurface", e);
                    }
                }
            });
        }

        /**
         * Requests broadcast related information from the related TV input.
         * @param request the request for broadcast info
         * @hide
         */
        public void requestBroadcastInfo(@NonNull final BroadcastInfoRequest request) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestBroadcastInfo (requestId="
                                    + request.getRequestId() + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onBroadcastInfoRequest(request);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestBroadcastInfo", e);
                    }
                }
            });
        }

        /**
         * Remove broadcast information request from the related TV input.
         * @param requestId the ID of the request
         * @hide
         */
        public void removeBroadcastInfo(final int requestId) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "removeBroadcastInfo (requestId="
                                    + requestId + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRemoveBroadcastInfo(requestId);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in removeBroadcastInfo", e);
                    }
                }
            });
        }

        /**
         * requests a specific command to be processed by the related TV input.
         * @param cmdType type of the specific command
         * @param parameters parameters of the specific command
         * @hide
         */
        public void requestCommand(
                @InteractiveAppServiceCommandType String cmdType, Bundle parameters) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestCommand (cmdType=" + cmdType + ", parameters="
                                    + parameters.toString() + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onCommandRequest(cmdType, parameters);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestCommand", e);
                    }
                }
            });
        }

        /**
         * Sets broadcast video bounds.
         * @hide
         */
        public void setVideoBounds(Rect rect) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "setVideoBounds (rect=" + rect + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onSetVideoBounds(rect);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in setVideoBounds", e);
                    }
                }
            });
        }

        /**
         * Requests the URI of the current channel.
         * @hide
         */
        public void requestCurrentChannelUri() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestCurrentChannelUri");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestCurrentChannelUri();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestCurrentChannelUri", e);
                    }
                }
            });
        }

        /**
         * Requests the logic channel number (LCN) of the current channel.
         * @hide
         */
        public void requestCurrentChannelLcn() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestCurrentChannelLcn");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestCurrentChannelLcn();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestCurrentChannelLcn", e);
                    }
                }
            });
        }

        /**
         * Requests stream volume.
         * @hide
         */
        public void requestStreamVolume() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestStreamVolume");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestStreamVolume();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestStreamVolume", e);
                    }
                }
            });
        }

        /**
         * Requests the list of {@link TvTrackInfo}.
         * @hide
         */
        public void requestTrackInfoList() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestTrackInfoList");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestTrackInfoList();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestTrackInfoList", e);
                    }
                }
            });
        }

        /**
         * Requests current TV input ID.
         *
         * @see android.media.tv.TvInputInfo
         * @hide
         */
        public void requestCurrentTvInputId() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestCurrentTvInputId");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestCurrentTvInputId();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestCurrentTvInputId", e);
                    }
                }
            });
        }

        /**
         * requests an advertisement request to be processed by the related TV input.
         * @param request advertisement request
         * @hide
         */
        public void requestAd(@NonNull final AdRequest request) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestAd (id=" + request.getId() + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onAdRequest(request);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestAd", e);
                    }
                }
            });
        }

        void startInteractiveApp() {
            onStartInteractiveApp();
        }

        void stopInteractiveApp() {
            onStopInteractiveApp();
        }

        void resetInteractiveApp() {
            onResetInteractiveApp();
        }

        void createBiInteractiveApp(@NonNull Uri biIAppUri, @Nullable Bundle params) {
            onCreateBiInteractiveApp(biIAppUri, params);
        }

        void destroyBiInteractiveApp(@NonNull String biIAppId) {
            onDestroyBiInteractiveApp(biIAppId);
        }

        void setTeletextAppEnabled(boolean enable) {
            onSetTeletextAppEnabled(enable);
        }

        void sendCurrentChannelUri(@Nullable Uri channelUri) {
            onCurrentChannelUri(channelUri);
        }

        void sendCurrentChannelLcn(int lcn) {
            onCurrentChannelLcn(lcn);
        }

        void sendStreamVolume(float volume) {
            onStreamVolume(volume);
        }

        void sendTrackInfoList(@NonNull List<TvTrackInfo> tracks) {
            onTrackInfoList(tracks);
        }

        void sendCurrentTvInputId(@Nullable String inputId) {
            onCurrentTvInputId(inputId);
        }

        void release() {
            onRelease();
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            synchronized (mLock) {
                mSessionCallback = null;
                mPendingActions.clear();
            }
            // Removes the media view lastly so that any hanging on the main thread can be handled
            // in {@link #scheduleMediaViewCleanup}.
            removeMediaView(true);
        }

        void notifyTuned(Uri channelUri) {
            if (DEBUG) {
                Log.d(TAG, "notifyTuned (channelUri=" + channelUri + ")");
            }
            onTuned(channelUri);
        }

        void notifyTrackSelected(int type, String trackId) {
            if (DEBUG) {
                Log.d(TAG, "notifyTrackSelected (type=" + type + "trackId=" + trackId + ")");
            }
            onTrackSelected(type, trackId);
        }

        void notifyTracksChanged(List<TvTrackInfo> tracks) {
            if (DEBUG) {
                Log.d(TAG, "notifyTracksChanged (tracks=" + tracks + ")");
            }
            onTracksChanged(tracks);
        }

        void notifyVideoAvailable() {
            if (DEBUG) {
                Log.d(TAG, "notifyVideoAvailable");
            }
            onVideoAvailable();
        }

        void notifyVideoUnavailable(int reason) {
            if (DEBUG) {
                Log.d(TAG, "notifyVideoAvailable (reason=" + reason + ")");
            }
            onVideoUnavailable(reason);
        }

        void notifyContentAllowed() {
            if (DEBUG) {
                Log.d(TAG, "notifyContentAllowed");
            }
            notifyContentAllowed();
        }

        void notifyContentBlocked(TvContentRating rating) {
            if (DEBUG) {
                Log.d(TAG, "notifyContentBlocked (rating=" + rating.flattenToString() + ")");
            }
            onContentBlocked(rating);
        }

        void notifySignalStrength(int strength) {
            if (DEBUG) {
                Log.d(TAG, "notifySignalStrength (strength=" + strength + ")");
            }
            onSignalStrength(strength);
        }

        /**
         * Calls {@link #onBroadcastInfoResponse}.
         */
        void notifyBroadcastInfoResponse(BroadcastInfoResponse response) {
            if (DEBUG) {
                Log.d(TAG, "notifyBroadcastInfoResponse (requestId="
                        + response.getRequestId() + ")");
            }
            onBroadcastInfoResponse(response);
        }

        /**
         * Calls {@link #onAdResponse}.
         */
        void notifyAdResponse(AdResponse response) {
            if (DEBUG) {
                Log.d(TAG, "notifyAdResponse (requestId=" + response.getId() + ")");
            }
            onAdResponse(response);
        }

        /**
         * Notifies when the session state is changed.
         *
         * @param state the current session state.
         * @param err the error code for error state. {@link TvInteractiveAppManager#ERROR_NONE} is
         *            used when the state is not
         *            {@link TvInteractiveAppManager#INTERACTIVE_APP_STATE_ERROR}.
         */
        public void notifySessionStateChanged(
                @TvInteractiveAppManager.InteractiveAppState int state,
                @TvInteractiveAppManager.ErrorCode int err) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "notifySessionStateChanged (state="
                                    + state + "; err=" + err + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onSessionStateChanged(state, err);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifySessionStateChanged", e);
                    }
                }
            });
        }

        /**
         * Notifies the broadcast-independent(BI) interactive application has been created.
         * @param biIAppId BI interactive app ID, which can be used to destroy the BI interactive
         *                 app.
         * @hide
         */
        public final void notifyBiInteractiveAppCreated(Uri biIAppUri, String biIAppId) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "notifyBiInteractiveAppCreated (biIAppId="
                                    + biIAppId + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onBiInteractiveAppCreated(biIAppUri, biIAppId);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyBiInteractiveAppCreated", e);
                    }
                }
            });
        }

        /**
         * Notifies when the digital teletext app state is changed.
         * @param state the current state.
         * @hide
         */
        public final void notifyTeletextAppStateChanged(
                @TvInteractiveAppManager.TeletextAppState int state) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "notifyTeletextAppState (state="
                                    + state + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onTeletextAppStateChanged(state);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTeletextAppState", e);
                    }
                }
            });
        }

        /**
         * Takes care of dispatching incoming input events and tells whether the event was handled.
         */
        int dispatchInputEvent(InputEvent event, InputEventReceiver receiver) {
            if (DEBUG) Log.d(TAG, "dispatchInputEvent(" + event + ")");
            if (event instanceof KeyEvent) {
                KeyEvent keyEvent = (KeyEvent) event;
                if (keyEvent.dispatch(this, mDispatcherState, this)) {
                    return TvInteractiveAppManager.Session.DISPATCH_HANDLED;
                }

                // TODO: special handlings of navigation keys and media keys
            } else if (event instanceof MotionEvent) {
                MotionEvent motionEvent = (MotionEvent) event;
                final int source = motionEvent.getSource();
                if (motionEvent.isTouchEvent()) {
                    if (onTouchEvent(motionEvent)) {
                        return TvInteractiveAppManager.Session.DISPATCH_HANDLED;
                    }
                } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    if (onTrackballEvent(motionEvent)) {
                        return TvInteractiveAppManager.Session.DISPATCH_HANDLED;
                    }
                } else {
                    if (onGenericMotionEvent(motionEvent)) {
                        return TvInteractiveAppManager.Session.DISPATCH_HANDLED;
                    }
                }
            }
            // TODO: handle overlay view
            return TvInteractiveAppManager.Session.DISPATCH_NOT_HANDLED;
        }

        private void initialize(ITvInteractiveAppSessionCallback callback) {
            synchronized (mLock) {
                mSessionCallback = callback;
                for (Runnable runnable : mPendingActions) {
                    runnable.run();
                }
                mPendingActions.clear();
            }
        }

        /**
         * Calls {@link #onSetSurface}.
         */
        void setSurface(Surface surface) {
            onSetSurface(surface);
            if (mSurface != null) {
                mSurface.release();
            }
            mSurface = surface;
            // TODO: Handle failure.
        }

        /**
         * Calls {@link #onSurfaceChanged}.
         */
        void dispatchSurfaceChanged(int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "dispatchSurfaceChanged(format=" + format + ", width=" + width
                        + ", height=" + height + ")");
            }
            onSurfaceChanged(format, width, height);
        }

        private void executeOrPostRunnableOnMainThread(Runnable action) {
            synchronized (mLock) {
                if (mSessionCallback == null) {
                    // The session is not initialized yet.
                    mPendingActions.add(action);
                } else {
                    if (mHandler.getLooper().isCurrentThread()) {
                        action.run();
                    } else {
                        // Posts the runnable if this is not called from the main thread
                        mHandler.post(action);
                    }
                }
            }
        }

        /**
         * Creates an media view. This calls {@link #onCreateMediaView} to get a view to attach
         * to the media window.
         *
         * @param windowToken A window token of the application.
         * @param frame A position of the media view.
         */
        void createMediaView(IBinder windowToken, Rect frame) {
            if (mMediaViewContainer != null) {
                removeMediaView(false);
            }
            if (DEBUG) Log.d(TAG, "create media view(" + frame + ")");
            mWindowToken = windowToken;
            mMediaFrame = frame;
            onMediaViewSizeChanged(frame.right - frame.left, frame.bottom - frame.top);
            if (!mMediaViewEnabled) {
                return;
            }
            mMediaView = onCreateMediaView();
            if (mMediaView == null) {
                return;
            }
            if (mMediaViewCleanUpTask != null) {
                mMediaViewCleanUpTask.cancel(true);
                mMediaViewCleanUpTask = null;
            }
            // Creates a container view to check hanging on the media view detaching.
            // Adding/removing the media view to/from the container make the view attach/detach
            // logic run on the main thread.
            mMediaViewContainer = new FrameLayout(mContext.getApplicationContext());
            mMediaViewContainer.addView(mMediaView);

            int type = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
            // We make the overlay view non-focusable and non-touchable so that
            // the application that owns the window token can decide whether to consume or
            // dispatch the input events.
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            if (ActivityManager.isHighEndGfx()) {
                flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            }
            mWindowParams = new WindowManager.LayoutParams(
                    frame.right - frame.left, frame.bottom - frame.top,
                    frame.left, frame.top, type, flags, PixelFormat.TRANSPARENT);
            mWindowParams.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
            mWindowParams.gravity = Gravity.START | Gravity.TOP;
            mWindowParams.token = windowToken;
            mWindowManager.addView(mMediaViewContainer, mWindowParams);
        }

        /**
         * Relayouts the current media view.
         *
         * @param frame A new position of the media view.
         */
        void relayoutMediaView(Rect frame) {
            if (DEBUG) Log.d(TAG, "relayoutMediaView(" + frame + ")");
            if (mMediaFrame == null || mMediaFrame.width() != frame.width()
                    || mMediaFrame.height() != frame.height()) {
                // Note: relayoutMediaView is called whenever TvInteractiveAppView's layout is
                // changed regardless of setMediaViewEnabled.
                onMediaViewSizeChanged(frame.right - frame.left, frame.bottom - frame.top);
            }
            mMediaFrame = frame;
            if (!mMediaViewEnabled || mMediaViewContainer == null) {
                return;
            }
            mWindowParams.x = frame.left;
            mWindowParams.y = frame.top;
            mWindowParams.width = frame.right - frame.left;
            mWindowParams.height = frame.bottom - frame.top;
            mWindowManager.updateViewLayout(mMediaViewContainer, mWindowParams);
        }

        /**
         * Removes the current media view.
         */
        void removeMediaView(boolean clearWindowToken) {
            if (DEBUG) Log.d(TAG, "removeMediaView(" + mMediaViewContainer + ")");
            if (clearWindowToken) {
                mWindowToken = null;
                mMediaFrame = null;
            }
            if (mMediaViewContainer != null) {
                // Removes the media view from the view hierarchy in advance so that it can be
                // cleaned up in the {@link MediaViewCleanUpTask} if the remove process is
                // hanging.
                mMediaViewContainer.removeView(mMediaView);
                mMediaView = null;
                mWindowManager.removeView(mMediaViewContainer);
                mMediaViewContainer = null;
                mWindowParams = null;
            }
        }

        /**
         * Schedules a task which checks whether the media view is detached and kills the process
         * if it is not. Note that this method is expected to be called in a non-main thread.
         */
        void scheduleMediaViewCleanup() {
            View mediaViewParent = mMediaViewContainer;
            if (mediaViewParent != null) {
                mMediaViewCleanUpTask = new MediaViewCleanUpTask();
                mMediaViewCleanUpTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                        mediaViewParent);
            }
        }
    }

    private static final class MediaViewCleanUpTask extends AsyncTask<View, Void, Void> {
        @Override
        protected Void doInBackground(View... views) {
            View mediaViewParent = views[0];
            try {
                Thread.sleep(DETACH_MEDIA_VIEW_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return null;
            }
            if (isCancelled()) {
                return null;
            }
            if (mediaViewParent.isAttachedToWindow()) {
                Log.e(TAG, "Time out on releasing media view. Killing "
                        + mediaViewParent.getContext().getPackageName());
                android.os.Process.killProcess(Process.myPid());
            }
            return null;
        }
    }

    /**
     * Implements the internal ITvInteractiveAppSession interface.
     * @hide
     */
    public static class ITvInteractiveAppSessionWrapper extends ITvInteractiveAppSession.Stub {
        // TODO: put ITvInteractiveAppSessionWrapper in a separate Java file
        private final Session mSessionImpl;
        private InputChannel mChannel;
        private TvInteractiveAppEventReceiver mReceiver;

        public ITvInteractiveAppSessionWrapper(
                Context context, Session mSessionImpl, InputChannel channel) {
            this.mSessionImpl = mSessionImpl;
            mChannel = channel;
            if (channel != null) {
                mReceiver = new TvInteractiveAppEventReceiver(channel, context.getMainLooper());
            }
        }

        @Override
        public void startInteractiveApp() {
            mSessionImpl.startInteractiveApp();
        }

        @Override
        public void stopInteractiveApp() {
            mSessionImpl.stopInteractiveApp();
        }

        @Override
        public void resetInteractiveApp() {
            mSessionImpl.resetInteractiveApp();
        }

        @Override
        public void createBiInteractiveApp(@NonNull Uri biIAppUri, @Nullable Bundle params) {
            mSessionImpl.createBiInteractiveApp(biIAppUri, params);
        }

        @Override
        public void setTeletextAppEnabled(boolean enable) {
            mSessionImpl.setTeletextAppEnabled(enable);
        }

        @Override
        public void destroyBiInteractiveApp(@NonNull String biIAppId) {
            mSessionImpl.destroyBiInteractiveApp(biIAppId);
        }

        @Override
        public void sendCurrentChannelUri(@Nullable Uri channelUri) {
            mSessionImpl.sendCurrentChannelUri(channelUri);
        }

        @Override
        public void sendCurrentChannelLcn(int lcn) {
            mSessionImpl.sendCurrentChannelLcn(lcn);
        }

        @Override
        public void sendStreamVolume(float volume) {
            mSessionImpl.sendStreamVolume(volume);
        }

        @Override
        public void sendTrackInfoList(@NonNull List<TvTrackInfo> tracks) {
            mSessionImpl.sendTrackInfoList(tracks);
        }

        @Override
        public void sendCurrentTvInputId(@Nullable String inputId) {
            mSessionImpl.sendCurrentTvInputId(inputId);
        }

        @Override
        public void release() {
            mSessionImpl.scheduleMediaViewCleanup();
            mSessionImpl.release();
        }

        @Override
        public void notifyTuned(Uri channelUri) {
            mSessionImpl.notifyTuned(channelUri);
        }

        @Override
        public void notifyTrackSelected(int type, final String trackId) {
            mSessionImpl.notifyTrackSelected(type, trackId);
        }

        @Override
        public void notifyTracksChanged(List<TvTrackInfo> tracks) {
            mSessionImpl.notifyTracksChanged(tracks);
        }

        @Override
        public void notifyVideoAvailable() {
            mSessionImpl.notifyVideoAvailable();
        }

        @Override
        public void notifyVideoUnavailable(int reason) {
            mSessionImpl.notifyVideoUnavailable(reason);
        }

        @Override
        public void notifyContentAllowed() {
            mSessionImpl.notifyContentAllowed();
        }

        @Override
        public void notifyContentBlocked(String rating) {
            mSessionImpl.notifyContentBlocked(TvContentRating.unflattenFromString(rating));
        }

        @Override
        public void notifySignalStrength(int strength) {
            mSessionImpl.notifySignalStrength(strength);
        }

        @Override
        public void setSurface(Surface surface) {
            mSessionImpl.setSurface(surface);
        }

        @Override
        public void dispatchSurfaceChanged(int format, int width, int height) {
            mSessionImpl.dispatchSurfaceChanged(format, width, height);
        }

        @Override
        public void notifyBroadcastInfoResponse(BroadcastInfoResponse response) {
            mSessionImpl.notifyBroadcastInfoResponse(response);
        }

        @Override
        public void notifyAdResponse(AdResponse response) {
            mSessionImpl.notifyAdResponse(response);
        }

        @Override
        public void createMediaView(IBinder windowToken, Rect frame) {
            mSessionImpl.createMediaView(windowToken, frame);
        }

        @Override
        public void relayoutMediaView(Rect frame) {
            mSessionImpl.relayoutMediaView(frame);
        }

        @Override
        public void removeMediaView() {
            mSessionImpl.removeMediaView(true);
        }

        private final class TvInteractiveAppEventReceiver extends InputEventReceiver {
            TvInteractiveAppEventReceiver(InputChannel inputChannel, Looper looper) {
                super(inputChannel, looper);
            }

            @Override
            public void onInputEvent(InputEvent event) {
                if (mSessionImpl == null) {
                    // The session has been finished.
                    finishInputEvent(event, false);
                    return;
                }

                int handled = mSessionImpl.dispatchInputEvent(event, this);
                if (handled != TvInteractiveAppManager.Session.DISPATCH_IN_PROGRESS) {
                    finishInputEvent(
                            event, handled == TvInteractiveAppManager.Session.DISPATCH_HANDLED);
                }
            }
        }
    }

    @SuppressLint("HandlerLeak")
    private final class ServiceHandler extends Handler {
        private static final int DO_CREATE_SESSION = 1;
        private static final int DO_NOTIFY_SESSION_CREATED = 2;
        private static final int DO_NOTIFY_RTE_STATE_CHANGED = 3;

        private void broadcastRteStateChanged(int type, int state, int error) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    mCallbacks.getBroadcastItem(i).onStateChanged(type, state, error);
                } catch (RemoteException e) {
                    Log.e(TAG, "error in broadcastRteStateChanged", e);
                }
            }
            mCallbacks.finishBroadcast();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DO_CREATE_SESSION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    InputChannel channel = (InputChannel) args.arg1;
                    ITvInteractiveAppSessionCallback cb =
                            (ITvInteractiveAppSessionCallback) args.arg2;
                    String iAppServiceId = (String) args.arg3;
                    int type = (int) args.arg4;
                    args.recycle();
                    Session sessionImpl = onCreateSession(iAppServiceId, type);
                    if (sessionImpl == null) {
                        try {
                            // Failed to create a session.
                            cb.onSessionCreated(null);
                        } catch (RemoteException e) {
                            Log.e(TAG, "error in onSessionCreated", e);
                        }
                        return;
                    }
                    ITvInteractiveAppSession stub = new ITvInteractiveAppSessionWrapper(
                            TvInteractiveAppService.this, sessionImpl, channel);

                    SomeArgs someArgs = SomeArgs.obtain();
                    someArgs.arg1 = sessionImpl;
                    someArgs.arg2 = stub;
                    someArgs.arg3 = cb;
                    mServiceHandler.obtainMessage(ServiceHandler.DO_NOTIFY_SESSION_CREATED,
                            someArgs).sendToTarget();
                    return;
                }
                case DO_NOTIFY_SESSION_CREATED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Session sessionImpl = (Session) args.arg1;
                    ITvInteractiveAppSession stub = (ITvInteractiveAppSession) args.arg2;
                    ITvInteractiveAppSessionCallback cb =
                            (ITvInteractiveAppSessionCallback) args.arg3;
                    try {
                        cb.onSessionCreated(stub);
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onSessionCreated", e);
                    }
                    if (sessionImpl != null) {
                        sessionImpl.initialize(cb);
                    }
                    args.recycle();
                    return;
                }
                case DO_NOTIFY_RTE_STATE_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    int type = (int) args.arg1;
                    int state = (int) args.arg2;
                    int error = (int) args.arg3;
                    broadcastRteStateChanged(type, state, error);
                    return;
                }
                default: {
                    Log.w(TAG, "Unhandled message code: " + msg.what);
                    return;
                }
            }
        }

    }
}