package com.baozi.laninjector.payload;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * Auto-initialized ContentProvider that bootstraps the LanInjector payload.
 * Registered in AndroidManifest.xml during APK injection.
 * ContentProvider.onCreate() runs before any Activity, making this
 * a reliable entry point that doesn't depend on any specific Activity.
 */
public class PayloadProvider extends ContentProvider {

    private static final String TAG = "LanInjector";

    @Override
    public boolean onCreate() {
        Log.d(TAG, "PayloadProvider.onCreate()");
        try {
            PayloadInit.initFromProvider(getContext());
        } catch (Exception e) {
            Log.e(TAG, "PayloadProvider init failed", e);
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
