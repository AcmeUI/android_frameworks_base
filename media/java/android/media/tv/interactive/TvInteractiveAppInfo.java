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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is used to specify meta information of a TV interactive app.
 * @hide
 */
public final class TvInteractiveAppInfo implements Parcelable {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvInteractiveAppInfo";

    private static final String XML_START_TAG_NAME = "tv-interactive-app";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "INTERACTIVE_APP_TYPE_" }, value = {
            INTERACTIVE_APP_TYPE_HBBTV,
            INTERACTIVE_APP_TYPE_ATSC,
            INTERACTIVE_APP_TYPE_GINGA,
    })
    @interface InteractiveAppType {}

    /** HbbTV interactive app type */
    public static final int INTERACTIVE_APP_TYPE_HBBTV = 0x1;
    /** ATSC interactive app type */
    public static final int INTERACTIVE_APP_TYPE_ATSC = 0x2;
    /** Ginga interactive app type */
    public static final int INTERACTIVE_APP_TYPE_GINGA = 0x4;

    private final ResolveInfo mService;
    private final String mId;
    private int mTypes;

    public TvInteractiveAppInfo(@NonNull Context context, @NonNull ComponentName component) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null.");
        }
        Intent intent =
                new Intent(TvIAppService.SERVICE_INTERFACE).setComponent(component);
        ResolveInfo resolveInfo = context.getPackageManager().resolveService(intent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        if (resolveInfo == null) {
            throw new IllegalArgumentException("Invalid component. Can't find the service.");
        }

        ComponentName componentName = new ComponentName(resolveInfo.serviceInfo.packageName,
                resolveInfo.serviceInfo.name);
        String id;
        id = generateInteractiveAppServiceId(componentName);
        List<String> types = new ArrayList<>();
        parseServiceMetadata(resolveInfo, context, types);

        mService = resolveInfo;
        mId = id;
        mTypes = toTypesFlag(types);
    }
    private TvInteractiveAppInfo(ResolveInfo service, String id, int types) {
        mService = service;
        mId = id;
        mTypes = types;
    }

    private TvInteractiveAppInfo(@NonNull Parcel in) {
        mService = ResolveInfo.CREATOR.createFromParcel(in);
        mId = in.readString();
        mTypes = in.readInt();
    }

    public static final @NonNull Creator<TvInteractiveAppInfo> CREATOR =
            new Creator<TvInteractiveAppInfo>() {
                @Override
                public TvInteractiveAppInfo createFromParcel(Parcel in) {
                    return new TvInteractiveAppInfo(in);
                }

                @Override
                public TvInteractiveAppInfo[] newArray(int size) {
                    return new TvInteractiveAppInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mService.writeToParcel(dest, flags);
        dest.writeString(mId);
        dest.writeInt(mTypes);
    }

    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Returns the component of the TV Interactive App service.
     * @hide
     */
    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName, mService.serviceInfo.name);
    }

    /**
     * Returns the information of the service that implements this TV Interactive App service.
     */
    @Nullable
    public ServiceInfo getServiceInfo() {
        return mService.serviceInfo;
    }

    /**
     * Gets supported interactive app types
     */
    @InteractiveAppType
    @NonNull
    public int getSupportedTypes() {
        return mTypes;
    }

    private static String generateInteractiveAppServiceId(ComponentName name) {
        return name.flattenToShortString();
    }

    private static void parseServiceMetadata(
            ResolveInfo resolveInfo, Context context, List<String> types) {
        ServiceInfo si = resolveInfo.serviceInfo;
        PackageManager pm = context.getPackageManager();
        try (XmlResourceParser parser =
                     si.loadXmlMetaData(pm, TvIAppService.SERVICE_META_DATA)) {
            if (parser == null) {
                throw new IllegalStateException(
                        "No " + TvIAppService.SERVICE_META_DATA
                        + " meta-data found for " + si.name);
            }

            Resources res = pm.getResourcesForApplication(si.applicationInfo);
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
                // move to the START_TAG
            }

            String nodeName = parser.getName();
            if (!XML_START_TAG_NAME.equals(nodeName)) {
                throw new IllegalStateException("Meta-data does not start with "
                        + XML_START_TAG_NAME + " tag for " + si.name);
            }

            TypedArray sa = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.TvIAppService);
            CharSequence[] textArr = sa.getTextArray(
                    com.android.internal.R.styleable.TvIAppService_supportedTypes);
            for (CharSequence cs : textArr) {
                types.add(cs.toString().toLowerCase());
            }

            sa.recycle();
        } catch (IOException | XmlPullParserException e) {
            throw new IllegalStateException(
                    "Failed reading meta-data for " + si.packageName, e);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("No resources found for " + si.packageName, e);
        }
    }

    private static int toTypesFlag(List<String> types) {
        int flag = 0;
        for (String type : types) {
            switch (type) {
                case "hbbtv":
                    flag |= INTERACTIVE_APP_TYPE_HBBTV;
                    break;
                case "atsc":
                    flag |= INTERACTIVE_APP_TYPE_ATSC;
                    break;
                case "ginga":
                    flag |= INTERACTIVE_APP_TYPE_GINGA;
                    break;
                default:
                    break;
            }
        }
        return flag;
    }
}