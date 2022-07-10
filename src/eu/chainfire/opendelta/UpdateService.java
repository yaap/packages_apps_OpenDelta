/*
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2015 The OmniROM Project
 * Copyright (C) 2020-2021 Yet Another AOSP Project
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;
import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
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
import android.os.RecoverySystem;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.UpdateEngine;
import android.preference.PreferenceManager;

import eu.chainfire.opendelta.BatteryState.OnBatteryStateListener;
import eu.chainfire.opendelta.DeltaInfo.ProgressListener;
import eu.chainfire.opendelta.NetworkState.OnNetworkStateListener;
import eu.chainfire.opendelta.Scheduler.OnWantUpdateCheckListener;
import eu.chainfire.opendelta.ScreenState.OnScreenStateListener;

public class UpdateService extends Service implements OnNetworkStateListener,
        OnBatteryStateListener, OnScreenStateListener,
        OnWantUpdateCheckListener, OnSharedPreferenceChangeListener {
    private static final int HTTP_READ_TIMEOUT = 30000;
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

    public static void startClearRunningInstall(Context context) {
        start(context, ACTION_CLEAR_INSTALL_RUNNING);
    }

    public static void startFlashFile(Context context, String flashFilename) {
        Intent i = new Intent(context, UpdateService.class);
        i.setAction(ACTION_FLASH_FILE);
        i.putExtra(EXTRA_FILENAME, flashFilename);
        context.startService(i);
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
    public static final String EXTRA_ERROR_CODE = "eu.chainfire.opendelta.extra.ERROR_CODE";

    public static final String STATE_ACTION_NONE = "action_none";
    public static final String STATE_ACTION_CHECKING = "action_checking";
    public static final String STATE_ACTION_CHECKING_SUM = "action_checking_sum";
    public static final String STATE_ACTION_SEARCHING = "action_searching";
    public static final String STATE_ACTION_SEARCHING_SUM = "action_searching_sum";
    public static final String STATE_ACTION_DOWNLOADING = "action_downloading";
    public static final String STATE_ACTION_APPLYING = "action_applying";
    public static final String STATE_ACTION_APPLYING_PATCH = "action_applying_patch";
    public static final String STATE_ACTION_APPLYING_SUM = "action_applying_sum";
    public static final String STATE_ACTION_READY = "action_ready";
    public static final String STATE_ACTION_A_FLASH = "action_a_flash";
    public static final String STATE_ACTION_AB_FLASH = "action_ab_flash";
    public static final String STATE_ACTION_AB_FINISHED = "action_ab_finished";
    public static final String STATE_ERROR_DISK_SPACE = "error_disk_space";
    public static final String STATE_ERROR_UNKNOWN = "error_unknown";
    public static final String STATE_ERROR_UNOFFICIAL = "error_unofficial";
    public static final String STATE_ACTION_BUILD = "action_build";
    public static final String STATE_ERROR_DOWNLOAD = "error_download";
    public static final String STATE_ERROR_CONNECTION = "error_connection";
    public static final String STATE_ERROR_PERMISSIONS = "error_permissions";
    public static final String STATE_ERROR_FLASH = "error_flash";
    public static final String STATE_ERROR_AB_FLASH = "error_ab_flash";
    public static final String STATE_ERROR_FLASH_FILE = "error_flash_file";
    public static final String STATE_ACTION_FLASH_FILE_READY = "action_flash_file_ready";

    private static final String ACTION_CHECK = "eu.chainfire.opendelta.action.CHECK";
    private static final String ACTION_FLASH = "eu.chainfire.opendelta.action.FLASH";
    private static final String ACTION_ALARM = "eu.chainfire.opendelta.action.ALARM";
    private static final String EXTRA_ALARM_ID = "eu.chainfire.opendelta.extra.ALARM_ID";
    private static final String ACTION_NOTIFICATION_DELETED = "eu.chainfire.opendelta.action.NOTIFICATION_DELETED";
    private static final String ACTION_BUILD = "eu.chainfire.opendelta.action.BUILD";
    private static final String ACTION_UPDATE = "eu.chainfire.opendelta.action.UPDATE";
    static final String ACTION_CLEAR_INSTALL_RUNNING =
            "eu.chainfire.opendelta.action.ACTION_CLEAR_INSTALL_RUNNING";
    private static final String ACTION_FLASH_FILE = "eu.chainfire.opendelta.action.FLASH_FILE";

    private static final String NOTIFICATION_CHANNEL_ID = "eu.chainfire.opendelta.notification";
    public static final int NOTIFICATION_BUSY = 1;
    public static final int NOTIFICATION_UPDATE = 2;
    public static final int NOTIFICATION_ERROR = 3;

    public static final String PREF_READY_FILENAME_NAME = "ready_filename";

    public static final String PREF_LAST_CHECK_TIME_NAME = "last_check_time";
    public static final long PREF_LAST_CHECK_TIME_DEFAULT = 0L;

    private static final String PREF_LAST_DOWNLOAD_TIME = "last_spent_download_time";
    private static final String PREF_LAST_SNOOZE_TIME_NAME = "last_snooze_time";
    private static final long PREF_LAST_SNOOZE_TIME_DEFAULT = 0L;
    // we only snooze until a new build
    private static final String PREF_SNOOZE_UPDATE_NAME = "last_snooze_update";

    public static final String PREF_PENDING_REBOOT = "pending_reboot";

    private static final String PREF_CURRENT_AB_FILENAME_NAME = "current_ab_filename";
    public static final String PREF_CURRENT_FILENAME_NAME = "current_filename";
    public static final String PREF_FILE_FLASH = "file_flash";

    private static final long SNOOZE_MS = 24 * AlarmManager.INTERVAL_HOUR;

    public static final String PREF_AUTO_UPDATE_METERED_NETWORKS = "auto_update_metered_networks";

    public static final String PREF_LATEST_FULL_NAME = "latest_full_name";
    public static final String PREF_LATEST_DELTA_NAME = "latest_delta_name";
    public static final String PREF_STOP_DOWNLOAD = "stop_download";
    public static final String PREF_DOWNLOAD_SIZE = "download_size_long";
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
    private Notification.Builder mFlashNotificationBuilder;
    private Notification.Builder mDownloadNotificationBuilder;

    // url override
    private boolean isUrlOverride = false;
    private String sumUrlOvr = null;

    private long[] mLastProgressTime;
    private final DeltaInfo.ProgressListener mProgressListener = new DeltaInfo.ProgressListener() {
        private String status;

        @Override
        public void onProgress(float progress, long current, long total) {
            long now = SystemClock.elapsedRealtime();
            if (now >= mLastProgressTime[0] + 16L) {
                long ms = SystemClock.elapsedRealtime() - mLastProgressTime[1];
                int sec = (int) (((((float) total / (float) current) * (float) ms) - ms) / 1000f);
                updateState(STATE_ACTION_AB_FLASH, progress, current, total, this.status,
                        ms);
                setFlashNotificationProgress((int) progress, sec);
                mLastProgressTime[0] = now;
            }
        }

        public void setStatus(String status) {
            this.status = status;
        }
    };

    /*
     * Using reflection voodoo instead calling the hidden class directly, to
     * dev/test outside of AOSP tree
     */
    private void setPermissions(String path, int uid) {
        try {
            Class<?> FileUtils = getClassLoader().loadClass(
                    "android.os.FileUtils");
            Method setPermissions = FileUtils.getDeclaredMethod(
                    "setPermissions", String.class, int.class,
                    int.class, int.class);
            setPermissions.invoke(
                    null,
                    path, 420,
                    uid, 2001);
        } catch (Exception e) {
            // A lot of voodoo could go wrong here, return failure instead of
            // crashing
            Logger.ex(e);
        }
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
                        "OpenDelta:WakeLock");
        wifiLock = ((WifiManager) getSystemService(WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL,
                        "OpenDelta:WifiLock");

        handlerThread = new HandlerThread("OpenDelta Service Thread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        createNotificationChannel();

        scheduler = new Scheduler(this, this);
        int autoDownload = getAutoDownloadValue();
        if (autoDownload != PREF_AUTO_DOWNLOAD_DISABLED) {
            scheduler.start();
        }
        networkState = new NetworkState();
        networkState.start(this, this, prefs.getBoolean(
                PREF_AUTO_UPDATE_METERED_NETWORKS, false));

        batteryState = new BatteryState();
        batteryState.start(this, this,
                Integer.parseInt(prefs.getString(SettingsActivity.PREF_BATTERY_LEVEL, "50")),
                prefs.getBoolean(SettingsActivity.PREF_CHARGE_ONLY, true));

        screenState = new ScreenState();
        screenState.start(this, this);

        prefs.registerOnSharedPreferenceChangeListener(this);
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
                if (checkPermissions())
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_CHECK);
            } else if (ACTION_FLASH.equals(intent.getAction())) {
                if (checkPermissions()) {
                    if (Config.isABDevice()) flashABUpdate();
                    else flashUpdate();
                }
            } else if (ACTION_ALARM.equals(intent.getAction())) {
                scheduler.alarm(intent.getIntExtra(EXTRA_ALARM_ID, -1));
            } else if (ACTION_NOTIFICATION_DELETED.equals(intent.getAction())) {
                prefs.edit().putLong(PREF_LAST_SNOOZE_TIME_NAME,
                        System.currentTimeMillis()).commit();
                String lastBuild = prefs.getString(PREF_LATEST_FULL_NAME, null);
                if (lastBuild != null) {
                    // only snooze until no newer build is available
                    Logger.i("Snoozing notification for " + lastBuild);
                    prefs.edit().putString(PREF_SNOOZE_UPDATE_NAME, lastBuild).commit();
                }
            } else if (ACTION_BUILD.equals(intent.getAction())) {
                if (checkPermissions())
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_FULL);
            } else if (ACTION_UPDATE.equals(intent.getAction())) {
                autoState(true, PREF_AUTO_DOWNLOAD_CHECK, false);
            } else if (ACTION_CLEAR_INSTALL_RUNNING.equals(intent.getAction())) {
                ABUpdate.setInstallingUpdate(false, this);
            } else if (ACTION_FLASH_FILE.equals(intent.getAction())) {
                if (intent.hasExtra(EXTRA_FILENAME)) {
                    String flashFilename = intent.getStringExtra(EXTRA_FILENAME);
                    setFlashFilename(flashFilename);
                }
            } else {
                autoState(false, PREF_AUTO_DOWNLOAD_CHECK, false);
            }
        }
        return START_STICKY;
    }

    private void updateState(String state, Float progress,
            Long current, Long total, String filename, Long ms) {
        updateState(state, progress, current,  total,  filename,  ms, -1);
    }

    private synchronized void updateState(String state, Float progress,
            Long current, Long total, String filename, Long ms, int errorCode) {
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
        if (errorCode != -1)
            i.putExtra(EXTRA_ERROR_CODE, errorCode);
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
        if (isProgressState(state)) {
            Logger.i("Blocked scheduler requests while running in state " + state);
            return false;
        }
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

        if (PREF_AUTO_UPDATE_METERED_NETWORKS.equals(key)) {
            networkState.setMeteredAllowed(sharedPreferences.getBoolean(
                    PREF_AUTO_UPDATE_METERED_NETWORKS, false));
        }
        if (PREF_STOP_DOWNLOAD.equals(key)) {
            stopDownload = true;
            if (notificationManager != null)
                notificationManager.cancel(NOTIFICATION_BUSY);
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

        // Check if we're currently installing an A/B update
        if (Config.isABDevice() && ABUpdate.isInstallingUpdate(this)) {
            // resume listening to progress
            final String flashFilename = prefs.getString(PREF_CURRENT_AB_FILENAME_NAME, null);
            final String _filename = new File(flashFilename).getName();
            if (mLastProgressTime == null)
                mLastProgressTime = new long[] { 0, SystemClock.elapsedRealtime() };
            mProgressListener.setStatus(_filename);
            updateState(STATE_ACTION_AB_FLASH, 0f, 0L, 100L, _filename, null);
            if (!ABUpdate.resume(flashFilename, mProgressListener, this)) {
                stopNotification();
                updateState(STATE_ERROR_AB_FLASH, null, null, null, null, null);
            } else {
                newFlashNotification(_filename);
            }
            return;
        }

        String filename = prefs.getString(PREF_READY_FILENAME_NAME, null);

        if (filename != null) {
            if (!(new File(filename)).exists()) {
                filename = null;
            }
        }

        // Check if a previous update was done already
        if (prefs.getBoolean(PREF_PENDING_REBOOT, false)) {
            final String lastFilename = prefs.getString(PREF_CURRENT_AB_FILENAME_NAME, null);
            prefs.edit().putBoolean(PREF_PENDING_REBOOT, false).commit();
            ABUpdate.pokeStatus(lastFilename, this);
            return;
        }

        // if the file has been downloaded or creates anytime before
        // this will always be more important
        if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK && filename == null) {
            Logger.d("Checking step done");
            if (!updateAvailable()) {
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
                        startNotification();
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
                    startNotification();
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

    private void startNotification() {
        final String latestFull = prefs.getString(PREF_LATEST_FULL_NAME, null);
        if (latestFull == null) {
            return;
        }
        String flashFilename = prefs.getString(PREF_READY_FILENAME_NAME, null);
        final boolean readyToFlash = flashFilename != null;
        if (readyToFlash) {
            flashFilename = new File(flashFilename).getName();
            flashFilename.substring(0, flashFilename.lastIndexOf('.'));
        }

        String notifyFileName = readyToFlash ? flashFilename : latestFull.substring(0, latestFull.lastIndexOf('.'));

        notificationManager.notify(
                NOTIFICATION_UPDATE,
                (new Notification.Builder(this, NOTIFICATION_CHANNEL_ID))
                .setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(readyToFlash ? getString(R.string.notify_title_flash) : getString(R.string.notify_title_download))
                .setShowWhen(true)
                .setContentIntent(getNotificationIntent(false))
                .setDeleteIntent(getNotificationIntent(true))
                .setContentText(notifyFileName).build());
    }

    private void newFlashNotification(String filename) {
        mFlashNotificationBuilder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        mFlashNotificationBuilder.setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(getString(R.string.state_action_ab_flash))
                .setShowWhen(true)
                .setOngoing(true)
                .setContentIntent(getNotificationIntent(false))
                .setContentText(filename);
        setFlashNotificationProgress(0, 0);
    }

    private void startABRebootNotification(String filename) {
        String flashFilename = filename;
        flashFilename = new File(flashFilename).getName();
        flashFilename.substring(0, flashFilename.lastIndexOf('.'));

        notificationManager.notify(
                NOTIFICATION_UPDATE,
                (new Notification.Builder(this, NOTIFICATION_CHANNEL_ID))
                .setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(getString(R.string.state_action_ab_finished))
                .setShowWhen(true)
                .setContentIntent(getNotificationIntent(false))
                .setDeleteIntent(getNotificationIntent(true))
                .setContentText(flashFilename).build());
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
                    (new Notification.Builder(this, NOTIFICATION_CHANNEL_ID))
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

    private HttpsURLConnection setupHttpsRequest(String urlStr) {
        return setupHttpsRequest(urlStr, 0);
    }

    private HttpsURLConnection setupHttpsRequest(String urlStr, long offset) {
        URL url;
        HttpsURLConnection urlConnection;
        try {
            url = new URL(urlStr);
            urlConnection = (HttpsURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(HTTP_CONNECTION_TIMEOUT);
            urlConnection.setReadTimeout(HTTP_READ_TIMEOUT);
            urlConnection.setRequestMethod("GET");
            urlConnection.setDoInput(true);
            if (offset > 0)
                urlConnection.setRequestProperty("Range", "bytes=" + offset + "-");
            urlConnection.connect();
            int code = urlConnection.getResponseCode();
            if (code != HttpsURLConnection.HTTP_OK
                    && code != HttpsURLConnection.HTTP_PARTIAL) {
                Logger.d("response: %d", code);
                return null;
            }
            return urlConnection;
        } catch (Exception e) {
            Logger.i("Failed to connect to server");
            return null;
        }
    }

    private byte[] downloadUrlMemory(String url) {
        Logger.d("download: %s", url);

        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null) {
                return null;
            }

            int len = urlConnection.getContentLength();
            if ((len >= 0) && (len < 1024 * 1024)) {
                InputStream is = urlConnection.getInputStream();
                int byteInt;
                ByteArrayOutputStream byteArray = new ByteArrayOutputStream();

                while((byteInt = is.read()) >= 0){
                    byteArray.write(byteInt);
                }

                return byteArray.toByteArray();
            }
            return null;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return null;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private String downloadUrlMemoryAsString(String url) {
        Logger.d("download: %s", url);

        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null){
                return null;
            }

            InputStream is = urlConnection.getInputStream();
            ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
            int byteInt;

            while((byteInt = is.read()) >= 0){
                byteArray.write(byteInt);
            }

            byte[] bytes = byteArray.toByteArray();
            if (bytes == null){
                return null;
            }

            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return null;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private boolean downloadUrlFile(String url, File f, String matchSUM,
            DeltaInfo.ProgressListener progressListener) {
        Logger.d("download: %s", url);

        HttpsURLConnection urlConnection = null;
        MessageDigest digest = null;
        if (matchSUM != null) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                // No SHA-256 algorithm support
                Logger.ex(e);
            }
        }

        if (f.exists())
            f.delete();

        try {
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null){
                return false;
            }
            long len = urlConnection.getContentLength();
            long recv = 0;
            if ((len > 0) && (len < 4L * 1024L * 1024L * 1024L)) {
                byte[] buffer = new byte[262144];

                InputStream is = urlConnection.getInputStream();
                try (FileOutputStream os = new FileOutputStream(f, false)) {
                    int r;
                    while ((r = is.read(buffer)) > 0) {
                        if (stopDownload) {
                            return false;
                        }
                        os.write(buffer, 0, r);
                        if (digest != null)
                            digest.update(buffer, 0, r);

                        recv += r;
                        if (progressListener != null)
                            progressListener.onProgress(
                                    ((float) recv / (float) len) * 100f, recv,
                                    len);
                    }
                }

                if (digest != null) {
                    StringBuilder SUM = new StringBuilder(new BigInteger(1, digest.digest())
                            .toString(16).toLowerCase(Locale.ENGLISH));
                    while (SUM.length() < 64)
                         SUM.insert(0, "0");
                    boolean sumCheck = SUM.toString().equals(matchSUM);
                    Logger.d("SUM=" + SUM + " matchSUM=" + matchSUM);
                    Logger.d("SUM.length=" + SUM.length() +
                            " matchSUM.length=" + matchSUM.length());
                    if (!sumCheck) {
                        Logger.i("SUM check failed for " + url);
                    }
                    return sumCheck;
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
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private boolean downloadUrlFileUnknownSize(String url, final File f,
            String matchSUM) {
        Logger.d("download: %s", url);

        HttpsURLConnection urlConnection = null;
        InputStream is = null;
        FileOutputStream os = null;
        MessageDigest digest = null;
        long len = 0;
        if (matchSUM != null) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                // No SHA-256 algorithm support
                Logger.ex(e);
            }
        }

        long lastTime = SystemClock.elapsedRealtime();
        long offset = 0;
        if (f.exists()) offset = f.length();

        try {
            final String userFN = f.getName().substring(0, f.getName().length() - 5);
            updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, 0L, userFN, null);
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null) return false;

            len = urlConnection.getContentLength();
            if (offset > 0 && offset < len) {
                urlConnection.disconnect();
                urlConnection = setupHttpsRequest(url, offset);
                if (urlConnection == null) return false;
            }

            updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, len, userFN, null);

            long freeSpace = (new StatFs(config.getPathBase()))
                    .getAvailableBytes();
            if (freeSpace < len - offset) {
                updateState(STATE_ERROR_DISK_SPACE, null, freeSpace, len, null,
                        null);
                Logger.d("not enough space!");
                return false;
            }

            if (offset > 0)
                lastTime -= prefs.getLong(PREF_LAST_DOWNLOAD_TIME, 0);
            final long[] last = new long[] { 0, len, 0, lastTime };
            DeltaInfo.ProgressListener progressListener = new DeltaInfo.ProgressListener() {
                @Override
                public void onProgress(float progress, long current, long total) {
                    current += last[0];
                    total = last[1];
                    progress = ((float) current / (float) total) * 100f;
                    long now = SystemClock.elapsedRealtime();
                    if (now >= last[2] + 250L) {
                        updateState(STATE_ACTION_DOWNLOADING, progress,
                                current, total, userFN, now - last[3]);
                        setDownloadNotificationProgress(progress, current,
                                total,now - last[3]);
                        last[2] = now;
                    }
                }

                public void setStatus(String s){
                    // do nothing
                }
            };

            long recv = offset;
            if ((len > 0) && (len < 4L * 1024L * 1024L * 1024L)) {
                byte[] buffer = new byte[262144];

                is = urlConnection.getInputStream();
                os = new FileOutputStream(f, offset > 0);
                int r;
                while ((r = is.read(buffer)) > 0) {
                    if (stopDownload) {
                        return false;
                    }
                    os.write(buffer, 0, r);
                    if (offset == 0 && digest != null)
                        digest.update(buffer, 0, r);

                    recv += r;
                    progressListener.onProgress(
                            ((float) recv / (float) len) * 100f,
                            recv, len);
                }

                if (offset > 0) digest = getDigestForFile(f);
                if (digest == null) return false;
                StringBuilder SUM = new StringBuilder(new BigInteger(1, digest.digest())
                        .toString(16).toLowerCase(Locale.ENGLISH));
                while (SUM.length() < 64)
                     SUM.insert(0, "0");
                boolean sumCheck = SUM.toString().equals(matchSUM);
                Logger.d("SUM=" + SUM + " matchSUM=" + matchSUM);
                Logger.d("SUM.length=" + SUM.length() +
                        " matchSUM.length=" + matchSUM.length());
                if (!sumCheck) {
                    Logger.i("SUM check failed for " + url);
                    // if sum does not match when done, get rid
                    f.delete();
                }
                return sumCheck;
            }
            return false;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            prefs.edit().putLong(PREF_LAST_DOWNLOAD_TIME,
                    SystemClock.elapsedRealtime() - lastTime).apply();
            if (urlConnection != null) urlConnection.disconnect();
            try { if (is != null) is.close(); } catch (IOException ignored) {}
            try { if (os != null) os.close(); } catch (IOException ignored) {}
            return false;
        } finally {
            updateState(STATE_ACTION_DOWNLOADING, 100f, len, len, null, null);
            notificationManager.cancel(NOTIFICATION_BUSY);
            if (urlConnection != null) urlConnection.disconnect();
            try { if (is != null) is.close(); } catch (IOException ignored) {}
            try { if (os != null) os.close(); } catch (IOException ignored) {}
        }
    }

    private long getUrlDownloadSize(String url) {
        Logger.d("getUrlDownloadSize: %s", url);

        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null){
                return 0;
            }

            return urlConnection.getContentLength();
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return 0;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private boolean isMatchingImage(String fileName) {
        try {
            Logger.d("Image check for file name: " + fileName);
            if (fileName.endsWith(".zip") && fileName.contains(config.getDevice())) {
                String[] parts = fileName.split("-");
                if (parts.length > 1) {
                    Logger.d("isMatchingImage: check " + fileName);
                    String version = parts[1];
                    Version current = new Version(config.getAndroidVersion());
                    Version fileVersion = new Version(version);
                    if (fileVersion.compareTo(current) >= 0) {
                        Logger.d("isMatchingImage: ok " + fileName);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Logger.ex(e);
        }
        return false;
    }

    private MessageDigest getDigestForFile(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // No SHA-256 algorithm support
            Logger.ex(e);
            return null;
        }
        FileInputStream fis = new FileInputStream(file);
        byte[] byteArray = new byte[1024];
        int bytesCount;
        while ((bytesCount = fis.read(byteArray)) != -1)
            digest.update(byteArray, 0, bytesCount);
        fis.close();
        return digest;
    }

    private List<String> getNewestFullBuild() {
        Logger.d("Checking for latest full build");

        String url = config.getUrlBaseJson();

        String buildData = downloadUrlMemoryAsString(url);
        if (buildData == null || buildData.length() == 0) {
            updateState(STATE_ERROR_DOWNLOAD, null, null, null, url, null);
            notificationManager.cancel(NOTIFICATION_BUSY);
            return null;
        }
        JSONObject object;
        try {
            object = new JSONObject(buildData);
            JSONArray updatesList = object.getJSONArray("response");
            String latestBuild = null;
            String urlOverride = null;
            String sumOverride = null;
            for (int i = 0; i < updatesList.length(); i++) {
                if (updatesList.isNull(i)) {
                    continue;
                }
                try {
                    JSONObject build = updatesList.getJSONObject(i);
                    String fileName = new File(build.getString("filename")).getName();
                    String urlOvr = null;
                    String sumOvr = null;
                    if (build.has("url"))
                        urlOvr = build.getString("url");
                    if (build.has("sha256url"))
                        sumOvr = build.getString("sha256url");
                    Logger.d("parsed from json:");
                    Logger.d("fileName= " + fileName);
                    if (isMatchingImage(fileName))
                        latestBuild = fileName;
                    if (urlOvr != null && !urlOvr.equals("")) {
                        urlOverride = urlOvr;
                        Logger.d("url= " + urlOverride);
                    }
                    if (sumOvr != null && !sumOvr.equals("")) {
                        sumOverride = sumOvr;
                        Logger.d("sha256 url= " + sumOverride);
                    }
                } catch (JSONException e) {
                    Logger.ex(e);
                }
            }

            List<String> ret = new ArrayList<>();
            if (latestBuild != null) {
                ret.add(latestBuild);
                if (urlOverride != null) {
                    ret.add(urlOverride);
                    if (sumOverride != null) {
                        ret.add(sumOverride);
                        isUrlOverride = true;
                        sumUrlOvr = sumOverride;
                    }
                }
            }
            return ret;

        } catch (Exception e) {
            Logger.ex(e);
        }
        updateState(STATE_ERROR_UNOFFICIAL, null, null, null, config.getVersion(), null);
        return null;
    }

    private DeltaInfo.ProgressListener getSUMProgress(String state,
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
            public void setStatus(String s) {
                // do nothing
            }
        };
    }

    private long sizeOnDisk(long size) {
        // Assuming 256k block size here, should be future proof for a little
        // bit
        long blocks = (size + 262143L) / 262144L;
        return blocks * 262144L;
    }

    private boolean downloadDeltaFile(String url_base,
            DeltaInfo.FileBase fileBase, DeltaInfo.FileSizeSHA256 match,
            DeltaInfo.ProgressListener progressListener, boolean force) {
        if (fileBase.getTag() == null) {
            if (force || networkState.getState()) {
                String url = url_base + fileBase.getName();
                String fn = config.getPathBase() + fileBase.getName();
                File f = new File(fn);
                Logger.d("download: %s --> %s", url, fn);

                if (downloadUrlFile(url, f, match.getSHA256(), progressListener)) {
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
                        notificationManager.cancel(NOTIFICATION_BUSY);
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

        return new Thread(() -> {
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
        });
    }

    private boolean zipadjust(String filenameIn, String filenameOut,
            long start, long currentOut, long totalOut) {
        Logger.d("zipadjust [%s] --> [%s]", filenameIn, filenameOut);

        // checking file sizes in the background as progress, because these
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

        // checking file sizes in the background as progress, because these
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

        // so we have a time even in the error case
        prefs.edit().putLong(PREF_LAST_CHECK_TIME_NAME, System.currentTimeMillis()).commit();

        if (!isSupportedVersion()) {
            // TODO - to be more generic this should maybe use the info from getNewestFullBuild
            updateState(STATE_ERROR_UNOFFICIAL, null, null, null, config.getVersion(), null);
            Logger.i("Ignoring request to check for updates - not compatible for update! " + config.getVersion());
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
                    Logger.i("Auto-download not possible - fallback to check only");
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
                    getSUMProgress(STATE_ACTION_CHECKING_SUM, di.getUpdate()
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
                        getSUMProgress(STATE_ACTION_CHECKING_SUM, lastDelta
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

            requiredSpace += 3 * sizeOnDisk(biggest);
        }

        return requiredSpace;
    }

    private String findInitialFile(List<DeltaInfo> deltas,
            String possibleMatch, boolean[] needsProcessing) {
        // Find the currently flashed ZIP
        Logger.d("findInitialFile possibleMatch = " + possibleMatch);

        DeltaInfo firstDelta = deltas.get(0);

        updateState(STATE_ACTION_SEARCHING, null, null, null, null, null);

        String initialFile = null;

        // Check if an original flashable ZIP is in our preferred location
        String expectedLocation = config.getPathBase()
                + firstDelta.getIn().getName();
        Logger.d("findInitialFile expectedLocation = " + expectedLocation);
        DeltaInfo.FileSizeSHA256 match = null;
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
                    getSUMProgress(STATE_ACTION_SEARCHING_SUM, firstDelta
                            .getIn().getName()));
            if (match != null) {
                initialFile = expectedLocation;
            }
        }

        if ((needsProcessing != null) && (needsProcessing.length > 0)) {
            needsProcessing[0] = (initialFile != null)
                    && (match != firstDelta.getIn().getStore());
        }

        return initialFile;
    }

    private boolean downloadFiles(List<DeltaInfo> deltas,
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
                    setDownloadNotificationProgress(progress, current, total,
                            SystemClock.elapsedRealtime() - last[3]);
                    last[2] = now;
                }
            }
            public void setStatus(String s) {
                // do nothing
            }
        };

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
        updateState(STATE_ACTION_DOWNLOADING, 100f, totalDownloadSize,
                totalDownloadSize, null, null);

        return true;
    }

    private void downloadFullBuild(String url, String sha256Sum,
                                   String imageName) {
        final String[] filename = new String[] { null };
        filename[0] = imageName;
        String fn = config.getPathBase() + imageName;
        File f = new File(fn + ".part");
        Logger.d("download: %s --> %s", url, fn);

        if (downloadUrlFileUnknownSize(url, f, sha256Sum)
                && f.renameTo(new File(fn))) {
            Logger.d("success");
            prefs.edit().putString(PREF_READY_FILENAME_NAME, fn).commit();
        } else {
            if (stopDownload) {
                Logger.d("download stopped");
                f.delete();
            } else {
                Logger.d("download error");
                updateState(STATE_ERROR_DOWNLOAD, null, null, null, url, null);
                notificationManager.cancel(NOTIFICATION_BUSY);
            }
        }

    }

    /**
     * @param url - url to sha256sum file
     * @param fn - file name
     * @param ovr - whether direct link to sha256sum is provided
     * @return true if sha256sum matches the file
     */
    private boolean checkFullBuildSHA256Sum(String url, String fn, boolean ovr) {
        String urlSuffix = config.getUrlSuffix();
        if (!ovr && urlSuffix.length() > 0) {
            url += urlSuffix;
        }
        String latestFullSUM = downloadUrlMemoryAsString(url);
        if (latestFullSUM != null){
            try {
                String fileSUM = getFileSHA256(new File(fn),
                        getSUMProgress(STATE_ACTION_CHECKING_SUM,
                        new File(fn).getName()));
                if (latestFullSUM.equals(fileSUM)) {
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
            }
        } finally {
            (new File(tempFiles[0])).delete();
            (new File(tempFiles[1])).delete();
        }

        return true;
    }

    private void writeString(OutputStream os, String s)
            throws IOException {
        os.write((s + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private String handleUpdateCleanup() throws FileNotFoundException {
        String flashFilename = prefs.getString(PREF_READY_FILENAME_NAME, null);
        String initialFile = prefs.getString(PREF_INITIAL_FILE, null);
        boolean fileFlash =  prefs.getBoolean(PREF_FILE_FLASH, true);

        if (flashFilename == null
                || (!fileFlash && !flashFilename.startsWith(config.getPathBase()))
                || !new File(flashFilename).exists()) {
            throw new FileNotFoundException("flashUpdate - no valid file to flash found " + flashFilename);
        }
        // now delete the initial file
        if (initialFile != null
                && new File(initialFile).exists()
                && initialFile.startsWith(config.getPathBase())){
            new File(initialFile).delete();
            Logger.d("flashUpdate - delete initial file");
        }

        return flashFilename;
    }

    protected void onUpdateCompleted(int status, int errorCode) {
        stopNotification();
        if (status == UpdateEngine.ErrorCodeConstants.SUCCESS) {
            prefs.edit().putBoolean(PREF_PENDING_REBOOT, true).commit();
            String flashFilename = prefs.getString(PREF_READY_FILENAME_NAME, null);
            if (flashFilename != null) {
                deleteOldFlashFile(flashFilename);
                prefs.edit().putString(PREF_CURRENT_FILENAME_NAME, flashFilename).commit();
            }
            startABRebootNotification(flashFilename);
            updateState(STATE_ACTION_AB_FINISHED, null, null, null, null, null);
        } else {
            updateState(STATE_ERROR_AB_FLASH, null, null, null, null, null, errorCode);
        }
    }

    private synchronized void setFlashNotificationProgress(int percent, int sec) {
        // max progress is 100%
        mFlashNotificationBuilder.setProgress(100, percent, false);
        String sub = "0%";
        if (percent > 0) {
            sub = String.format(Locale.ENGLISH,
                                    getString(R.string.notify_eta_remaining),
                                    percent, sec / 60, sec % 60);
        }
        mFlashNotificationBuilder.setSubText(sub);
        notificationManager.notify(
                    NOTIFICATION_UPDATE, mFlashNotificationBuilder.build());
    }

    private synchronized void setDownloadNotificationProgress(float progress, long current, long total, long ms) {
        // max progress is 100%
        int percent = Math.round(progress);
        mDownloadNotificationBuilder.setProgress(100, percent, false);
        // long --> int overflows FTL (progress.setXXX)
        boolean progressInK = false;
        if (total > 1024L * 1024L * 1024L) {
            progressInK = true;
            current /= 1024L;
            total /= 1024L;
        }
        String sub = "";
        if ((ms > 500) && (current > 0) && (total > 0)) {
            float kibps = ((float) current / 1024f)
                    / ((float) ms / 1000f);
            if (progressInK)
                kibps *= 1024f;
            int sec = (int) (((((float) total / (float) current) * (float) ms) - ms) / 1000f);
            if (kibps < 1024) {
                sub = String.format(Locale.ENGLISH,
                        "%2d%% · %.0f KiB/s · %02d:%02d",
                        percent, kibps, sec / 60, sec % 60);
            } else {
                sub = String.format(Locale.ENGLISH,
                        "%2d%% · %.0f MiB/s · %02d:%02d",
                        percent, kibps / 1024f, sec / 60, sec % 60);
            }
        }
        if (sub.isEmpty()) sub = String.format(Locale.ENGLISH,
                "%2d%%", percent);
        mDownloadNotificationBuilder.setSubText(sub);
        notificationManager.notify(
                NOTIFICATION_BUSY, mDownloadNotificationBuilder.build());
    }

    private void flashABUpdate() {
        Logger.d("flashABUpdate");
        String flashFilename;
        try {
            flashFilename = handleUpdateCleanup();
        } catch (Exception ex) {
            updateState(STATE_ERROR_AB_FLASH, null, null, null, null, null);
            Logger.ex(ex);
            return;
        }

        // Save the filename for resuming
        prefs.edit().putString(PREF_CURRENT_AB_FILENAME_NAME, flashFilename).commit();

        // Clear the Download size to hide while flashing
        prefs.edit().putLong(PREF_DOWNLOAD_SIZE, -1).commit();

        final String _filename = new File(flashFilename).getName();
        updateState(STATE_ACTION_AB_FLASH, 0f, 0L, 100L, _filename, null);

        newFlashNotification(_filename);

        try {
            ZipFile zipFile = new ZipFile(flashFilename);
            boolean isABUpdate = ABUpdate.isABUpdate(zipFile);
            zipFile.close();
            if (isABUpdate) {
                mLastProgressTime = new long[] { 0, SystemClock.elapsedRealtime() };
                mProgressListener.setStatus(_filename);
                if (!ABUpdate.start(flashFilename, mProgressListener, this)) {
                    stopNotification();
                    updateState(STATE_ERROR_AB_FLASH, null, null, null, null, null);
                }
            } else {
                stopNotification();
                updateState(STATE_ERROR_AB_FLASH, null, null, null, null, null);
            }
        } catch (Exception ex) {
            Logger.ex(ex);
        }
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
        String flashFilename;
        try {
            flashFilename = handleUpdateCleanup();
        } catch (Exception ex) {
            updateState(STATE_ERROR_FLASH, null, null, null, null, null);
            Logger.ex(ex);
            return;
        }

        deleteOldFlashFile(flashFilename);
        prefs.edit().putString(PREF_CURRENT_FILENAME_NAME, flashFilename).commit();
        clearState();

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
            if (config.getUseTWRP()) {
                if (config.getInjectSignatureEnable() && deltaSignature) {
                    Logger.d("flashUpdate - create /cache/recovery/keys");

                    try (FileOutputStream os = new FileOutputStream(
                            "/cache/recovery/keys", false)) {
                        writeString(os, config.getInjectSignatureKeys());
                    }
                    setPermissions("/cache/recovery/keys",
                            Process.myUid()  /* AID_CACHE */);
                }

                Logger.d("flashUpdate - create /cache/recovery/openrecoveryscript");

                try (FileOutputStream os = new FileOutputStream(
                        "/cache/recovery/openrecoveryscript", false)) {
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
                }

                setPermissions("/cache/recovery/openrecoveryscript",
                        Process.myUid()  /* AID_CACHE */);

                Logger.d("flashUpdate - reboot to recovery");
                ((PowerManager) getSystemService(Context.POWER_SERVICE))
                        .rebootCustom(PowerManager.REBOOT_RECOVERY);
            } else {
                // AOSP recovery and derivatives
                // First copy the file to cache and decrypt it
                // Finally tell RecoverySystem to flash it via recovery
                FileChannel srcCh = null;
                FileChannel dstCh = null;
                File dst = new File(path_sd + "ota_package.zip.uncrypt");
                dst.setReadable(true, false);
                dst.setWritable(true, false);
                dst.setExecutable(true, false);
                try {
                    Logger.d("flashUpdate - copying A-only OTA package: "
                            + dst.getAbsolutePath());
                    File src = new File(path_sd + flashFilename);
                    srcCh = new FileInputStream(src).getChannel();
                    dstCh = new FileOutputStream(dst, false).getChannel();
                    dstCh.transferFrom(srcCh, 0, srcCh.size());
                    srcCh.close(); srcCh = null;
                    dstCh.close(); dstCh = null;
                    Logger.d("flashUpdate - installing A-only OTA package");
                    RecoverySystem.installPackage(this, dst);
                } catch (Exception e) {
                    dst.delete();
                    Logger.d("flashUpdate - Could not install OTA package:");
                    Logger.ex(e);
                    updateState(STATE_ERROR_FLASH, null, null, null, null, null);
                } finally {
                    if (srcCh != null) srcCh.close();
                    if (dstCh != null) dstCh.close();
                }
            }
        } catch (Exception e) {
            // We have failed to write something. There's not really anything
            // else to do at this stage than give up. No reason to crash though.
            Logger.ex(e);
            updateState(STATE_ERROR_FLASH, null, null, null, null, null);
        }
    }

    private boolean updateAvailable() {
        final String latestFull = prefs.getString(UpdateService.PREF_LATEST_FULL_NAME, null);
        final String latestDelta = prefs.getString(UpdateService.PREF_LATEST_DELTA_NAME, null);
        return latestFull != null || latestDelta != null;
    }

    private String getLatestFullSHA256Sum(String sumUrl) {
        String urlSuffix = config.getUrlSuffix();
        if (isUrlOverride) {
            sumUrl = sumUrlOvr;
        } else if (urlSuffix.length() > 0) {
            sumUrl += config.getUrlSuffix();
        }
        String latestFullSum = downloadUrlMemoryAsString(sumUrl);
        if (latestFullSum != null) {
            String sumPart = latestFullSum;
            while (sumPart.length() > 64)
                sumPart = sumPart.substring(0, sumPart.length() - 1);
            Logger.d("getLatestFullSHA256Sum - sha256sum = " + sumPart);
            return sumPart;
        }
        return null;
    }

    private float getProgress(long current, long total) {
        if (total == 0)
            return 0f;
        return ((float) current / (float) total) * 100f;
    }

    // need to locally here for the deltas == 0 case
    private String getFileSHA256(File file, ProgressListener progressListener) {
        String ret = null;

        long current = 0;
        long total = file.length();
        if (progressListener != null)
            progressListener.onProgress(getProgress(current, total), current, total);

        try {
            try (FileInputStream is = new FileInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[256 * 1024];
                int r;

                while ((r = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, r);
                    current += r;
                    if (progressListener != null)
                        progressListener.onProgress(getProgress(current, total), current, total);
                }

                //while (SUM.length() < 32)
                //     SUM = "0" + SUM;
                ret = new BigInteger(1, digest.digest()).
                        toString(16).toLowerCase(Locale.ENGLISH);
                Logger.d("sha256sum from file is: " + ret);
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            // No SHA256 support (returns null)
            // The SHA256 of a non-existing file is null
            // Read or close error (returns null)
            Logger.ex(e);
        }

        if (progressListener != null)
            progressListener.onProgress(getProgress(total, total), total, total);

        return ret;
    }

    private boolean isSupportedVersion() {
        return config.isOfficialVersion();
    }

    private int getAutoDownloadValue() {
        String autoDownload = prefs.getString(SettingsActivity.PREF_AUTO_DOWNLOAD, getDefaultAutoDownloadValue());
        return Integer.parseInt(autoDownload);
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
        return state.equals(UpdateService.STATE_ACTION_DOWNLOADING) ||
                state.equals(UpdateService.STATE_ACTION_SEARCHING) ||
                state.equals(UpdateService.STATE_ACTION_SEARCHING_SUM) ||
                state.equals(UpdateService.STATE_ACTION_CHECKING) ||
                state.equals(UpdateService.STATE_ACTION_CHECKING_SUM) ||
                state.equals(UpdateService.STATE_ACTION_APPLYING) ||
                state.equals(UpdateService.STATE_ACTION_APPLYING_SUM) ||
                state.equals(UpdateService.STATE_ACTION_APPLYING_PATCH) ||
                state.equals(UpdateService.STATE_ACTION_AB_FLASH);
    }

    public static boolean isErrorState(String state) {
        return state.equals(UpdateService.STATE_ERROR_DOWNLOAD) ||
                state.equals(UpdateService.STATE_ERROR_DISK_SPACE) ||
                state.equals(UpdateService.STATE_ERROR_UNKNOWN) ||
                state.equals(UpdateService.STATE_ERROR_UNOFFICIAL) ||
                state.equals(UpdateService.STATE_ERROR_CONNECTION) ||
                state.equals(UpdateService.STATE_ERROR_AB_FLASH) ||
                state.equals(UpdateService.STATE_ERROR_FLASH_FILE) ||
                state.equals(UpdateService.STATE_ERROR_FLASH);
    }

    private boolean isSnoozeNotification() {
        // check if we're snoozed, using abs for clock changes
        boolean timeSnooze = Math.abs(System.currentTimeMillis()
                - prefs.getLong(PREF_LAST_SNOOZE_TIME_NAME,
                        PREF_LAST_SNOOZE_TIME_DEFAULT)) <= SNOOZE_MS;
        if (timeSnooze) {
            String lastBuild = prefs.getString(PREF_LATEST_FULL_NAME, null);
            String snoozeBuild = prefs.getString(PREF_SNOOZE_UPDATE_NAME, null);
            if (lastBuild != null && snoozeBuild != null) {
                // only snooze if time snoozed and no newer update available
                if (!lastBuild.equals(snoozeBuild)) {
                    return false;
                }
            }
        }
        return timeSnooze;
    }

    private void clearState() {
        prefs.edit().putString(PREF_LATEST_FULL_NAME, null).commit();
        prefs.edit().putString(PREF_LATEST_DELTA_NAME, null).commit();
        prefs.edit().putString(PREF_READY_FILENAME_NAME, null).commit();
        prefs.edit().putLong(PREF_DOWNLOAD_SIZE, -1).commit();
        prefs.edit().putBoolean(PREF_DELTA_SIGNATURE, false).commit();
        prefs.edit().putString(PREF_INITIAL_FILE, null).commit();
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
        Logger.d("checkForUpdatesAsync " + getPrefs().getAll());

        updateState(STATE_ACTION_CHECKING, null, null, null, null, null);
        wakeLock.acquire();
        wifiLock.acquire();

        String notificationText = getString(R.string.state_action_checking);
        if (checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
            notificationText = getString(R.string.state_action_downloading);
        }
        mDownloadNotificationBuilder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        mDownloadNotificationBuilder.setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(notificationText)
                .setShowWhen(false)
                .setOngoing(true)
                .setContentIntent(getNotificationIntent(false));

        handler.post(() -> {
            boolean downloadFullBuild = false;

            stopDownload = false;
            updateRunning = true;

            try {
                List<DeltaInfo> deltas = new ArrayList<>();

                String flashFilename = null;
                (new File(config.getPathBase())).mkdir();
                (new File(config.getPathFlashAfterUpdate())).mkdir();

                clearState();

                List<String> latestFullBuildWithUrl = getNewestFullBuild();
                String latestFullBuild;
                // if we don't even find a build on dl no sense to continue
                if (latestFullBuildWithUrl == null || latestFullBuildWithUrl.size() == 0) {
                    Logger.d("no latest build found at " + config.getUrlBaseJson() +
                            " for " + config.getDevice());
                    return;
                }
                latestFullBuild = latestFullBuildWithUrl.get(0);

                String latestFullFetch;
                String latestFullFetchSUM = null;
                if (latestFullBuildWithUrl.size() < 3) {
                    latestFullFetch = config.getUrlBaseFull() +
                            latestFullBuild + config.getUrlSuffix();
                    latestFullFetchSUM = config.getUrlBaseFullSum() +
                            latestFullBuild + ".sha256sum" + config.getUrlSuffix();
                }
                else {
                    latestFullFetch = latestFullBuildWithUrl.get(1);
                }
                Logger.d("latest full build for device " + config.getDevice() + " is " + latestFullFetch);
                prefs.edit().putString(PREF_LATEST_FULL_NAME, latestFullBuild).commit();

                if (!Config.isABDevice()) {
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
                            } catch (JSONException | NullPointerException e) {
                                // There's an error in the JSON. Could be bad JSON,
                                // could be a 404 text, etc
                                Logger.ex(e);
                                delta = null;
                            } // Download failed

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
                                } catch (JSONException | NullPointerException e) {
                                    // There's an error in the JSON. Could be bad
                                    // JSON, could be a 404 text, etc
                                    Logger.ex(e);
                                    delta = null;
                                } // Download failed

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
                                        getSUMProgress(STATE_ACTION_CHECKING_SUM, di.getOut()
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
                        deltas.subList(0, last + 1).clear();
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
                    final String latestFull = prefs.getString(PREF_LATEST_FULL_NAME, null);
                    String currentVersionZip = config.getFilenameBase() +".zip";

                    long currFileDate; // will store current build date as YYYYMMDD
                    long latestFileDate; // will store latest build date as YYYYMMDD
                    boolean updateAvailable = false;
                    if (latestFull != null) {
                        try {
                            currFileDate = Long.parseLong(currentVersionZip.split("-")[4].substring(0, 8));
                            latestFileDate = Long.parseLong(latestFull.split("-")[4].substring(0, 8));
                            updateAvailable = latestFileDate > currFileDate;
                        } catch (NumberFormatException exception) {
                            // Just incase someone decides to make up his own zip / build name and F's this up
                            Logger.d("Build name malformed");
                            Logger.ex(exception);
                        }
                        downloadFullBuild = updateAvailable;
                    }

                    if (!updateAvailable) {
                        prefs.edit().putString(PREF_LATEST_FULL_NAME, null).commit();
                    }

                    if (downloadFullBuild) {
                        String fn = config.getPathBase() + latestFullBuild;
                        if (new File(fn).exists()) {
                            boolean directSUM = latestFullBuildWithUrl.size() == 3;
                            if (checkFullBuildSHA256Sum(
                                    (directSUM ? latestFullBuildWithUrl.get(2) : latestFullFetchSUM),
                                    fn, directSUM)) {
                                Logger.d("match found (full): " + fn);
                                prefs.edit().putString(PREF_READY_FILENAME_NAME, fn).commit();
                                downloadFullBuild = false;
                            } else {
                                Logger.d("sha256sum check failed : " + fn);
                            }
                        }
                    }
                    if (updateAvailable && downloadFullBuild) {
                        long size = getUrlDownloadSize(latestFullFetch);
                        prefs.edit().putLong(PREF_DOWNLOAD_SIZE, size).commit();
                    }
                    Logger.d("check donne: latest full build available = " + prefs.getString(PREF_LATEST_FULL_NAME, null) +
                            " : updateAvailable = " + updateAvailable + " : downloadFullBuild = " + downloadFullBuild);

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
                    String initialFile;
                    boolean initialFileNeedsProcessing;
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
                    boolean betterDownloadFullBuild = deltaDownloadSize > fullDownloadSize;

                    final String latestFull = prefs.getString(PREF_LATEST_FULL_NAME, null);
                    final String latestDelta = flashFilename;

                    String latestDeltaZip = latestDelta != null ? new File(latestDelta).getName() : null;
                    String latestFullZip = latestFull;
                    String currentVersionZip = config.getFilenameBase() +".zip";
                    boolean fullUpdatePossible = latestFullZip != null && Long.parseLong(latestFullZip.replaceAll("\\D+","")) > Long.parseLong(currentVersionZip.replaceAll("\\D+",""));
                    boolean deltaUpdatePossible = initialFile != null && latestDeltaZip != null && Long.parseLong(latestDeltaZip.replaceAll("\\D+","")) > Long.parseLong(currentVersionZip.replaceAll("\\D+","")) && latestDeltaZip.equals(latestFullZip);

                    // is the full version newer then what we could create with delta?
                    if (latestFullZip.compareTo(latestDeltaZip) > 0) {
                        betterDownloadFullBuild = true;
                    }

                    Logger.d("latestDeltaZip = " + latestDeltaZip + " currentVersionZip = " + currentVersionZip + " latestFullZip = " + latestFullZip);

                    Logger.d("deltaUpdatePossible = " + deltaUpdatePossible + " fullUpdatePossible = " + fullUpdatePossible + " betterDownloadFullBuild = " + betterDownloadFullBuild);

                    if (!deltaUpdatePossible || (betterDownloadFullBuild && fullUpdatePossible)) {
                        downloadFullBuild = true;
                    }
                    boolean updateAvailable = fullUpdatePossible || deltaUpdatePossible;

                    if (!updateAvailable) {
                        prefs.edit().putString(PREF_LATEST_DELTA_NAME, null).commit();
                        prefs.edit().putString(PREF_LATEST_FULL_NAME, null).commit();
                    } else {
                        if (downloadFullBuild) {
                            prefs.edit().putString(PREF_LATEST_DELTA_NAME, null).commit();
                        } else {
                            prefs.edit().putString(PREF_LATEST_DELTA_NAME, new File(flashFilename).getName()).commit();
                        }
                    }

                    if (downloadFullBuild) {
                        String fn = config.getPathBase() + latestFullBuild;
                        if (new File(fn).exists()) {
                            boolean directSUM = latestFullBuildWithUrl.size() == 3;
                            if (checkFullBuildSHA256Sum(
                                    (directSUM ? latestFullBuildWithUrl.get(2) : latestFullFetchSUM),
                                    fn, directSUM)) {
                                Logger.d("match found (full): " + fn);
                                prefs.edit().putString(PREF_READY_FILENAME_NAME, fn).commit();
                                downloadFullBuild = false;
                            } else {
                                Logger.d("sha256sum check failed : " + fn);
                            }
                        }
                    }
                    if (updateAvailable) {
                        if (deltaUpdatePossible) {
                            prefs.edit().putLong(PREF_DOWNLOAD_SIZE, deltaDownloadSize).commit();
                        } else if (downloadFullBuild) {
                            prefs.edit().putLong(PREF_DOWNLOAD_SIZE, fullDownloadSize).commit();
                        }
                    }
                    Logger.d("check donne: latest valid delta update = " + prefs.getString(PREF_LATEST_DELTA_NAME, null) +
                            " : latest full build available = " + prefs.getString(PREF_LATEST_FULL_NAME, null) +
                            " : updateAvailable = " + updateAvailable + " : downloadFullBuild = " + downloadFullBuild);

                    long requiredSpace = getRequiredSpace(deltas, downloadFullBuild);
                    long freeSpace = (new StatFs(config.getPathBase())).getAvailableBytes();
                    Logger.d("requiredSpace = " + requiredSpace + " freeSpace = " + freeSpace);

                    if (freeSpace < requiredSpace) {
                        updateState(STATE_ERROR_DISK_SPACE, null, freeSpace, requiredSpace,
                                null, null);
                        Logger.d("not enough space!");
                        return;
                    }

                    if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK) {
                        return;
                    }
                    long downloadSize = downloadFullBuild ? fullDownloadSize : deltaDownloadSize;

                    if (!downloadFullBuild && checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
                        // Download all the files we do not have yet
                        // getFull = false since full download is handled below
                        if (!downloadFiles(deltas, downloadSize, userInitiated))
                            return;

                        // Reconstruct flashable ZIP
                        if (!applyPatches(deltas, initialFile, initialFileNeedsProcessing))
                            return;

                        // Verify using SHA256
                        if (lastDelta.getOut().match(
                                new File(config.getPathBase() + lastDelta.getOut().getName()),
                                true,
                                getSUMProgress(STATE_ACTION_APPLYING_SUM, lastDelta.getOut()
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
                    if (userInitiated || networkState.getState()) {
                        String latestFullSUM = getLatestFullSHA256Sum(latestFullFetchSUM);
                        if (latestFullSUM != null) {
                            downloadFullBuild(latestFullFetch, latestFullSUM, latestFullBuild); // download full
                        } else {
                            updateState(STATE_ERROR_DOWNLOAD, null, null, null, null, null);
                            Logger.d("aborting download due to sha256sum not found");
                        }
                    } else {
                        updateState(STATE_ERROR_DOWNLOAD, null, null, null, null, null);
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
                    clearState();
                    if (!userInitiated) {
                        shouldShowErrorNotification();
                    }
                } else {
                    failedUpdateCount = 0;
                    autoState(userInitiated, checkOnly, true);
                }
                updateRunning = false;
            }
        });
    }

    private boolean checkPermissions() {
        if (!Environment.isExternalStorageManager()) {
            Logger.d("checkPermissions failed");
            updateState(STATE_ERROR_PERMISSIONS, null, null, null, null, null);
            return false;
        }
        return true;
    }

    private void deleteOldFlashFile(String newFlashFilename) {
        String oldFlashFilename = prefs.getString(PREF_CURRENT_FILENAME_NAME, null);
        Logger.d("delete oldFlashFilename " + oldFlashFilename + " " + newFlashFilename);

        if (oldFlashFilename != null && !oldFlashFilename.equals(newFlashFilename)
                && oldFlashFilename.startsWith(config.getPathBase())) {
            File file = new File(oldFlashFilename);
            if (file.exists()) {
                Logger.d("delete oldFlashFilename " + oldFlashFilename);
                file.delete();
            }
        }
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }

    public Config getConfig() {
        return config;
    }

    private void setFlashFilename(String flashFilename) {
        Logger.d("Flash file set: %s", flashFilename);
        File fn = new File(flashFilename);
        if (!fn.exists()) {
            updateState(STATE_ERROR_FLASH_FILE, null, null, null, null, null);
            return;
        }
        if (!fn.getName().endsWith(".zip")) {
            updateState(STATE_ERROR_FLASH_FILE, null, null, null, null, null);
            return;
        }
        Logger.d("Set flash possible: %s", flashFilename);
        prefs.edit().putString(PREF_READY_FILENAME_NAME, flashFilename).commit();
        updateState(STATE_ACTION_FLASH_FILE_READY, null, null, null, (new File(flashFilename)).getName(), null);
    }

    private void createNotificationChannel() {
        CharSequence name = getString(R.string.channel_name);
        String description = getString(R.string.channel_description);
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        notificationManager.createNotificationChannel(channel);
    }
}
