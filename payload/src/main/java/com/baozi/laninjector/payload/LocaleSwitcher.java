package com.baozi.laninjector.payload;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;

import java.util.Locale;

public class LocaleSwitcher {

    private static final String TAG = "LanInjector";

    @SuppressWarnings("deprecation")
    public static void switchLocale(Activity activity, String localeCode) {
        Log.d(TAG, "switchLocale: " + localeCode + " on " + activity.getClass().getSimpleName());
        Locale locale = LocaleUtils.parseLocaleQualifier(localeCode);
        Locale.setDefault(locale);

        Resources resources = activity.getResources();
        Configuration config = new Configuration(resources.getConfiguration());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            activity.getApplicationContext()
                    .createConfigurationContext(config);
            activity.getApplicationContext().getResources()
                    .updateConfiguration(config, resources.getDisplayMetrics());
        }

        config.locale = locale;
        resources.updateConfiguration(config, resources.getDisplayMetrics());

        Resources appResources = activity.getApplicationContext().getResources();
        Configuration appConfig = new Configuration(appResources.getConfiguration());
        appConfig.locale = locale;
        appResources.updateConfiguration(appConfig, appResources.getDisplayMetrics());

        Log.d(TAG, "Locale set to " + locale + ", calling recreate()");
        activity.recreate();
    }
}
