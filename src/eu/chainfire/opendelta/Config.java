/*
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2015 The OmniROM Project
 */
/*
 * This file is part of OpenDelta.
 *
 * OpenDelta is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenDelta is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenDelta. If not, see <http://www.gnu.org/licenses/>.
 */

package eu.chainfire.opendelta;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Environment;
import android.os.SystemProperties;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class Config {
    private static Config instance = null;

    public static Config getInstance(Context context) {
        if (instance == null) {
            instance = new Config(context.getApplicationContext());
        }
        return instance;
    }

    private final static String PREF_SECURE_MODE_NAME = "secure_mode";
    private final static String PREF_SHOW_INFO_NAME = "show_info";
    private final static String PREF_AB_PERF_MODE_NAME = "ab_perf_mode";
    private final static boolean PREF_AB_PERF_MODE_DEFAULT = true;
    private static final String PROP_AB_DEVICE = "ro.build.ab_update";

    private final SharedPreferences prefs;

    private final String property_version;
    private final String property_device;
    private final String filename_base;
    private final String path_base;
    private final String path_flash_after_update;
    private final String url_base_update;
    private final String url_base;
    private final String url_base_sum;
    private final String url_base_suffix;
    private final boolean support_ab_perf_mode;
    private final boolean use_twrp;
    private final String filename_base_prefix;
    private final String url_base_json;
    private final String android_version;

    /*
     * Using reflection voodoo instead calling the hidden class directly, to
     * dev/test outside of AOSP tree
     */
    private String getProperty(Context context, String key) {
        try {
            Class<?> SystemProperties = context.getClassLoader().loadClass(
                    "android.os.SystemProperties");
            Method get = SystemProperties.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, new Object[] { key, ""});
        } catch (Exception e) {
            // A lot of voodoo could go wrong here, return failure instead of
            // crash
            Logger.ex(e);
        }
        return null;
    }

    private Config(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);

        Resources res = context.getResources();

        property_version = getProperty(context,
                res.getString(R.string.property_version));
        property_device = getProperty(context,
                res.getString(R.string.property_device));
        filename_base = String.format(Locale.ENGLISH,
                res.getString(R.string.filename_base), property_version);

        path_base = String.format(Locale.ENGLISH, "%s%s%s%s",
                Environment.getExternalStorageDirectory().getAbsolutePath(),
                File.separator, res.getString(R.string.path_base),
                File.separator);
        path_flash_after_update = String.format(Locale.ENGLISH, "%s%s%s",
                path_base, "FlashAfterUpdate", File.separator);
        url_base_update = String.format(Locale.ENGLISH,
                res.getString(R.string.url_base_update), property_device);
        url_base = String.format(
                res.getString(R.string.url_base_full), property_device);
        url_base_sum = String.format(
                res.getString(R.string.url_base_full_sum), property_device);
        url_base_suffix = res.getString(R.string.url_base_suffix);
        support_ab_perf_mode = res.getBoolean(R.bool.support_ab_perf_mode);
        use_twrp = res.getBoolean(R.bool.use_twrp);
        url_base_json = String.format(
                res.getString(R.string.url_base_json),
                property_device, property_device);
        android_version = getProperty(context,
                res.getString(R.string.android_version));
        filename_base_prefix = String.format(Locale.ENGLISH,
                res.getString(R.string.filename_base), android_version);

        Logger.d("property_version: %s", property_version);
        Logger.d("property_device: %s", property_device);
        Logger.d("filename_base: %s", filename_base);
        Logger.d("filename_base_prefix: %s", filename_base_prefix);
        Logger.d("path_base: %s", path_base);
        Logger.d("path_flash_after_update: %s", path_flash_after_update);
        Logger.d("url_base_update: %s", url_base_update);
        Logger.d("url_base: %s", url_base);
        Logger.d("url_base_sum: %s", url_base_sum);
        Logger.d("url_base_json: %s", url_base_json);
        Logger.d("use_twrp: %d", use_twrp ? 1 : 0);
    }

    public String getFilenameBase() {
        return filename_base;
    }

    public String getPathBase() {
        return path_base;
    }

    public String getPathFlashAfterUpdate() {
        return path_flash_after_update;
    }

    public String getUrlBaseUpdate() {
        return url_base_update;
    }

    public String getUrlBase() {
        return url_base;
    }

    public String getUrlBaseSum() {
        return url_base_sum;
    }

    public String getUrlSuffix() {
        return url_base_suffix;
    }

    public boolean getUseTWRP() {
        return use_twrp;
    }

    public boolean getABPerfModeSupport() {
        return support_ab_perf_mode;
    }

    public boolean getABPerfModeCurrent() {
        return getABPerfModeSupport() && prefs.getBoolean(
                PREF_AB_PERF_MODE_NAME, PREF_AB_PERF_MODE_DEFAULT);
    }

    public void setABPerfModeCurrent(boolean enable) {
        prefs.edit().putBoolean(PREF_AB_PERF_MODE_NAME, enable).commit();
    }

    public boolean getShowInfo() {
        return prefs.getBoolean(PREF_SHOW_INFO_NAME, true);
    }

    public void setShowInfo(boolean enable) {
        prefs.edit().putBoolean(PREF_SHOW_INFO_NAME, enable).commit();
    }

    public List<String> getFlashAfterUpdateZIPs() {
        List<String> extras = new ArrayList<>();

        File[] files = (new File(getPathFlashAfterUpdate())).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().toLowerCase(Locale.ENGLISH).endsWith(".zip")) {
                    String filename = f.getAbsolutePath();
                    if (filename.startsWith(getPathBase())) {
                        extras.add(filename);
                    }
                }
            }
            Collections.sort(extras);
        }

        return extras;
    }

    public String getDevice() {
        return property_device;
    }

    public String getVersion() {
        return property_version;
    }

    public String getFileBaseNamePrefix() {
        return filename_base_prefix;
    }

    public String getUrlBaseJson() {
        return url_base_json;
    }

    public String getAndroidVersion() {
        return android_version;
    }

    public static boolean isABDevice() {
        return SystemProperties.getBoolean(PROP_AB_DEVICE, false);
    }
}
