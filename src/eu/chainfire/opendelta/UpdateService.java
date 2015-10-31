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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import eu.chainfire.opendelta.BatteryState.OnBatteryStateListener;
import eu.chainfire.opendelta.DeltaInfo.ProgressListener;
import eu.chainfire.opendelta.NetworkState.OnNetworkStateListener;
import eu.chainfire.opendelta.Scheduler.OnWantUpdateCheckListener;
import eu.chainfire.opendelta.ScreenState.OnScreenStateListener;

public class UpdateService extends Service implements OnNetworkStateListener,
OnBatteryStateListener, OnScreenStateListener,
OnWantUpdateCheckListener, OnSharedPreferenceChangeListener {
    private static final int HTTP_SOCKET_TIMEOUT = 30000;
    private static final int HTTP_CONNECTION_TIMEOUT = 30000;

    public static void start(Context context) {
        start(context, null);
    }

    public static void startCheck(Context context) {
        start(context, ACTION_CHECK);
    }

    public static void startFlash(Context context) {
        start(context, ACTION_FLASH);
    }

    public static void startBuild(Context context) {
        start(context, ACTION_BUILD);
    }

    public static void startUpdate(Context context) {
        start(context, ACTION_UPDATE);
    }

    private static void start(Context context, String action) {
        Intent i = new Intent(context, UpdateService.class);
        i.setAction(action);
        context.startService(i);
    }

    public static PendingIntent alarmPending(Context context, int id) {
        Intent intent = new Intent(context, UpdateService.class);
        intent.setAction(ACTION_ALARM);
        intent.putExtra(EXTRA_ALARM_ID, id);
        return PendingIntent.getService(context, id, intent, 0);
    }

    public static final String ACTION_SYSTEM_UPDATE_SETTINGS = "android.settings.SYSTEM_UPDATE_SETTINGS";
    public static final String PERMISSION_ACCESS_CACHE_FILESYSTEM = "android.permission.ACCESS_CACHE_FILESYSTEM";
    public static final String PERMISSION_REBOOT = "android.permission.REBOOT";

    public static final String BROADCAST_INTENT = "eu.chainfire.opendelta.intent.BROADCAST_STATE";
    public static final String EXTRA_STATE = "eu.chainfire.opendelta.extra.ACTION_STATE";
    public static final String EXTRA_LAST_CHECK = "eu.chainfire.opendelta.extra.LAST_CHECK";
    public static final String EXTRA_PROGRESS = "eu.chainfire.opendelta.extra.PROGRESS";
    public static final String EXTRA_CURRENT = "eu.chainfire.opendelta.extra.CURRENT";
    public static final String EXTRA_TOTAL = "eu.chainfire.opendelta.extra.TOTAL";
    public static final String EXTRA_FILENAME = "eu.chainfire.opendelta.extra.FILENAME";
    public static final String EXTRA_MS = "eu.chainfire.opendelta.extra.MS";

    public static final String STATE_ACTION_NONE = "action_none";
    public static final String STATE_ACTION_CHECKING = "action_checking";
    public static final String STATE_ACTION_CHECKING_MD5 = "action_checking_md5";
    public static final String STATE_ACTION_SEARCHING = "action_searching";
    public static final String STATE_ACTION_SEARCHING_MD5 = "action_searching_md5";
    public static final String STATE_ACTION_DOWNLOADING = "action_downloading";
    public static final String STATE_ACTION_APPLYING = "action_applying";
    public static final String STATE_ACTION_APPLYING_PATCH = "action_applying_patch";
    public static final String STATE_ACTION_APPLYING_MD5 = "action_applying_md5";
    public static final String STATE_ACTION_READY = "action_ready";
    public static final String STATE_ERROR_DISK_SPACE = "error_disk_space";
    public static final String STATE_ERROR_UNKNOWN = "error_unknown";
    public static final String STATE_ERROR_UNOFFICIAL = "error_unofficial";
    public static final String STATE_ACTION_BUILD = "action_build";
    public static final String STATE_ERROR_DOWNLOAD = "error_download";
    public static final String STATE_ERROR_CONNECTION = "error_connection";

    private static final String ACTION_CHECK = "eu.chainfire.opendelta.action.CHECK";
    private static final String ACTION_FLASH = "eu.chainfire.opendelta.action.FLASH";
    private static final String ACTION_ALARM = "eu.chainfire.opendelta.action.ALARM";
    private static final String EXTRA_ALARM_ID = "eu.chainfire.opendelta.extra.ALARM_ID";
    private static final String ACTION_NOTIFICATION_DELETED = "eu.chainfire.opendelta.action.NOTIFICATION_DELETED";
    private static final String ACTION_BUILD = "eu.chainfire.opendelta.action.BUILD";
    private static final String ACTION_UPDATE = "eu.chainfire.opendelta.action.UPDATE";

    private static final int NOTIFICATION_BUSY = 1;
    private static final int NOTIFICATION_UPDATE = 2;
    private static final int NOTIFICATION_ERROR = 3;

    public static final String PREF_READY_FILENAME_NAME = "ready_filename";
    public static final String PREF_READY_FILENAME_DEFAULT = null;

    private static final String PREF_LAST_CHECK_TIME_NAME = "last_check_time";
    private static final long PREF_LAST_CHECK_TIME_DEFAULT = 0L;

    private static final String PREF_LAST_SNOOZE_TIME_NAME = "last_snooze_time";
    private static final long PREF_LAST_SNOOZE_TIME_DEFAULT = 0L;
    // we only snooze until a new build
    private static final String PREF_SNOOZE_UPDATE_NAME = "last_snooze_update";

    private static final long SNOOZE_MS = 24 * AlarmManager.INTERVAL_HOUR;

    public static final String PREF_AUTO_UPDATE_NETWORKS_NAME = "auto_update_networks";
    public static final int PREF_AUTO_UPDATE_NETWORKS_DEFAULT = NetworkState.ALLOW_WIFI
            | NetworkState.ALLOW_ETHERNET;

    public static final String PREF_LATEST_FULL_NAME = "latest_full_name";
    public static final String PREF_LATEST_DELTA_NAME = "latest_delta_name";
    public static final String PREF_STOP_DOWNLOAD = "stop_download";
    public static final String PREF_DOWNLOAD_SIZE = "download_size";
    public static final String PREF_DELTA_SIGNATURE = "delta_signature";
    public static final String PREF_INITIAL_FILE = "initial_file";

    public static final int PREF_AUTO_DOWNLOAD_DISABLED = 0;
    public static final int PREF_AUTO_DOWNLOAD_CHECK = 1;
    public static final int PREF_AUTO_DOWNLOAD_DELTA = 2;
    public static final int PREF_AUTO_DOWNLOAD_FULL = 3;

    public static final String PREF_AUTO_DOWNLOAD_CHECK_STRING = String.valueOf(PREF_AUTO_DOWNLOAD_CHECK);
    public static final String PREF_AUTO_DOWNLOAD_DISABLED_STRING = String.valueOf(PREF_AUTO_DOWNLOAD_DISABLED);

    private Config config;

    private HandlerThread handlerThread;
    private Handler handler;

    private String state = STATE_ACTION_NONE;

    private NetworkState networkState = null;
    private BatteryState batteryState = null;
    private ScreenState screenState = null;

    private Scheduler scheduler = null;

    private PowerManager.WakeLock wakeLock = null;
    private WifiManager.WifiLock wifiLock = null;

    private NotificationManager notificationManager = null;
    private boolean stopDownload;
    private boolean updateRunning;
    private int failedUpdateCount;
    private SharedPreferences prefs = null;

    /*
     * Using reflection voodoo instead calling the hidden class directly, to
     * dev/test outside of AOSP tree
     */
    private boolean setPermissions(String path, int mode, int uid, int gid) {
        try {
            Class<?> FileUtils = getClassLoader().loadClass(
                    "android.os.FileUtils");
            Method setPermissions = FileUtils.getDeclaredMethod(
                    "setPermissions", new Class[] { String.class, int.class,
                            int.class, int.class });
            return ((Integer) setPermissions.invoke(
                    null,
                    new Object[] { path, Integer.valueOf(mode),
                            Integer.valueOf(uid), Integer.valueOf(gid) }) == 0);
        } catch (Exception e) {
            // A lot of voodoo could go wrong here, return failure instead of
            // crash
            Logger.ex(e);
        }
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onCreate() {
        super.onCreate();

        config = Config.getInstance(this);

        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(
                        config.getKeepScreenOn() ? PowerManager.SCREEN_DIM_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                : PowerManager.PARTIAL_WAKE_LOCK,
                        "OpenDelta WakeLock");
        wifiLock = ((WifiManager) getSystemService(WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL,
                        "OpenDelta WifiLock");

        handlerThread = new HandlerThread("OpenDelta Service Thread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        scheduler = new Scheduler(this, this);
        int autoDownload = getAutoDownloadValue();
        if (autoDownload != PREF_AUTO_DOWNLOAD_DISABLED) {
            scheduler.start();
        }
        networkState = new NetworkState();
        networkState.start(this, this, prefs.getInt(
                PREF_AUTO_UPDATE_NETWORKS_NAME,
                PREF_AUTO_UPDATE_NETWORKS_DEFAULT));

        batteryState = new BatteryState();
        batteryState.start(this, this,
                Integer.valueOf(prefs.getString(SettingsActivity.PREF_BATTERY_LEVEL, "50")).intValue(),
                prefs.getBoolean(SettingsActivity.PREF_CHARGE_ONLY, true));

        screenState = new ScreenState();
        screenState.start(this, this);

        prefs.registerOnSharedPreferenceChangeListener(this);

        autoState(false, PREF_AUTO_DOWNLOAD_CHECK, false);
    }

    @Override
    public void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        networkState.stop();
        batteryState.stop();
        screenState.stop();
        handlerThread.quitSafely();

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_CHECK.equals(intent.getAction())) {
                checkForUpdates(true, PREF_AUTO_DOWNLOAD_CHECK);
            } else if (ACTION_FLASH.equals(intent.getAction())) {
                flashUpdate();
            } else if (ACTION_ALARM.equals(intent.getAction())) {
                scheduler.alarm(intent.getIntExtra(EXTRA_ALARM_ID, -1));
            } else if (ACTION_NOTIFICATION_DELETED.equals(intent.getAction())) {
                prefs.edit().putLong(PREF_LAST_SNOOZE_TIME_NAME,
                        System.currentTimeMillis()).commit();
                String lastBuild = prefs.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT);
                if (lastBuild != PREF_READY_FILENAME_DEFAULT) {
                    // only snooze until no newer build is available
                    Logger.i("Snoozing notification for " + lastBuild);
                    prefs.edit().putString(PREF_SNOOZE_UPDATE_NAME, lastBuild).commit();
                }
            } else if (ACTION_BUILD.equals(intent.getAction())) {
                checkForUpdates(true, PREF_AUTO_DOWNLOAD_FULL);
            } else if (ACTION_UPDATE.equals(intent.getAction())) {
                autoState(true, PREF_AUTO_DOWNLOAD_CHECK, false);
            }
        }

        return START_STICKY;
    }

    private synchronized void updateState(String state, Float progress,
            Long current, Long total, String filename, Long ms) {
        this.state = state;

        Intent i = new Intent(BROADCAST_INTENT);
        i.putExtra(EXTRA_STATE, state);
        if (progress != null)
            i.putExtra(EXTRA_PROGRESS, progress);
        if (current != null)
            i.putExtra(EXTRA_CURRENT, current);
        if (total != null)
            i.putExtra(EXTRA_TOTAL, total);
        if (filename != null)
            i.putExtra(EXTRA_FILENAME, filename);
        if (ms != null)
            i.putExtra(EXTRA_MS, ms);

        sendStickyBroadcast(i);
    }

    @Override
    public void onNetworkState(boolean state) {
        Logger.d("network state --> %d", state ? 1 : 0);
    }

    @Override
    public void onBatteryState(boolean state) {
        Logger.d("battery state --> %d", state ? 1 : 0);
    }

    @Override
    public void onScreenState(boolean state) {
        Logger.d("screen state --> %d", state ? 1 : 0);
        scheduler.onScreenState(state);
    }

    @Override
    public boolean onWantUpdateCheck() {
        Logger.i("Scheduler requests check for updates");
        int autoDownload = getAutoDownloadValue();
        if (autoDownload != PREF_AUTO_DOWNLOAD_DISABLED) {
            return checkForUpdates(false, autoDownload);
        }
        return false;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
            String key) {
        Logger.d("onSharedPreferenceChanged " + key);

        if (PREF_AUTO_UPDATE_NETWORKS_NAME.equals(key)) {
            networkState.updateFlags(sharedPreferences.getInt(
                    PREF_AUTO_UPDATE_NETWORKS_NAME,
                    PREF_AUTO_UPDATE_NETWORKS_DEFAULT));
        }
        if (PREF_STOP_DOWNLOAD.equals(key)) {
            stopDownload = true;
        }
        if (SettingsActivity.PREF_AUTO_DOWNLOAD.equals(key)) {
            int autoDownload = getAutoDownloadValue();
            if (autoDownload == PREF_AUTO_DOWNLOAD_DISABLED) {
                scheduler.stop();
            } else {
                scheduler.start();
            }
        }
        if (batteryState != null) {
            batteryState.onSharedPreferenceChanged(sharedPreferences, key);
        }
        if (scheduler != null) {
        	scheduler.onSharedPreferenceChanged(sharedPreferences, key);
        }
    }

    private void autoState(boolean userInitiated, int checkOnly, boolean notify) {
        Logger.d("autoState state = " + this.state + " userInitiated = " + userInitiated + " checkOnly = " + checkOnly);

        if (isErrorState(this.state)) {
            return;
        }
        if (stopDownload) {
            // stop download is only possible in the download step
            // that means must have done a check step before 
            // so just fall back to this instead to show none state
            // which is just confusing
            checkOnly = PREF_AUTO_DOWNLOAD_CHECK;
        }
        String filename = prefs.getString(PREF_READY_FILENAME_NAME,
                PREF_READY_FILENAME_DEFAULT);

        if (filename != null) {
            if (!(new File(filename)).exists()) {
                filename = null;
            }
        }

        boolean updateAvilable = updateAvailable();
        // if the file has been downloaded or creates anytime before
        // this will aways be more important
        if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK && filename == null) {
            Logger.d("Checking step done");
            if (!updateAvilable) {
                Logger.d("System up to date");
                updateState(STATE_ACTION_NONE, null, null, null, null,
                        prefs.getLong(PREF_LAST_CHECK_TIME_NAME,
                                PREF_LAST_CHECK_TIME_DEFAULT));
            } else {
                Logger.d("Update available");
                updateState(STATE_ACTION_BUILD, null, null, null, null,
                        prefs.getLong(PREF_LAST_CHECK_TIME_NAME,
                                PREF_LAST_CHECK_TIME_DEFAULT));
                if (!userInitiated && notify) {
                    if (!isSnoozeNotification()) {
                        startNotification(checkOnly);
                    } else {
                        Logger.d("notification snoozed");
                    }
                }
            }
            return;
        }

        if (filename == null) {
            Logger.d("System up to date");
            updateState(STATE_ACTION_NONE, null, null, null, null,
                    prefs.getLong(PREF_LAST_CHECK_TIME_NAME,
                            PREF_LAST_CHECK_TIME_DEFAULT));
        } else {
            Logger.d("Update found: %s", filename);
            updateState(STATE_ACTION_READY, null, null, null, (new File(
                    filename)).getName(), prefs.getLong(
                            PREF_LAST_CHECK_TIME_NAME, PREF_LAST_CHECK_TIME_DEFAULT));

            if (!userInitiated && notify) {
                if (!isSnoozeNotification()) {
                    startNotification(checkOnly);
                } else {
                    Logger.d("notification snoozed");
                }
            }
        }
    }

    private PendingIntent getNotificationIntent(boolean delete) {
        if (delete) {
            Intent notificationIntent = new Intent(this, UpdateService.class);
            notificationIntent.setAction(ACTION_NOTIFICATION_DELETED);
            return PendingIntent.getService(this, 0, notificationIntent, 0);
        } else {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setAction(ACTION_SYSTEM_UPDATE_SETTINGS);
            return PendingIntent.getActivity(this, 0, notificationIntent, 0);
        }
    }

    private void startNotification(int checkOnly) {
        final String latestFull = prefs.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT);
        if (latestFull == PREF_READY_FILENAME_DEFAULT) {
            return;
        }
        String flashFilename = prefs.getString(PREF_READY_FILENAME_NAME, PREF_READY_FILENAME_DEFAULT);
        final boolean readyToFlash = flashFilename != PREF_READY_FILENAME_DEFAULT;
        if (readyToFlash) {
            flashFilename = new File(flashFilename).getName();
            flashFilename.substring(0, flashFilename.lastIndexOf('.'));
        }

        String notifyFileName = readyToFlash ? flashFilename : latestFull.substring(0, latestFull.lastIndexOf('.'));

        notificationManager.notify(
                NOTIFICATION_UPDATE,
                (new Notification.Builder(this))
                .setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(readyToFlash ? getString(R.string.notify_title_flash) : getString(R.string.notify_title_download))
                .setShowWhen(true)
                .setContentIntent(getNotificationIntent(false))
                .setDeleteIntent(getNotificationIntent(true))
                .setContentText(notifyFileName).build());
    }

    private void stopNotification() {
        notificationManager.cancel(NOTIFICATION_UPDATE);
    }

    private void startErrorNotification() {
        String errorStateString = null;
        try {
            errorStateString = getString(getResources().getIdentifier(
                    "state_" + state, "string", getPackageName()));
        } catch (Exception e) {
            // String for this state could not be found (displays empty string)
            Logger.ex(e);
        }
        if (errorStateString != null) {
            notificationManager.notify(
                    NOTIFICATION_ERROR,
                    (new Notification.Builder(this))
                    .setSmallIcon(R.drawable.stat_notify_error)
                    .setContentTitle(getString(R.string.notify_title_error))
                    .setContentText(errorStateString)
                    .setShowWhen(true)
                    .setContentIntent(getNotificationIntent(false)).build());
        }
    }

    private void stopErrorNotification() {
        notificationManager.cancel(NOTIFICATION_ERROR);
    }

    private byte[] downloadUrlMemory(String url) {
        Logger.d("download: %s", url);
        try {
            HttpParams params = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(params, HTTP_CONNECTION_TIMEOUT);
            HttpConnectionParams.setSoTimeout(params, HTTP_SOCKET_TIMEOUT);
            HttpClient client = new DefaultHttpClient(params);
            HttpGet request = new HttpGet(url);
            HttpResponse response = client.execute(request);
            int code = response.getStatusLine().getStatusCode();
            if (code != HttpStatus.SC_OK) {
                Logger.d("response: %d", code);
                return null;
            }
            int len = (int) response.getEntity().getContentLength();
            if ((len >= 0) && (len < 1024 * 1024)) {
                byte[] ret = new byte[len];
                InputStream in = response.getEntity().getContent();
                int pos = 0;
                while (pos < len) {
                    int r = in.read(ret, pos, len - pos);
                    pos += r;
                    if (r <= 0)
                        return null;
                }
                return ret;
            }
            return null;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return null;
        }
    }

    private String downloadUrlMemoryAsString(String url) {
        Logger.d("download: %s", url);
        try {
            HttpParams params = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(params, HTTP_CONNECTION_TIMEOUT);
            HttpConnectionParams.setSoTimeout(params, HTTP_SOCKET_TIMEOUT);
            HttpClient client = new DefaultHttpClient(params);
            HttpGet request = new HttpGet(url);
            HttpResponse response = client.execute(request);
            int code = response.getStatusLine().getStatusCode();
            if (code != HttpStatus.SC_OK) {
                Logger.d("response: %d", code);
                return null;
            }
            String responseBody = EntityUtils.toString(response.getEntity(),
                    HTTP.UTF_8);
            return responseBody;
        } catch (UnknownHostException e) {
            Logger.i("Failed to connect to download server");
            return null;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return null;
        }
    }

    private boolean downloadUrlFile(String url, File f, String matchMD5,
            DeltaInfo.ProgressListener progressListener) {
        Logger.d("download: %s", url);

        MessageDigest digest = null;
        if (matchMD5 != null) {
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                // No MD5 algorithm support
                Logger.ex(e);
            }
        }

        if (f.exists())
            f.delete();
        try {
            HttpParams params = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(params, HTTP_CONNECTION_TIMEOUT);
            HttpConnectionParams.setSoTimeout(params, HTTP_SOCKET_TIMEOUT);
            HttpClient client = new DefaultHttpClient(params);
            HttpGet request = new HttpGet(url);
            HttpResponse response = client.execute(request);
            int code = response.getStatusLine().getStatusCode();
            if (code != HttpStatus.SC_OK) {
                Logger.d("response: %d", code);
                return false;
            }
            long len = (int) response.getEntity().getContentLength();
            long recv = 0;
            if ((len > 0) && (len < 4L * 1024L * 1024L * 1024L)) {
                byte[] buffer = new byte[262144];

                InputStream is = response.getEntity().getContent();
                FileOutputStream os = new FileOutputStream(f, false);
                try {
                    int r;
                    while ((r = is.read(buffer)) > 0) {
                        if (stopDownload) {
                            return false;
                        }
                        os.write(buffer, 0, r);
                        if (digest != null)
                            digest.update(buffer, 0, r);

                        recv += (long) r;
                        if (progressListener != null)
                            progressListener.onProgress(
                                    ((float) recv / (float) len) * 100f, recv,
                                    len);
                    }
                } finally {
                    os.close();
                }

                if (digest != null) {
                    String MD5 = new BigInteger(1, digest.digest())
                    .toString(16).toLowerCase(Locale.ENGLISH);
                    while (MD5.length() < 32)
                        MD5 = "0" + MD5;
                    boolean md5Check = MD5.equals(matchMD5);
                    if (!md5Check) {
                        Logger.i("MD5 check failed for " + url);
                    }
                    return md5Check;
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return false;
        }
    }

    private boolean downloadUrlFileUnknownSize(String url, final File f,
            String matchMD5) {
        Logger.d("download: %s", url);

        MessageDigest digest = null;
        long len = 0;
        if (matchMD5 != null) {
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                // No MD5 algorithm support
                Logger.ex(e);
            }
        }

        if (f.exists())
            f.delete();
        try {
            updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, 0L, f.getName(), null);

            HttpParams params = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(params, HTTP_CONNECTION_TIMEOUT);
            HttpConnectionParams.setSoTimeout(params, HTTP_SOCKET_TIMEOUT);
            HttpClient client = new DefaultHttpClient(params);
            HttpGet request = new HttpGet(url);
            HttpResponse response = client.execute(request);
            int code = response.getStatusLine().getStatusCode();
            if (code != HttpStatus.SC_OK) {
                Logger.d("response: %d", code);
                return false;
            }
            len = (int) response.getEntity().getContentLength();

            updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, len, f.getName(), null);

            long freeSpace = (new StatFs(config.getPathBase()))
                    .getAvailableBytes();
            if (freeSpace < len) {
                updateState(STATE_ERROR_DISK_SPACE, null, freeSpace, len, null,
                        null);
                Logger.d("not enough space!");
                return false;
            }

            final long[] last = new long[] { 0, len, 0,
                    SystemClock.elapsedRealtime() };
            DeltaInfo.ProgressListener progressListener = new DeltaInfo.ProgressListener() {
                @Override
                public void onProgress(float progress, long current, long total) {
                    current += last[0];
                    total = last[1];
                    progress = ((float) current / (float) total) * 100f;
                    long now = SystemClock.elapsedRealtime();
                    if (now >= last[2] + 16L) {
                        updateState(STATE_ACTION_DOWNLOADING, progress,
                                current, total, f.getName(),
                                SystemClock.elapsedRealtime() - last[3]);
                        last[2] = now;
                    }
                }
            };

            long recv = 0;
            if ((len > 0) && (len < 4L * 1024L * 1024L * 1024L)) {
                byte[] buffer = new byte[262144];

                InputStream is = response.getEntity().getContent();
                FileOutputStream os = new FileOutputStream(f, false);
                try {
                    int r;
                    while ((r = is.read(buffer)) > 0) {
                        if (stopDownload) {
                            return false;
                        }
                        os.write(buffer, 0, r);
                        if (digest != null)
                            digest.update(buffer, 0, r);

                        recv += (long) r;
                        if (progressListener != null)
                            progressListener.onProgress(
                                    ((float) recv / (float) len) * 100f, recv,
                                    len);
                    }
                } finally {
                    os.close();
                }

                if (digest != null) {
                    String MD5 = new BigInteger(1, digest.digest())
                    .toString(16).toLowerCase(Locale.ENGLISH);
                    while (MD5.length() < 32)
                        MD5 = "0" + MD5;
                    boolean md5Check = MD5.equals(matchMD5);
                    if (!md5Check) {
                        Logger.i("MD5 check failed for " + url);
                    }
                    return md5Check;
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return false;
        } finally {
            updateState(STATE_ACTION_DOWNLOADING, 100f, len, len, null, null);
        }
    }

    private long getUrlDownloadSize(String url) {
        Logger.d("getUrlDownloadSize: %s", url);

        try {
            HttpParams params = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(params, HTTP_CONNECTION_TIMEOUT);
            HttpConnectionParams.setSoTimeout(params, HTTP_SOCKET_TIMEOUT);
            HttpClient client = new DefaultHttpClient(params);
            HttpGet request = new HttpGet(url);
            HttpResponse response = client.execute(request);
            int code = response.getStatusLine().getStatusCode();
            if (code != HttpStatus.SC_OK) {
                Logger.d("response: %d", code);
                return 0;
            }
            return response.getEntity().getContentLength();
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
        }
        return 0;
    }

    private String getNewestFullBuild() {
        Logger.d("Checking for latest full build");

        String url = config.getUrlBaseJson();

        String buildData = downloadUrlMemoryAsString(url);
        if (buildData == null || buildData.length() == 0) {
            updateState(STATE_ERROR_DOWNLOAD, null, null, null, url, null);
            return null;
        }
        JSONObject object = null;
        try {
            object = new JSONObject(buildData);

            Iterator<String> nextKey = object.keys();
            List<String> buildNames = new ArrayList<String>();
            while (nextKey.hasNext()) {
                String key = nextKey.next();
                if (key.equals("./" + config.getDevice())) {
                    JSONArray builds = object.getJSONArray(key);
                    for (int i = 0; i < builds.length(); i++) {
                        JSONObject build = builds.getJSONObject(i);
                        String file = build.getString("filename");
                        if (file.endsWith(".zip")) {
                            buildNames.add(new File(file).getName());
                        }
                    }
                }
            }
            // assumed its always sorted
            if (buildNames.size() > 0) {
                return buildNames.get(buildNames.size() - 1);
            }
        } catch (Exception e) {
        }
        updateState(STATE_ERROR_UNOFFICIAL, null, null, null, config.getVersion(), null);
        return null;
    }

    private DeltaInfo.ProgressListener getMD5Progress(String state,
            String filename) {
        final long[] last = new long[] { 0, SystemClock.elapsedRealtime() };
        final String _state = state;
        final String _filename = filename;

        return new DeltaInfo.ProgressListener() {
            @Override
            public void onProgress(float progress, long current, long total) {
                long now = SystemClock.elapsedRealtime();
                if (now >= last[0] + 16L) {
                    updateState(_state, progress, current, total, _filename,
                            SystemClock.elapsedRealtime() - last[1]);
                    last[0] = now;
                }
            }
        };
    }

    private String findZIPOnSD(DeltaInfo.FileFull zip, File base) {
        //Logger.d("scanning: %s", base.getAbsolutePath());
        File[] list = base.listFiles();
        if (list != null) {
            for (File f : list) {
                if (!f.isDirectory()
                        && f.getName().endsWith(".zip")
                        && f.getName().startsWith(
                                config.getFileBaseNamePrefix())) {
                    Logger.d("checking: %s", f.getAbsolutePath());

                    boolean ok = (zip.match(
                            f,
                            true,
                            getMD5Progress(STATE_ACTION_SEARCHING_MD5,
                                    f.getName())) != null);
                    updateState(STATE_ACTION_SEARCHING, null, null, null, null,
                            null);

                    if (ok)
                        return f.getAbsolutePath();
                }
            }

            for (File f : list) {
                if (f.isDirectory()) {
                    String ret = findZIPOnSD(zip, f);
                    if (ret != null)
                        return ret;
                }
            }
        }

        return null;
    }

    private long sizeOnDisk(long size) {
        // Assuming 256k block size here, should be future proof for a little
        // bit
        long blocks = (size + 262143L) / 262144L;
        return blocks * 262144L;
    }

    private boolean downloadDeltaFile(String url_base,
            DeltaInfo.FileBase fileBase, DeltaInfo.FileSizeMD5 match,
            DeltaInfo.ProgressListener progressListener, boolean force) {
        if (fileBase.getTag() == null) {
            if (force || networkState.getState()) {
                String url = url_base + fileBase.getName();
                String fn = config.getPathBase() + fileBase.getName();
                File f = new File(fn);
                Logger.d("download: %s --> %s", url, fn);

                if (downloadUrlFile(url, f, match.getMD5(), progressListener)) {
                    fileBase.setTag(fn);
                    Logger.d("success");
                    return true;
                } else {
                    f.delete();
                    if (stopDownload) {
                        Logger.d("download stopped");
                    } else {
                        updateState(STATE_ERROR_DOWNLOAD, null, null, null,
                                fn, null);
                        Logger.d("download error");
                    }
                    return false;
                }
            } else {
                Logger.d("aborting download due to network state");
                return false;
            }
        } else {
            Logger.d("have %s already", fileBase.getName());
            return true;
        }
    }

    private Thread getThreadedProgress(String filename, String display,
            long start, long currentOut, long totalOut) {
        final File _file = new File(filename);
        final String _display = display;
        final long _currentOut = currentOut;
        final long _totalOut = totalOut;
        final long _start = start;

        return new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        long current = _currentOut + _file.length();
                        updateState(STATE_ACTION_APPLYING_PATCH,
                                ((float) current / (float) _totalOut) * 100f,
                                current, _totalOut, _display,
                                SystemClock.elapsedRealtime() - _start);

                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        // We're being told to quit
                        break;
                    }
                }
            }
        });
    }

    private boolean zipadjust(String filenameIn, String filenameOut,
            long start, long currentOut, long totalOut) {
        Logger.d("zipadjust [%s] --> [%s]", filenameIn, filenameOut);

        // checking filesizes in the background as progress, because these
        // native functions don't have callbacks (yet) to do this

        (new File(filenameOut)).delete();

        Thread progress = getThreadedProgress(filenameOut,
                (new File(filenameIn)).getName(), start, currentOut, totalOut);
        progress.start();

        int ok = Native.zipadjust(filenameIn, filenameOut, 1);

        progress.interrupt();
        try {
            progress.join();
        } catch (InterruptedException e) {
            // We got interrupted in a very short wait, surprising, but not a
            // problem. 'progress' will quit by itself.
            Logger.ex(e);
        }

        Logger.d("zipadjust --> %d", ok);

        return (ok == 1);
    }

    private boolean dedelta(String filenameSource, String filenameDelta,
            String filenameOut, long start, long currentOut, long totalOut) {
        Logger.d("dedelta [%s] --> [%s] --> [%s]", filenameSource,
                filenameDelta, filenameOut);

        // checking filesizes in the background as progress, because these
        // native functions don't have callbacks (yet) to do this

        (new File(filenameOut)).delete();

        Thread progress = getThreadedProgress(filenameOut, (new File(
                filenameDelta)).getName(), start, currentOut, totalOut);
        progress.start();

        int ok = Native.dedelta(filenameSource, filenameDelta, filenameOut);

        progress.interrupt();
        try {
            progress.join();
        } catch (InterruptedException e) {
            // We got interrupted in a very short wait, surprising, but not a
            // problem. 'progress' will quit by itself.
            Logger.ex(e);
        }

        Logger.d("dedelta --> %d", ok);

        return (ok == 1);
    }

    private boolean checkForUpdates(boolean userInitiated, int checkOnly) {
        /*
         * Unless the user is specifically asking to check for updates, we only
         * check for them if we have a connection matching the user's set
         * preferences, we're charging and/or have juice aplenty (>50), and the screen
         * is off
         *
         * if user has enabled checking only we only check the screen state
         * cause the amount of data transferred for checking is not very large
         */

        if ((networkState == null) || (batteryState == null)
                || (screenState == null))
            return false;

        Logger.d("checkForUpdates checkOnly = " + checkOnly + " updateRunning = " + updateRunning + " userInitiated = " + userInitiated +
                " networkState.getState() = " + networkState.getState() + " batteryState.getState() = " + batteryState.getState() +
                " screenState.getState() = " + screenState.getState());

        if (updateRunning) {
            Logger.i("Ignoring request to check for updates - busy");
            return false;
        }

        stopNotification();
        stopErrorNotification();

        if (!isSupportedVersion()) {
            // TODO - to be more generic this should maybe use the info from getNewestFullBuild
            updateState(STATE_ERROR_UNOFFICIAL, null, null, null, config.getVersion(), null);
            Logger.i("Ignoring request to check for updates - not compatible for update! " + config.getVersion() + " " + config.getOfficialVersionTag());
            return false;
        }
        if (!networkState.isConnected()) {
            updateState(STATE_ERROR_CONNECTION, null, null, null, null, null);
            Logger.i("Ignoring request to check for updates - no data connection");
            return false;
        }
        boolean updateAllowed = false;
        if (!userInitiated) {
            updateAllowed = checkOnly >= PREF_AUTO_DOWNLOAD_CHECK;
            if (checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
                // must confirm to all if we may auto download
                updateAllowed = networkState.getState()
                        && batteryState.getState() && isScreenStateEnabled();
                if (!updateAllowed) {
                    // fallback to check only
                    checkOnly = PREF_AUTO_DOWNLOAD_CHECK;
                    updateAllowed = true;
                    Logger.i("Auto-dwonload not possible - fallback to check only");
                }
            }
        }

        if (userInitiated || updateAllowed) {
            Logger.i("Starting check for updates");
            checkForUpdatesAsync(userInitiated, checkOnly);
            return true;
        } else {
            Logger.i("Ignoring request to check for updates");
        }
        return false;
    }

    private long getDeltaDownloadSize(List<DeltaInfo> deltas) {
        updateState(STATE_ACTION_CHECKING, null, null, null, null, null);

        long deltaDownloadSize = 0L;
        for (DeltaInfo di : deltas) {
            String fn = config.getPathBase() + di.getUpdate().getName();
            if (di.getUpdate().match(
                    new File(fn),
                    true,
                    getMD5Progress(STATE_ACTION_CHECKING_MD5, di.getUpdate()
                            .getName())) == di.getUpdate().getUpdate()) {
                di.getUpdate().setTag(fn);
            } else {
                deltaDownloadSize += di.getUpdate().getUpdate().getSize();
            }
        }

        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);
        {
            if (config.getApplySignature()) {
                String fn = config.getPathBase()
                        + lastDelta.getSignature().getName();
                if (lastDelta.getSignature().match(
                        new File(fn),
                        true,
                        getMD5Progress(STATE_ACTION_CHECKING_MD5, lastDelta
                                .getSignature().getName())) == lastDelta
                                .getSignature().getUpdate()) {
                    lastDelta.getSignature().setTag(fn);
                } else {
                    deltaDownloadSize += lastDelta.getSignature().getUpdate()
                            .getSize();
                }
            }
        }

        updateState(STATE_ACTION_CHECKING, null, null, null, null, null);

        return deltaDownloadSize;
    }

    private long getFullDownloadSize(List<DeltaInfo> deltas) {
        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);
        return lastDelta.getOut().getOfficial().getSize();
    }

    private long getRequiredSpace(List<DeltaInfo> deltas, boolean getFull) {
        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);

        long requiredSpace = 0;
        if (getFull) {
            requiredSpace += sizeOnDisk(lastDelta.getOut().getTag() != null ? 0
                    : lastDelta.getOut().getOfficial().getSize());
        } else {
            // The resulting number will be a tad more than worst case what we
            // actually need, but not dramatically so

            for (DeltaInfo di : deltas) {
                if (di.getUpdate().getTag() == null)
                    requiredSpace += sizeOnDisk(di.getUpdate().getUpdate()
                            .getSize());
            }
            if (config.getApplySignature()) {
                requiredSpace += sizeOnDisk(lastDelta.getSignature()
                        .getUpdate().getSize());
            }

            long biggest = 0;
            for (DeltaInfo di : deltas)
                biggest = Math.max(biggest, sizeOnDisk(di.getUpdate()
                        .getApplied().getSize()));

            requiredSpace += 2 * sizeOnDisk(biggest);
        }

        return requiredSpace;
    }

    private String findInitialFile(List<DeltaInfo> deltas,
            String possibleMatch, boolean[] needsProcessing) {
        // Find the currently flashed ZIP

        DeltaInfo firstDelta = deltas.get(0);

        updateState(STATE_ACTION_SEARCHING, null, null, null, null, null);

        String initialFile = null;

        // Check if an original flashable ZIP is in our preferred location
        String expectedLocation = config.getPathBase()
                + firstDelta.getIn().getName();
        DeltaInfo.FileSizeMD5 match = null;
        if (expectedLocation.equals(possibleMatch)) {
            match = firstDelta.getIn().match(new File(expectedLocation), false,
                    null);
            if (match != null) {
                initialFile = possibleMatch;
            }
        }

        if (match == null) {
            match = firstDelta.getIn().match(
                    new File(expectedLocation),
                    true,
                    getMD5Progress(STATE_ACTION_SEARCHING_MD5, firstDelta
                            .getIn().getName()));
            if (match != null) {
                initialFile = expectedLocation;
            }
        }
        updateState(STATE_ACTION_SEARCHING, null, null, null, null, null);

        // If the user flashed manually, the file is probably not in our
        // preferred location (assuming it wasn't sideloaded), so search
        // the storages for it.
        if (initialFile == null) {
            // Primary external storage ( == internal storage)
            initialFile = findZIPOnSD(firstDelta.getIn(),
                    Environment.getExternalStorageDirectory());

            if (initialFile == null) {
                // Search secondary external storages ( == sdcards, OTG drives,
                // etc)
                String secondaryStorages = System.getenv("SECONDARY_STORAGE");
                if ((secondaryStorages != null)
                        && (secondaryStorages.length() > 0)) {
                    String[] storages = TextUtils.split(secondaryStorages,
                            File.pathSeparator);
                    for (String storage : storages) {
                        initialFile = findZIPOnSD(firstDelta.getIn(), new File(
                                storage));
                        if (initialFile != null) {
                            break;
                        }
                    }
                }
            }

            if (initialFile != null) {
                match = firstDelta.getIn().match(new File(initialFile), false,
                        null);
            }
        }

        if ((needsProcessing != null) && (needsProcessing.length > 0)) {
            needsProcessing[0] = (initialFile != null)
                    && (match != firstDelta.getIn().getStore());
        }

        return initialFile;
    }

    private boolean downloadFiles(List<DeltaInfo> deltas, boolean getFull,
            long totalDownloadSize, boolean force) {
        // Download all the files we do not have yet

        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);

        final String[] filename = new String[] { null };
        updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, totalDownloadSize, null,
                null);

        final long[] last = new long[] { 0, totalDownloadSize, 0,
                SystemClock.elapsedRealtime() };
        DeltaInfo.ProgressListener progressListener = new DeltaInfo.ProgressListener() {
            @Override
            public void onProgress(float progress, long current, long total) {
                current += last[0];
                total = last[1];
                progress = ((float) current / (float) total) * 100f;
                long now = SystemClock.elapsedRealtime();
                if (now >= last[2] + 16L) {
                    updateState(STATE_ACTION_DOWNLOADING, progress, current,
                            total, filename[0], SystemClock.elapsedRealtime()
                            - last[3]);
                    last[2] = now;
                }
            }
        };

        if (getFull) {
            filename[0] = lastDelta.getOut().getName();
            if (!downloadDeltaFile(config.getUrlBaseFull(), lastDelta.getOut(),
                    lastDelta.getOut().getOfficial(), progressListener, force)) {
                return false;
            }
        } else {
            for (DeltaInfo di : deltas) {
                filename[0] = di.getUpdate().getName();
                if (!downloadDeltaFile(config.getUrlBaseUpdate(),
                        di.getUpdate(), di.getUpdate().getUpdate(),
                        progressListener, force)) {
                    return false;
                }
                last[0] += di.getUpdate().getUpdate().getSize();
            }

            if (config.getApplySignature()) {
                filename[0] = lastDelta.getSignature().getName();
                if (!downloadDeltaFile(config.getUrlBaseUpdate(),
                        lastDelta.getSignature(), lastDelta.getSignature()
                        .getUpdate(), progressListener, force)) {
                    return false;
                }
            }
        }
        updateState(STATE_ACTION_DOWNLOADING, 100f, totalDownloadSize,
                totalDownloadSize, null, null);

        return true;
    }

    private boolean downloadFullBuild(String url, String md5Sum,
            String imageName) {
        final String[] filename = new String[] { null };
        filename[0] = imageName;
        String fn = config.getPathBase() + imageName;
        File f = new File(fn);
        Logger.d("download: %s --> %s", url, fn);

        if (downloadUrlFileUnknownSize(url, f, md5Sum)) {
            Logger.d("success");
            prefs.edit().putString(PREF_READY_FILENAME_NAME, fn).commit();
        } else {
            f.delete();
            if (stopDownload) {
                Logger.d("download stopped");
            } else {
                Logger.d("download error");
                updateState(STATE_ERROR_DOWNLOAD, null, null, null, url, null);
            }
        }

        return true;
    }

    private boolean checkFullBuildMd5Sum(String url, String fn) {
        String md5Url = url + ".md5sum";
        String latestFullMd5 = downloadUrlMemoryAsString(md5Url);
        if (latestFullMd5 != null){
            try {
                String md5Part = latestFullMd5.split("  ")[0];
                String fileMd5 =getFileMD5(new File(fn), getMD5Progress(STATE_ACTION_CHECKING_MD5, new File(fn).getName()));
                if (md5Part.equals(fileMd5)) {
                    return true;
                }
            } catch(Exception e) {
                // WTH knows what can comes from the server
            }
        }
        return false;
    }

    private boolean applyPatches(List<DeltaInfo> deltas, String initialFile,
            boolean initialFileNeedsProcessing) {
        // Create storeSigned outfile from infile + deltas

        DeltaInfo firstDelta = deltas.get(0);
        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);

        int tempFile = 0;
        String[] tempFiles = new String[] { config.getPathBase() + "temp1",
                config.getPathBase() + "temp2" };
        try {
            long start = SystemClock.elapsedRealtime();
            long current = 0L;
            long total = 0L;

            if (initialFileNeedsProcessing)
                total += firstDelta.getIn().getStore().getSize();
            for (DeltaInfo di : deltas)
                total += di.getUpdate().getApplied().getSize();
            if (config.getApplySignature())
                total += lastDelta.getSignature().getApplied().getSize();

            if (initialFileNeedsProcessing) {
                if (!zipadjust(initialFile, tempFiles[tempFile], start,
                        current, total)) {
                    updateState(STATE_ERROR_UNKNOWN, null, null, null, null,
                            null);
                    Logger.d("zipadjust error");
                    return false;
                }
                tempFile = (tempFile + 1) % 2;
                current += firstDelta.getIn().getStore().getSize();
            }

            for (DeltaInfo di : deltas) {
                String inFile = tempFiles[(tempFile + 1) % 2];
                if (!initialFileNeedsProcessing && (di == firstDelta))
                    inFile = initialFile;
                String outFile = tempFiles[tempFile];
                if (!config.getApplySignature() && (di == lastDelta))
                    outFile = config.getPathBase()
                    + lastDelta.getOut().getName();

                if (!dedelta(inFile, config.getPathBase()
                        + di.getUpdate().getName(), outFile, start, current,
                        total)) {
                    updateState(STATE_ERROR_UNKNOWN, null, null, null, null,
                            null);
                    Logger.d("dedelta error");
                    return false;
                }
                tempFile = (tempFile + 1) % 2;
                current += di.getUpdate().getApplied().getSize();
            }

            if (config.getApplySignature()) {
                if (!dedelta(tempFiles[(tempFile + 1) % 2],
                        config.getPathBase()
                        + lastDelta.getSignature().getName(),
                        config.getPathBase() + lastDelta.getOut().getName(),
                        start, current, total)) {
                    updateState(STATE_ERROR_UNKNOWN, null, null, null, null,
                            null);
                    Logger.d("dedelta error");
                    return false;
                }
                tempFile = (tempFile + 1) % 2;
                current += lastDelta.getSignature().getApplied().getSize();
            }
        } finally {
            (new File(tempFiles[0])).delete();
            (new File(tempFiles[1])).delete();
        }

        return true;
    }

    private void writeString(OutputStream os, String s)
            throws UnsupportedEncodingException, IOException {
        os.write((s + "\n").getBytes("UTF-8"));
    }

    @SuppressLint("SdCardPath")
    private void flashUpdate() {
        Logger.d("flashUpdate");
        if (getPackageManager().checkPermission(
                PERMISSION_ACCESS_CACHE_FILESYSTEM, getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            Logger.d("[%s] required beyond this point",
                    PERMISSION_ACCESS_CACHE_FILESYSTEM);
            return;
        }

        if (getPackageManager().checkPermission(PERMISSION_REBOOT,
                getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            Logger.d("[%s] required beyond this point", PERMISSION_REBOOT);
            return;
        }

        boolean deltaSignature = prefs.getBoolean(PREF_DELTA_SIGNATURE, false);
        String flashFilename = prefs.getString(PREF_READY_FILENAME_NAME, PREF_READY_FILENAME_DEFAULT);
        String intialFile = prefs.getString(PREF_INITIAL_FILE, PREF_READY_FILENAME_DEFAULT);

        clearState(prefs);

        if ((flashFilename == null)
                || !flashFilename.startsWith(config.getPathBase())) {
            Logger.d("flashUpdate - no valid file to flash found");
            return;
        }
        // now delete the initial file
        if (intialFile != null
                && new File(intialFile).exists()
                && intialFile.startsWith(config.getPathBase())){
            new File(intialFile).delete();
            Logger.d("flashUpdate - delete initial file");
        }
        // Remove the path to the storage from the filename, so we get a path
        // relative to the root of the storage
        String path_sd = Environment.getExternalStorageDirectory()
                + File.separator;
        flashFilename = flashFilename.substring(path_sd.length());

        // Find additional ZIPs to flash, strip path to sd
        List<String> extras = config.getFlashAfterUpdateZIPs();
        for (int i = 0; i < extras.size(); i++) {
            extras.set(i, extras.get(i).substring(path_sd.length()));
        }
        Logger.d("flashUpdate - extra files to flash " + extras);


        try {
            // TWRP - OpenRecoveryScript - the recovery will find the correct
            // storage root for the ZIPs, life is nice and easy.
            //
            // Optionally, we're injecting our own signature verification keys
            // and verifying against those. We place these keys in /cache
            // where only privileged apps can edit, contrary to the storage
            // location of the ZIP itself - anyone can modify the ZIP.
            // As such, flashing the ZIP without checking the whole-file
            // signature coming from a secure location would be a security
            // risk.
            {
                if (config.getInjectSignatureEnable() && deltaSignature) {
                    Logger.d("flashUpdate - create /cache/recovery/keys");

                    FileOutputStream os = new FileOutputStream(
                            "/cache/recovery/keys", false);
                    try {
                        writeString(os, config.getInjectSignatureKeys());
                    } finally {
                        os.close();
                    }
                    setPermissions("/cache/recovery/keys", 0644,
                            Process.myUid(), 2001 /* AID_CACHE */);
                }

                Logger.d("flashUpdate - create /cache/recovery/openrecoveryscript");

                FileOutputStream os = new FileOutputStream(
                        "/cache/recovery/openrecoveryscript", false);
                try {
                    if (config.getInjectSignatureEnable() && deltaSignature) {
                        writeString(os, "cmd cat /res/keys > /res/keys_org");
                        writeString(os,
                                "cmd cat /cache/recovery/keys > /res/keys");
                        writeString(os, "set tw_signed_zip_verify 1");
                        writeString(os,
                                String.format("install %s", flashFilename));
                        writeString(os, "set tw_signed_zip_verify 0");
                        writeString(os, "cmd cat /res/keys_org > /res/keys");
                        writeString(os, "cmd rm /res/keys_org");
                    } else {
                        writeString(os, "set tw_signed_zip_verify 0");
                        writeString(os,
                                String.format("install %s", flashFilename));
                    }

                    if (!config.getSecureModeCurrent()) {
                        // any program could have placed these ZIPs, so ignore
                        // them in secure mode
                        for (String file : extras) {
                            writeString(os, String.format("install %s", file));
                        }
                    }
                    writeString(os, "wipe cache");
                } finally {
                    os.close();
                }

                setPermissions("/cache/recovery/openrecoveryscript", 0644,
                        Process.myUid(), 2001 /* AID_CACHE */);
            }

            // CWM - ExtendedCommand - provide paths to both internal and
            // external storage locations, it's nigh impossible to know in
            // practice which will be correct, not just because the internal
            // storage location varies based on the external storage being
            // present, but also because it's not uncommon for community-built
            // versions to have them reversed. It'll give some horrible looking
            // results, but it seems to continue installing even if one ZIP
            // fails and produce the wanted result. Better than nothing ...
            //
            // We don't generate a CWM script in secure mode, because it
            // doesn't support checking our custom signatures
            if (!config.getSecureModeCurrent()) {
                Logger.d("flashUpdate - create /cache/recovery/extendedcommand");

                FileOutputStream os = new FileOutputStream(
                        "/cache/recovery/extendedcommand", false);
                try {
                    writeString(os, String.format("install_zip(\"%s%s\");",
                            "/sdcard/", flashFilename));
                    writeString(os, String.format("install_zip(\"%s%s\");",
                            "/emmc/", flashFilename));
                    for (String file : extras) {
                        writeString(os, String.format("install_zip(\"%s%s\");",
                                "/sdcard/", file));
                        writeString(os, String.format("install_zip(\"%s%s\");",
                                "/emmc/", file));
                    }
                    writeString(os,
                            "run_program(\"/sbin/busybox\", \"rm\", \"-rf\", \"/cache/*\");");
                } finally {
                    os.close();
                }

                setPermissions("/cache/recovery/extendedcommand", 0644,
                        Process.myUid(), 2001 /* AID_CACHE */);
            } else {
                (new File("/cache/recovery/extendedcommand")).delete();
            }

            Logger.d("flashUpdate - reboot to recovery");

            ((PowerManager) getSystemService(Context.POWER_SERVICE)).reboot("recovery");
        } catch (Exception e) {
            // We have failed to write something. There's not really anything
            // else to do at
            // at this stage than give up. No reason to crash though.
            Logger.ex(e);
        }
    }

    private boolean updateAvailable() {
        final String latestFull = prefs.getString(UpdateService.PREF_LATEST_FULL_NAME, UpdateService.PREF_READY_FILENAME_DEFAULT);
        final String latestDelta = prefs.getString(UpdateService.PREF_LATEST_DELTA_NAME, UpdateService.PREF_READY_FILENAME_DEFAULT);
        return latestFull != PREF_READY_FILENAME_DEFAULT || latestDelta != PREF_READY_FILENAME_DEFAULT;
    }

    private String getLatestFullMd5Sum(String latestFullFetch) {
        String md5Url = latestFullFetch + ".md5sum";
        String latestFullMd5 = downloadUrlMemoryAsString(md5Url);
        if (latestFullMd5 != null){
            try {
                String md5Part = latestFullMd5.split("  ")[0];
                return md5Part;
            } catch (Exception e) {
            }
        }
        return null;
    }

    private float getProgress(long current, long total) {
        if (total == 0)
            return 0f;
        return ((float) current / (float) total) * 100f;
    }

    // need to locally here for the deltas == 0 case
    private String getFileMD5(File file, ProgressListener progressListener) {
        String ret = null;

        long current = 0;
        long total = file.length();
        if (progressListener != null)
            progressListener.onProgress(getProgress(current, total), current, total);

        try {
            FileInputStream is = new FileInputStream(file);
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] buffer = new byte[256 * 1024];
                int r;

                while ((r = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, r);
                    current += (long) r;
                    if (progressListener != null)
                        progressListener.onProgress(getProgress(current, total), current, total);
                }

                String MD5 = new BigInteger(1, digest.digest()).
                        toString(16).toLowerCase(Locale.ENGLISH);
                while (MD5.length() < 32)
                    MD5 = "0" + MD5;
                ret = MD5;
            } finally {
                is.close();
            }
        } catch (NoSuchAlgorithmException e) {
            // No MD5 support (returns null)
            Logger.ex(e);
        } catch (FileNotFoundException e) {
            // The MD5 of a non-existing file is null
            Logger.ex(e);
        } catch (IOException e) {
            // Read or close error (returns null)
            Logger.ex(e);
        }

        if (progressListener != null)
            progressListener.onProgress(getProgress(total, total), total, total);

        return ret;
    }

    private boolean isSupportedVersion() {
        if (config.getVersion().indexOf(config.getOfficialVersionTag()) == -1) {
            return false;
        }
        return true;
    }

    private int getAutoDownloadValue() {
        String autoDownload = prefs.getString(SettingsActivity.PREF_AUTO_DOWNLOAD, getDefaultAutoDownloadValue());
        return Integer.valueOf(autoDownload).intValue();
    }

    private String getDefaultAutoDownloadValue() {
        return isSupportedVersion() ? PREF_AUTO_DOWNLOAD_CHECK_STRING : PREF_AUTO_DOWNLOAD_DISABLED_STRING;
    }

    private boolean isScreenStateEnabled() {
        if (screenState == null) {
            return false;
        }
        boolean screenStateValue = screenState.getState();
        boolean prefValue = prefs.getBoolean(SettingsActivity.PREF_SCREEN_STATE_OFF, true);
        if (prefValue) {
            // only when screen off
            return !screenStateValue;
        }
        // always allow
        return true;
    }

    public static boolean isProgressState(String state) {
        if (state.equals(UpdateService.STATE_ACTION_DOWNLOADING) ||
                state.equals(UpdateService.STATE_ACTION_SEARCHING) ||
                state.equals(UpdateService.STATE_ACTION_SEARCHING_MD5) ||
                state.equals(UpdateService.STATE_ACTION_CHECKING) ||
                state.equals(UpdateService.STATE_ACTION_CHECKING_MD5) ||
                state.equals(UpdateService.STATE_ACTION_APPLYING) ||
                state.equals(UpdateService.STATE_ACTION_APPLYING_MD5) ||
                state.equals(UpdateService.STATE_ACTION_APPLYING_PATCH)) {
            return true;
        }
        return false;
    }

    public static boolean isErrorState(String state) {
        if (state.equals(UpdateService.STATE_ERROR_DOWNLOAD) ||
                state.equals(UpdateService.STATE_ERROR_DISK_SPACE) ||
                state.equals(UpdateService.STATE_ERROR_UNKNOWN) ||
                state.equals(UpdateService.STATE_ERROR_UNOFFICIAL) ||
                state.equals(UpdateService.STATE_ERROR_CONNECTION)) {
            return true;
        }
        return false;
    }

    private boolean isSnoozeNotification() {
        // check if we're snoozed, using abs for clock changes
        boolean timeSnooze = Math.abs(System.currentTimeMillis()
                - prefs.getLong(PREF_LAST_SNOOZE_TIME_NAME,
                        PREF_LAST_SNOOZE_TIME_DEFAULT)) <= SNOOZE_MS;
        if (timeSnooze) {
            String lastBuild = prefs.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT);
            String snoozeBuild = prefs.getString(PREF_SNOOZE_UPDATE_NAME, PREF_READY_FILENAME_DEFAULT);
            if (lastBuild != PREF_READY_FILENAME_DEFAULT && snoozeBuild != PREF_READY_FILENAME_DEFAULT) {
                // only snooze if time snoozed and no newer update available
                if (!lastBuild.equals(snoozeBuild)) {
                    return false;
                }
            }
        }
        return timeSnooze;
    }

    public static void clearState(SharedPreferences prefs) {
        prefs.edit().putString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT).commit();
        prefs.edit().putString(PREF_LATEST_DELTA_NAME, PREF_READY_FILENAME_DEFAULT).commit();
        prefs.edit().putString(PREF_READY_FILENAME_NAME, PREF_READY_FILENAME_DEFAULT).commit();
        prefs.edit().putString(PREF_DOWNLOAD_SIZE, null).commit();
        prefs.edit().putBoolean(PREF_DELTA_SIGNATURE, false).commit();
        prefs.edit().putString(PREF_INITIAL_FILE, PREF_READY_FILENAME_DEFAULT).commit();
    }

    private void shouldShowErrorNotification() {
        boolean dailyAlarm = prefs.getString(SettingsActivity.PREF_SCHEDULER_MODE, SettingsActivity.PREF_SCHEDULER_MODE_SMART)
                .equals(SettingsActivity.PREF_SCHEDULER_MODE_DAILY);

        if (dailyAlarm || failedUpdateCount >= 4) {
            // if from scheduler show a notification cause user should
            // see that something went wrong
            // if we check only daily always show - if smart mode wait for 4
            // consecutive failure - would be about 24h
            startErrorNotification();
            failedUpdateCount = 0;
        }
    }

    private void checkForUpdatesAsync(final boolean userInitiated, final int checkOnly) {
        updateState(STATE_ACTION_CHECKING, null, null, null, null, null);
        wakeLock.acquire();
        wifiLock.acquire();

        String notificationText = getString(R.string.state_action_checking);
        if (checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
            notificationText = getString(R.string.state_action_downloading);
        }
        Notification notification = (new Notification.Builder(this)).
                setSmallIcon(R.drawable.stat_notify_update).
                setContentTitle(getString(R.string.title)).
                setContentText(notificationText).
                setShowWhen(false).
                setContentIntent(getNotificationIntent(false)).
                build();
        // TODO update notification with current step
        startForeground(NOTIFICATION_BUSY, notification);

        handler.post(new Runnable() {
            @Override
            public void run() {
                boolean downloadFullBuild = false;
                boolean force = userInitiated;

                stopDownload = false;
                updateRunning = true;

                try {
                    List<DeltaInfo> deltas = new ArrayList<DeltaInfo>();

                    String flashFilename = null;
                    (new File(config.getPathBase())).mkdir();
                    (new File(config.getPathFlashAfterUpdate())).mkdir();

                    clearState(prefs);

                    String latestFullBuild = getNewestFullBuild();
                    // if we dont even find a build on dl no sense to continue
                    if (latestFullBuild == null) {
                        Logger.d("no latest build found at " + config.getUrlBaseJson() + " for " + config.getDevice());
                        return;
                    }

                    String latestFullFetch = String.format(Locale.ENGLISH, "%s%s",
                            config.getUrlBaseFull(),
                            latestFullBuild);
                    Logger.d("latest full build for device " + config.getDevice() + " is " + latestFullFetch);
                    prefs.edit().putString(PREF_LATEST_FULL_NAME, latestFullBuild).commit();

                    // Create a list of deltas to apply to get from our current
                    // version to the latest
                    String fetch = String.format(Locale.ENGLISH, "%s%s.delta",
                            config.getUrlBaseDelta(),
                            config.getFilenameBase());

                    while (true) {
                        DeltaInfo delta = null;
                        byte[] data = downloadUrlMemory(fetch);
                        if (data != null && data.length != 0) {
                            try {
                                delta = new DeltaInfo(data, false);
                            } catch (JSONException e) {
                                // There's an error in the JSON. Could be bad JSON,
                                // could be a 404 text, etc
                                Logger.ex(e);
                                delta = null;
                            } catch (NullPointerException e) {
                                // Download failed
                                Logger.ex(e);
                                delta = null;
                            }
                        }

                        if (delta == null) {
                            // See if we have a revoked version instead, we
                            // still need it for chaining future deltas, but
                            // will not allow flashing this one
                            data = downloadUrlMemory(fetch.replace(".delta",
                                    ".delta_revoked"));
                            if (data != null && data.length != 0) {
                                try {
                                    delta = new DeltaInfo(data, true);
                                } catch (JSONException e) {
                                    // There's an error in the JSON. Could be bad
                                    // JSON, could be a 404 text, etc
                                    Logger.ex(e);
                                    delta = null;
                                } catch (NullPointerException e) {
                                    // Download failed
                                    Logger.ex(e);
                                    delta = null;
                                }
                            }

                            // We didn't get a delta or a delta_revoked - end of
                            // the delta availability chain
                            if (delta == null)
                                break;
                        }

                        Logger.d("delta --> [%s]", delta.getOut().getName());
                        fetch = String.format(Locale.ENGLISH, "%s%s.delta",
                                config.getUrlBaseDelta(), delta
                                .getOut().getName().replace(".zip", ""));
                        deltas.add(delta);
                    }

                    if (deltas.size() > 0) {
                        // See if we have done past work and have newer ZIPs
                        // than the original of what's currently flashed

                        int last = -1;
                        for (int i = deltas.size() - 1; i >= 0; i--) {
                            DeltaInfo di = deltas.get(i);
                            String fn = config.getPathBase() + di.getOut().getName();
                            if (di.getOut()
                                    .match(new File(fn),
                                            true,
                                            getMD5Progress(STATE_ACTION_CHECKING_MD5, di.getOut()
                                                    .getName())) != null) {
                                if (latestFullBuild.equals(di.getOut().getName())) {
                                    boolean signedFile = di.getOut().isSignedFile(new File(fn));
                                    Logger.d("match found (%s): %s", signedFile ? "delta" : "full", di.getOut().getName());
                                    flashFilename = fn;
                                    last = i;
                                    prefs.edit().putBoolean(PREF_DELTA_SIGNATURE, signedFile).commit();
                                    break;
                                }
                            }
                        }

                        if (last > -1) {
                            for (int i = 0; i <= last; i++) {
                                deltas.remove(0);
                            }
                        }
                    }

                    while ((deltas.size() > 0) && (deltas.get(deltas.size() - 1).isRevoked())) {
                        // Make sure the last delta is not revoked
                        deltas.remove(deltas.size() - 1);
                    }

                    if (deltas.size() == 0) {
                        // we found a matching zip created from deltas before
                        if (flashFilename != null) {
                            prefs.edit().putString(PREF_READY_FILENAME_NAME, flashFilename).commit();
                            return;
                        }
                        // only full download available
                        final String latestFull = prefs.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT);
                        String latestFullZip = latestFull !=  PREF_READY_FILENAME_DEFAULT ? latestFull : null;
                        String currentVersionZip = config.getFilenameBase() +".zip";

                        boolean updateAvilable = (latestFullZip != null && latestFullZip.compareTo(currentVersionZip) > 0);
                        downloadFullBuild = updateAvilable;

                        if (!updateAvilable) {
                            prefs.edit().putString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT).commit();
                        }

                        if (downloadFullBuild) {
                            String fn = config.getPathBase() + latestFullBuild;
                            if (new File(fn).exists()) {
                                if (checkFullBuildMd5Sum(latestFullFetch, fn)) {
                                    Logger.d("match found (full): " + fn);
                                    prefs.edit().putString(PREF_READY_FILENAME_NAME, fn).commit();
                                    downloadFullBuild = false;
                                } else {
                                    Logger.d("md5sum check failed : " + fn);
                                }
                            }
                        }
                        if (updateAvilable && downloadFullBuild) {
                            long size = getUrlDownloadSize(latestFullFetch);
                            if (size != 0) {
                                prefs.edit().putString(PREF_DOWNLOAD_SIZE,
                                                String.valueOf(size)).commit();
                            } else {
                                prefs.edit().putString(PREF_DOWNLOAD_SIZE,
                                                getString(R.string.text_download_size_unknown))
                                        .commit();
                            }
                        }
                        Logger.d("check donne: latest full build available = " + prefs.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT) +
                                " : updateAvilable = " + updateAvilable + " : downloadFullBuild = " + downloadFullBuild);

                        if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK) {
                            return;
                        }
                    } else {
                        DeltaInfo lastDelta = deltas.get(deltas.size() - 1);
                        flashFilename = config.getPathBase() + lastDelta.getOut().getName();

                        long deltaDownloadSize = getDeltaDownloadSize(deltas);
                        long fullDownloadSize = getFullDownloadSize(deltas);

                        Logger.d("download size --> deltas[%d] vs full[%d]", deltaDownloadSize,
                                fullDownloadSize);

                        // Find the currently flashed ZIP, or a newer one
                        String initialFile = null;
                        boolean initialFileNeedsProcessing = false;
                        {
                            boolean[] needsProcessing = new boolean[] {
                                    false
                            };
                            initialFile = findInitialFile(deltas, flashFilename, needsProcessing);
                            initialFileNeedsProcessing = needsProcessing[0];
                        }
                        Logger.d("initial: %s", initialFile != null ? initialFile : "not found");

                        // If we don't have a file to start out with, or the
                        // combined deltas get big, just get the latest full ZIP
                        downloadFullBuild = ((initialFile == null) || (deltaDownloadSize > fullDownloadSize));

                        final String latestFull = prefs.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT);
                        final String latestDelta = flashFilename;

                        String latestDeltaZip = latestDelta != PREF_READY_FILENAME_DEFAULT ? new File(latestDelta).getName() : null;
                        String latestFullZip = latestFull !=  PREF_READY_FILENAME_DEFAULT ? latestFull : null;
                        String currentVersionZip = config.getFilenameBase() +".zip";
                        boolean fullUpdatePossible = latestFullZip != null && latestFullZip.compareTo(currentVersionZip) > 0;
                        boolean deltaUpdatePossible = !downloadFullBuild && latestDeltaZip != null && latestDeltaZip.compareTo(currentVersionZip) > 0 && latestDeltaZip.equals(latestFullZip);

                        if (!deltaUpdatePossible && fullUpdatePossible) {
                            downloadFullBuild = true;
                        }
                        boolean updateAvilable = fullUpdatePossible || deltaUpdatePossible;

                        if (!updateAvilable) {
                            prefs.edit().putString(PREF_LATEST_DELTA_NAME, PREF_READY_FILENAME_DEFAULT).commit();
                            prefs.edit().putString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT).commit();
                        } else {
                            if (downloadFullBuild) {
                                prefs.edit().putString(PREF_LATEST_DELTA_NAME, PREF_READY_FILENAME_DEFAULT).commit();
                            } else {
                                prefs.edit().putString(PREF_LATEST_DELTA_NAME, new File(flashFilename).getName()).commit();
                            }
                        }

                        if (downloadFullBuild) {
                            String fn = config.getPathBase() + latestFullBuild;
                            if (new File(fn).exists()) {
                                if (checkFullBuildMd5Sum(latestFullFetch, fn)) {
                                    Logger.d("match found (full): " + fn);
                                    prefs.edit().putString(PREF_READY_FILENAME_NAME, fn).commit();
                                    downloadFullBuild = false;
                                } else {
                                    Logger.d("md5sum check failed : " + fn);
                                }
                            }
                        }
                        if (updateAvilable) {
                            if (deltaUpdatePossible) {
                                prefs.edit().putString(PREF_DOWNLOAD_SIZE, String.valueOf(deltaDownloadSize)).commit();
                            } else if (downloadFullBuild) {
                                prefs.edit().putString(PREF_DOWNLOAD_SIZE, String.valueOf(fullDownloadSize)).commit();
                            }
                        }
                        Logger.d("check donne: latest valid delta update = " + prefs.getString(PREF_LATEST_DELTA_NAME, PREF_READY_FILENAME_DEFAULT) +
                                " : latest full build available = " + prefs.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT) +
                                " : updateAvilable = " + updateAvilable + " : downloadFullBuild = " + downloadFullBuild);

                        if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK) {
                            return;
                        }

                        long requiredSpace = getRequiredSpace(deltas, downloadFullBuild);
                        long freeSpace = (new StatFs(config.getPathBase())).getAvailableBytes();
                        if (freeSpace < requiredSpace) {
                            updateState(STATE_ERROR_DISK_SPACE, null, freeSpace, requiredSpace,
                                    null, null);
                            Logger.d("not enough space!");
                            return;
                        }

                        long downloadSize = downloadFullBuild ? fullDownloadSize : deltaDownloadSize;

                        if (!downloadFullBuild && checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
                            // Download all the files we do not have yet
                            // getFull = false since full download is handled below
                            if (!downloadFiles(deltas, false, downloadSize, force))
                                return;

                            // Reconstruct flashable ZIP
                            if (!applyPatches(deltas, initialFile, initialFileNeedsProcessing))
                                return;

                            // Verify using MD5
                            if (lastDelta.getOut().match(
                                    new File(config.getPathBase() + lastDelta.getOut().getName()),
                                    true,
                                    getMD5Progress(STATE_ACTION_APPLYING_MD5, lastDelta.getOut()
                                            .getName())) == null) {
                                updateState(STATE_ERROR_UNKNOWN, null, null, null, null, null);
                                Logger.d("final verification error");
                                return;
                            }
                            Logger.d("final verification complete");

                            // Cleanup
                            for (DeltaInfo di : deltas) {
                                (new File(config.getPathBase() + di.getUpdate().getName())).delete();
                                (new File(config.getPathBase() + di.getSignature().getName())).delete();
                                if (di != lastDelta)
                                    (new File(config.getPathBase() + di.getOut().getName())).delete();
                            }
                            // we will not delete initialFile until flashing
                            // else people building images and not flashing for 24h will loose
                            // the possibility to do delta updates
                            if (initialFile != null) {
                                if (initialFile.startsWith(config.getPathBase())) {
                                    prefs.edit().putString(PREF_INITIAL_FILE, initialFile).commit();
                                }
                            }
                            prefs.edit().putBoolean(PREF_DELTA_SIGNATURE, true).commit();
                            prefs.edit().putString(PREF_READY_FILENAME_NAME, flashFilename).commit();
                        }
                    }
                    if (downloadFullBuild && checkOnly == PREF_AUTO_DOWNLOAD_FULL) {
                        if (force || networkState.getState()) {
                            String latestFullMd5 = getLatestFullMd5Sum(latestFullFetch);
                            if (latestFullMd5 != null){
                                downloadFullBuild(latestFullFetch, latestFullMd5, latestFullBuild);
                            } else {
                                Logger.d("aborting download due to md5sum not found");
                            }
                        } else {
                            Logger.d("aborting download due to network state");
                        }
                    }
                } finally {
                    prefs.edit().putLong(PREF_LAST_CHECK_TIME_NAME, System.currentTimeMillis()).commit();
                    stopForeground(true);
                    if (wifiLock.isHeld()) wifiLock.release();
                    if (wakeLock.isHeld()) wakeLock.release();

                    if (isErrorState(state)) {
                        failedUpdateCount++;
                        clearState(prefs);
                        if (!userInitiated) {
                            shouldShowErrorNotification();
                        }
                    } else {
                        failedUpdateCount = 0;
                        autoState(userInitiated, checkOnly, true);
                    }
                    updateRunning = false;
                }
            }
        });
    }
}
