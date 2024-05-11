/*
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2015 The OmniROM Project
 * Copyright (C) 2020-2022 Yet Another AOSP Project
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
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
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

import eu.chainfire.opendelta.State.StateInt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.StringBuilder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class UpdateService extends Service implements OnSharedPreferenceChangeListener {

    public static void start(Context context) {
        start(context, null);
    }

    public static void startClearRunningInstall(Context context) {
        start(context, ACTION_CLEAR_INSTALL_RUNNING);
    }

    public static void start(Context context, String action) {
        Intent i = new Intent(context, UpdateService.class);
        i.setAction(action);
        context.startService(i);
    }

    public interface ProgressListener {
        void onProgress(float progress, long current, long total);
        void setStatus(String status);
    }

    public interface CheckForUpdateListener {
        void onCheckDone(State state);
    }

    public static final String ACTION_SYSTEM_UPDATE_SETTINGS = "android.settings.SYSTEM_UPDATE_SETTINGS";
    public static final String PERMISSION_ACCESS_CACHE_FILESYSTEM = "android.permission.ACCESS_CACHE_FILESYSTEM";
    public static final String PERMISSION_REBOOT = "android.permission.REBOOT";

    public static final String BROADCAST_INTENT = "eu.chainfire.opendelta.intent.BROADCAST_STATE";
    public static final String EXTRA_STATE = "eu.chainfire.opendelta.extra.ACTION_STATE";
    public static final String EXTRA_FILENAME = "eu.chainfire.opendelta.extra.FILENAME";

    public static final String ACTION_CHECK = "eu.chainfire.opendelta.action.CHECK";
    public static final String ACTION_FORCE_FLASH = "eu.chainfire.opendelta.action.FORCE_FLASH";
    public static final String ACTION_DOWNLOAD = "eu.chainfire.opendelta.action.DOWNLOAD";
    public static final String ACTION_DOWNLOAD_STOP = "eu.chainfire.opendelta.action.DOWNLOAD_STOP";
    public static final String ACTION_DOWNLOAD_PAUSE = "eu.chainfire.opendelta.action.DOWNLOAD_PAUSE";
    public static final String ACTION_FLASH = "eu.chainfire.opendelta.action.FLASH";
    public static final String ACTION_STREAM = "eu.chainfire.opendelta.action.STREAM";
    public static final String ACTION_ALARM = "eu.chainfire.opendelta.action.ALARM";
    public static final String ACTION_SCHEDULER = "eu.chainfire.opendelta.action.SCHEDULER";
    public static final String EXTRA_ALARM_ID = "eu.chainfire.opendelta.extra.ALARM_ID";
    private static final String ACTION_NOTIFICATION_DELETED = "eu.chainfire.opendelta.action.NOTIFICATION_DELETED";
    static final String ACTION_CLEAR_INSTALL_RUNNING =
            "eu.chainfire.opendelta.action.ACTION_CLEAR_INSTALL_RUNNING";
    public static final String ACTION_FLASH_FILE = "eu.chainfire.opendelta.action.FLASH_FILE";

    private static final String INSTALL_NOTIFICATION_CHANNEL_ID = "eu.chainfire.opendelta.notification.install";
    private static final String UPDATE_NOTIFICATION_CHANNEL_ID = "eu.chainfire.opendelta.notification.update";
    public static final int NOTIFICATION_BUSY = 1;
    public static final int NOTIFICATION_UPDATE = 2;
    public static final int NOTIFICATION_ERROR = 3;

    private static final String PAYLOAD_PROP_OFFSET = "offset=";
    private static final String PAYLOAD_PROP_SIZE = "FILE_SIZE=";

    public static final String PREF_READY_FILENAME_NAME = "ready_filename";
    public static final String PREF_LATEST_CHANGELOG = "latest_changelog";

    public static final String PREF_LAST_CHECK_TIME_NAME = "last_check_time";
    public static final long PREF_LAST_CHECK_TIME_DEFAULT = 0L;

    public static final String PREF_LAST_DOWNLOAD_TIME = "last_spent_download_time";
    private static final String PREF_LAST_SNOOZE_TIME_NAME = "last_snooze_time";
    private static final long PREF_LAST_SNOOZE_TIME_DEFAULT = 0L;
    // we only snooze until a new build
    private static final String PREF_SNOOZE_UPDATE_NAME = "last_snooze_update";

    public static final String PREF_PENDING_REBOOT = "pending_reboot";

    private static final String PREF_CURRENT_AB_FILENAME_NAME = "current_ab_filename";
    public static final String PREF_CURRENT_FILENAME_NAME = "current_filename";
    public static final String PREF_FILE_FLASH = "file_flash";

    private static final long SNOOZE_MS = AlarmManager.INTERVAL_HALF_DAY;

    public static final String PREF_AUTO_UPDATE_METERED_NETWORKS = "auto_update_metered_networks";

    public static final String PREF_LATEST_FULL_NAME = "latest_full_name";
    public static final String PREF_LATEST_PAYLOAD_PROPS = "latest_payload_props";
    public static final String PREF_DOWNLOAD_SIZE = "download_size_long";

    public static final int PREF_AUTO_DOWNLOAD_DISABLED = 0;
    public static final int PREF_AUTO_DOWNLOAD_CHECK = 1;
    public static final int PREF_AUTO_DOWNLOAD_FULL = 2;

    private Config mConfig;

    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private final State mState = State.getInstance();
    private Download mDownload;

    private NetworkState mNetworkState;
    private BatteryState mBatteryState;
    private ScreenState mScreenState;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    private NotificationManager mNotificationManager;
    private boolean mIsUpdateRunning;
    private int mFailedUpdateCount;
    private SharedPreferences mPrefs;
    private Notification.Builder mFlashNotificationBuilder;
    private Notification.Builder mDownloadNotificationBuilder;

    private List<CheckForUpdateListener> mCheckForUpdateListeners = new ArrayList<>();

    // url override
    private boolean mIsUrlOverride;
    private String mSumUrlOvr;

    private long[] mLastProgressTime;
    private final ProgressListener mProgressListener = new ProgressListener() {
        private String status;

        @Override
        public void onProgress(float progress, long current, long total) {
            if (mLastProgressTime == null)
                mLastProgressTime = mLastProgressTime = new long[] { 0, SystemClock.elapsedRealtime() };
            long now = SystemClock.elapsedRealtime();
            if (now >= mLastProgressTime[0] + 250L) {
                long ms = SystemClock.elapsedRealtime() - mLastProgressTime[1];
                int sec = (int) (((((float) total / (float) current) * (float) ms) - ms) / 1000f);
                mState.update(State.ACTION_AB_FLASH, progress, current, total, this.status, ms);
                setFlashNotificationProgress((int) progress, sec);
                mLastProgressTime[0] = now;
            }
        }

        public void setStatus(String status) {
            this.status = status;
        }
    };

    private final State.StateCallback mStopWhenDoneCallback =
            new State.StateCallback() {
        @Override
        public void update(@StateInt int state, Float progress,
                Long current, Long total, String filename,
                Long ms, int errorCode) {
            if (State.isProgressState(state) || mIsUpdateRunning)
                return;
            Logger.d("Stopping service");
            mState.removeStateCallback(this);
            stopForeground(STOP_FOREGROUND_DETACH); // keep notifications
            stopSelf();
        }
    };

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        UpdateService getService() {
            return UpdateService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // activity is destroyed
        // wait for any current progress to end and close
        mState.addStateCallback(mStopWhenDoneCallback);
        return super.onUnbind(intent);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onCreate() {
        super.onCreate();

        mConfig = Config.getInstance(this);

        mWakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenDelta:WakeLock");
        mWifiLock = ((WifiManager) getSystemService(WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, "OpenDelta:WifiLock");

        mHandlerThread = new HandlerThread("OpenDelta Service Thread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        createInstallNotificationChannel();
        createUpdateNotificationChannel();

        int autoDownload = getAutoDownloadValue();
        if (autoDownload != PREF_AUTO_DOWNLOAD_DISABLED) {
            Scheduler.start(this, Scheduler.ACTION_SCHEDULER_START);
        }
        mNetworkState = new NetworkState();
        mNetworkState.start(this, null);

        mBatteryState = new BatteryState();
        mBatteryState.start(this, null,
                Integer.parseInt(mPrefs.getString(SettingsActivity.PREF_BATTERY_LEVEL, "50")),
                mPrefs.getBoolean(SettingsActivity.PREF_CHARGE_ONLY, true));

        mScreenState = new ScreenState();
        mScreenState.start(this, null);

        mPrefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        mNetworkState.stop();
        mBatteryState.stop();
        mScreenState.stop();
        mHandlerThread.quitSafely();

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d("Starting service");
        if (intent != null) {
            performAction(intent);
        }
        return START_REDELIVER_INTENT;
    }

    public synchronized void performAction(Intent intent) {
        String action = intent.getAction();
        if (action == null) action = "";
        switch (action) {
            case ACTION_CLEAR_INSTALL_RUNNING:
                // at boot
                clearState();
                mIsUpdateRunning = false;
                ABUpdate.setInstallingUpdate(false, this);
                if (getAutoDownloadValue() != PREF_AUTO_DOWNLOAD_DISABLED &&
                        Scheduler.isTimePassed(mPrefs) && !Scheduler.isCustomAlarm(mPrefs)
                        && onWantUpdateCheck()) {
                    // scheduler check interval time passed after boot
                    // checkForUpdatesAsync will stopSelf for us
                    break;
                }
                // always stop after boot receiver has done its thing
                stopSelf();
                break;
            case ACTION_CHECK:
                if (checkPermissions())
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_CHECK);
                break;
            case ACTION_FORCE_FLASH:
                if (checkPermissions())
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_CHECK, true);
                break;
            case ACTION_DOWNLOAD:
                if (checkPermissions())
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_FULL);
                break;
            case ACTION_DOWNLOAD_STOP:
                final boolean pendingReboot = mPrefs.getBoolean(PREF_PENDING_REBOOT, false);
                if (pendingReboot || ABUpdate.isInstallingUpdate(this)) {
                    ABUpdate.getInstance(this).stop(pendingReboot);
                    mNotificationManager.cancelAll();
                    mIsUpdateRunning = false;
                    clearState();
                    autoState(false);
                    break;
                }

                if (mDownload != null) mDownload.stop();
                if (mNotificationManager != null)
                    mNotificationManager.cancel(NOTIFICATION_BUSY);
                // if we have a paused download in progress we need to manually stop it
                if (mState.equals(State.ERROR_DOWNLOAD_RESUME) ||
                        mState.equals(State.ACTION_DOWNLOADING_PAUSED)) {
                    // to do so we just need to remove the file and update state
                    File[] files = new File(mConfig.getPathBase()).listFiles();
                    if (files != null && files.length > 0)
                        for (File file : files)
                            if (file.isFile() && file.getName().endsWith(".part"))
                                file.delete();
                    autoState(false);
                }
                break;
            case ACTION_DOWNLOAD_PAUSE:
                if (ABUpdate.isInstallingUpdate(this)) {
                    ABUpdate.getInstance(this).suspend();
                    if (ABUpdate.isSuspended(this)) {
                        mNotificationManager.cancelAll();
                        mState.update(State.ACTION_AB_PAUSED);
                    } else {
                        autoState(false);
                    }
                    break;
                }

                final boolean isPaused = mState.equals(State.ACTION_DOWNLOADING_PAUSED) ||
                        mState.equals(State.ERROR_DOWNLOAD_RESUME);
                if (isPaused) {
                    // resume
                    if (mDownload != null) mDownload.resetState();
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_FULL);
                } else {
                    // pause
                    if (mDownload != null) mDownload.pause();
                    autoState(false);
                }
                break;
            case ACTION_FLASH:
                if (checkPermissions()) {
                    if (Config.isABDevice()) flashABUpdate();
                    else flashUpdate();
                }
                break;
            case ACTION_STREAM:
                if (checkPermissions()) flashABUpdate(true);
                break;
            case ACTION_FLASH_FILE:
                if (intent.hasExtra(EXTRA_FILENAME)) {
                    String flashFilename = intent.getStringExtra(EXTRA_FILENAME);
                    setFlashFilename(flashFilename);
                }
                break;
            case ACTION_SCHEDULER:
                // see comment in ACTION_CLEAR_INSTALL_RUNNING
                if (!onWantUpdateCheck()) stopSelf();
                break;
            case ACTION_ALARM:
                Scheduler.start(this, Scheduler.ACTION_SCHEDULER_ALARM,
                        intent.getIntExtra(EXTRA_ALARM_ID, -1));
                break;
            case ACTION_NOTIFICATION_DELETED:
                mPrefs.edit().putLong(PREF_LAST_SNOOZE_TIME_NAME,
                        System.currentTimeMillis()).apply();
                String lastBuild = mPrefs.getString(PREF_LATEST_FULL_NAME, null);
                if (lastBuild != null) {
                    // only snooze until no newer build is available
                    Logger.i("Snoozing notification for " + lastBuild);
                    mPrefs.edit().putString(PREF_SNOOZE_UPDATE_NAME, lastBuild).apply();
                }
                break;
            default:
                autoState(false);
                break;
        }
    }

    public State getState() {
        return mState;
    }

    public boolean onWantUpdateCheck() {
        return onWantUpdateCheck(false);
    }

    public boolean onWantUpdateCheck(boolean qs) {
        if (mState.isProgressState()) {
            Logger.i("Blocked scheduler requests while running in state " + mState);
            return false;
        }
        if (qs) {
            Logger.i("QS tile requests check for updates");
        } else {
            Logger.i("Scheduler requests check for updates");
        }
        final int autoDownload = qs ? PREF_AUTO_DOWNLOAD_CHECK : getAutoDownloadValue();
        if (autoDownload != PREF_AUTO_DOWNLOAD_DISABLED) {
            return checkForUpdates(false, autoDownload);
        }
        return false;
    }

    public void addCheckForUpdateListener(CheckForUpdateListener listener) {
        if (mCheckForUpdateListeners.contains(listener)) return;
        mCheckForUpdateListeners.add(listener);
    }

    public void removeCheckForUpdateListener(CheckForUpdateListener listener) {
        if (!mCheckForUpdateListeners.contains(listener)) return;
        mCheckForUpdateListeners.remove(listener);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Logger.d("onSharedPreferenceChanged " + key);
        switch (key) {
            case PREF_AUTO_UPDATE_METERED_NETWORKS:
                mNetworkState.setMeteredAllowed(sharedPreferences.getBoolean(
                        PREF_AUTO_UPDATE_METERED_NETWORKS, false));
                break;
            case SettingsActivity.PREF_AUTO_DOWNLOAD:
            case SettingsActivity.PREF_SCHEDULER_MODE:
            case SettingsActivity.PREF_SCHEDULER_DAILY_TIME:
            case SettingsActivity.PREF_SCHEDULER_WEEK_DAY:
                int autoDownload = getAutoDownloadValue();
                if (autoDownload == PREF_AUTO_DOWNLOAD_DISABLED) {
                    Scheduler.stop(this);
                    break;
                }
                Scheduler.stop(this);
                Scheduler.start(this, Scheduler.ACTION_SCHEDULER_START);
                break;
            default:
                break;
        }
        if (mBatteryState != null)
            mBatteryState.onSharedPreferenceChanged(sharedPreferences, key);
    }

    /**
     * Updates the current state by checking everything in shared prefs and helper classes
     * @param notify whether to notify the user.
     *               specific for {@link #UPDATE_NOTIFICATION_CHANNEL_ID} notifications
     */
    private void autoState(boolean notify) {
        Logger.d("autoState: old state = " + mState + " notify = " + notify);
        // approach here is to check by the reverse order of update procedure

        // Check if a previous update was done already
        if (checkForFinishedUpdate()) return;

        // Check if we're currently installing an A/B update
        if (Config.isABDevice() && ABUpdate.isInstallingUpdate(this)) {
            // first check if we're suspended
            if (ABUpdate.isSuspended(this)) {
                mState.update(State.ACTION_AB_PAUSED);
                return;
            }
            // resume listening to progress, will notify
            final String flashFilename = mPrefs.getString(PREF_CURRENT_AB_FILENAME_NAME, null);
            if (flashFilename != null && !flashFilename.isEmpty()) {
                final String _filename = new File(flashFilename).getName();
                if (mLastProgressTime == null)
                    mLastProgressTime = new long[] { 0, SystemClock.elapsedRealtime() };
                mProgressListener.setStatus(_filename);
                mState.update(State.ACTION_AB_FLASH, 0f, 0L, 100L, _filename, null);
                final int code = ABUpdate.getInstance(this).resume();
                mIsUpdateRunning = code < 0;
                if (!mIsUpdateRunning) {
                    mNotificationManager.cancel(NOTIFICATION_UPDATE);
                    mState.update(State.ERROR_AB_FLASH, code);
                } else {
                    newFlashNotification(_filename);
                }
                return;
            }
        }

        // check if a file was already downloaded
        String readyFilename = mPrefs.getString(PREF_READY_FILENAME_NAME, null);
        if (readyFilename != null && (new File(readyFilename)).exists()) {
            // file was downloaded and is still there
            Logger.d("Update file found: %s", readyFilename);
            readyFilename = (new File(readyFilename)).getName();
            mState.update(State.ACTION_READY, readyFilename,
                    mPrefs.getLong(PREF_LAST_CHECK_TIME_NAME, PREF_LAST_CHECK_TIME_DEFAULT));
            maybeNotify(notify, null, readyFilename);
            return;
        }

        // check if there was an available download
        final String latestBuild = mPrefs.getString(PREF_LATEST_FULL_NAME, null);
        boolean readyToDownload = latestBuild != null;
        if (readyToDownload) {
            // first check if we have a download that was in progress
            // check if we have a .part file that was saved as latest
            File found = null;
            File[] files = new File(mConfig.getPathBase()).listFiles();
            if (files != null && files.length > 0) {
                for (File file : files) {
                    String currName = file.getName();
                    if (file.isFile() && currName.endsWith(".part")) {
                        if (currName.equals(latestBuild + ".part"))
                            found = file;
                        else
                            file.delete(); // remove old .part files
                    }
                }
            }
            if (found != null) {
                // confirm we're not already downloading
                if (mState.getState() == State.ACTION_DOWNLOADING) return;
                long total = mPrefs.getLong(PREF_DOWNLOAD_SIZE, 1500000000L /* 1.5 GB */);
                final long current = found.length();
                final long lastTime = mPrefs.getLong(PREF_LAST_DOWNLOAD_TIME, 0);
                final float progress = ((float) current / (float) total) * 100f;
                mState.update(State.ACTION_DOWNLOADING_PAUSED, progress, current, total, latestBuild, lastTime);
                // display paused notification with the proper title
                newDownloadNotification(true, getString(R.string.state_action_downloading_paused));
                mDownloadNotificationBuilder.setProgress(100, Math.round(progress), false);
                mNotificationManager.notify(NOTIFICATION_BUSY, mDownloadNotificationBuilder.build());
                return;
            }

            Logger.d("Assuming update available");
            Set<String> propSet = null;
            if (mConfig.getABStreamCurrent())
                propSet = mPrefs.getStringSet(PREF_LATEST_PAYLOAD_PROPS, null);
            final int state = (propSet != null && propSet.size() > 0)
                    ? State.ACTION_AVAILABLE_STREAM : State.ACTION_AVAILABLE;
            mState.update(state, mPrefs.getLong(PREF_LAST_CHECK_TIME_NAME, PREF_LAST_CHECK_TIME_DEFAULT));
            maybeNotify(notify, latestBuild, null);
            return;
        }

        Logger.d("Assuming system up to date");
        mState.update(State.ACTION_NONE, mPrefs.getLong(PREF_LAST_CHECK_TIME_NAME,
                PREF_LAST_CHECK_TIME_DEFAULT));
    }

    // helper for autoState
    private void maybeNotify(boolean notify, String latest, String flashFilename) {
        if (!notify) return;
        if (isSnoozeNotification()) {
            Logger.d("notification snoozed");
            return;
        }
        startNotification(latest, flashFilename);
    }

    private PendingIntent getNotificationIntent(boolean delete) {
        if (delete) {
            Intent notificationIntent = new Intent(this, UpdateService.class);
            notificationIntent.setAction(ACTION_NOTIFICATION_DELETED);
            return PendingIntent.getService(this, 0, notificationIntent, PendingIntent.FLAG_MUTABLE);
        } else {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setAction(ACTION_SYSTEM_UPDATE_SETTINGS);
            return PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_MUTABLE);
        }
    }

    private void startNotification(String latest, String flashFilename) {
        final SharedPreferences.Editor editor = mPrefs.edit();
        final boolean available = latest != null;
        final boolean readyToFlash = flashFilename != null;
        if (readyToFlash) {
            flashFilename = new File(flashFilename).getName();
            flashFilename.substring(0, flashFilename.lastIndexOf('.'));
            editor.putString(PREF_SNOOZE_UPDATE_NAME, flashFilename);
            editor.putLong(PREF_LAST_SNOOZE_TIME_NAME, System.currentTimeMillis());
        } else if (available) {
            editor.putString(PREF_SNOOZE_UPDATE_NAME, latest.substring(0, latest.lastIndexOf('.')));
            editor.putLong(PREF_LAST_SNOOZE_TIME_NAME, System.currentTimeMillis());
        }
        editor.commit();

        if (!readyToFlash && !available) return;

        String notifyFileName = readyToFlash
                ? flashFilename
                : latest.substring(0, latest.lastIndexOf('.'));

        mNotificationManager.notify(
                NOTIFICATION_UPDATE,
                (new Notification.Builder(this, UPDATE_NOTIFICATION_CHANNEL_ID))
                .setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(readyToFlash
                        ? getString(R.string.notify_title_flash)
                        : getString(R.string.notify_title_download))
                .setShowWhen(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(getNotificationIntent(false))
                .setDeleteIntent(getNotificationIntent(true))
                .setContentText(notifyFileName).build());
    }

    private void newFlashNotification(String filename) {
        mFlashNotificationBuilder = new Notification.Builder(this, INSTALL_NOTIFICATION_CHANNEL_ID);
        mFlashNotificationBuilder.setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(getString(R.string.state_action_ab_flash))
                .setShowWhen(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(getNotificationIntent(false))
                .setContentText(filename);
        setFlashNotificationProgress(0, 0);
    }

    private void newDownloadNotification(boolean isPaused, String title) {
        List<Notification.Action> actions = new ArrayList<>();
        // actions
        Intent stopIntent = new Intent(this, UpdateService.class);
        stopIntent.setAction(ACTION_DOWNLOAD_STOP);
        PendingIntent sPI = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_MUTABLE);
        Intent pauseIntent = new Intent(this, UpdateService.class);
        pauseIntent.setAction(ACTION_DOWNLOAD_PAUSE);
        PendingIntent cPI = PendingIntent.getService(this, 0, pauseIntent, PendingIntent.FLAG_MUTABLE);
        actions.add(new Notification.Action.Builder(
            0,
            getResources().getText(R.string.button_stop_text, ""),
            sPI
        ).build());
        actions.add(new Notification.Action.Builder(
            0,
            getResources().getText(isPaused
                    ? R.string.button_resume_text
                    : R.string.button_pause_text),
            cPI
        ).build());
        mDownloadNotificationBuilder = new Notification.Builder(this, INSTALL_NOTIFICATION_CHANNEL_ID);
        mDownloadNotificationBuilder.setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(title)
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(getNotificationIntent(false));
        for (Notification.Action action : actions)
            mDownloadNotificationBuilder.addAction(action);
    }

    private void startABRebootNotification(String filename) {
        String flashFilename = filename;
        final boolean hasName = flashFilename != null && !flashFilename.isEmpty();
        if (hasName) {
            flashFilename = new File(flashFilename).getName();
            flashFilename.substring(0, flashFilename.lastIndexOf('.'));
        }

        Notification.Builder builder =
                (new Notification.Builder(this, INSTALL_NOTIFICATION_CHANNEL_ID))
                .setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(getString(R.string.state_action_ab_finished))
                .setShowWhen(true)
                .setContentIntent(getNotificationIntent(false));
        if (hasName) builder.setContentText(flashFilename);

        mNotificationManager.notify(NOTIFICATION_UPDATE, builder.build());
    }

    private void startErrorNotification() {
        String errorStateString = null;
        try {
            errorStateString = getString(getResources().getIdentifier(
                    "state_" + mState, "string", getPackageName()));
        } catch (Exception e) {
            // String for this state could not be found (displays empty string)
            Logger.w("Couldn't find string for state " + mState);
        }
        if (errorStateString != null) {
            mNotificationManager.notify(
                    NOTIFICATION_ERROR,
                    (new Notification.Builder(this, UPDATE_NOTIFICATION_CHANNEL_ID))
                    .setSmallIcon(R.drawable.stat_notify_error)
                    .setContentTitle(getString(R.string.notify_title_error))
                    .setContentText(errorStateString)
                    .setShowWhen(true)
                    .setContentIntent(getNotificationIntent(false)).build());
        }
    }

    private boolean isMatchingImage(String fileName) {
        try {
            Logger.d("Image check for file name: " + fileName);
            if (fileName.endsWith(".zip") && fileName.contains(mConfig.getDevice())) {
                String[] parts = fileName.split("-");
                if (parts.length > 1) {
                    Logger.d("isMatchingImage: check " + fileName);
                    String version = parts[1];
                    Version current = new Version(mConfig.getAndroidVersion());
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

    public ProgressListener getSUMProgress(@StateInt int state, String filename) {
        final long[] last = new long[] { 0, SystemClock.elapsedRealtime() };
        final @StateInt int _state = state;
        final String _filename = filename;

        return new ProgressListener() {
            @Override
            public void onProgress(float progress, long current, long total) {
                long now = SystemClock.elapsedRealtime();
                if (now >= last[0] + 16L) {
                    mState.update(_state, progress, current, total, _filename,
                            SystemClock.elapsedRealtime() - last[1]);
                    last[0] = now;
                }
            }
            public void setStatus(String s) {
                // do nothing
            }
        };
    }

    private boolean checkForUpdates(boolean userInitiated, int checkOnly) {
        return checkForUpdates(userInitiated, checkOnly, false);
    }

    private boolean checkForUpdates(boolean userInitiated, int checkOnly, boolean forceFlash) {
        /*
         * Unless the user is specifically asking to check for updates, we only
         * check for them if we have a connection matching the user's set
         * preferences, we're charging and/or have juice aplenty (>50), and the screen
         * is off
         *
         * if user has enabled checking only we only check the screen state
         * cause the amount of data transferred for checking is not very large
         */
        if (userInitiated) clearState();

        if ((mNetworkState == null) || (mBatteryState == null)
                || (mScreenState == null))
            return false;

        // Check if a previous update was done already
        if (checkForFinishedUpdate()) return false;

        Logger.d(
            "checkForUpdates checkOnly = " + checkOnly +
            " mIsUpdateRunning = " + mIsUpdateRunning +
            " userInitiated = " + userInitiated +
            " forceFlash = " + forceFlash +
            " mNetworkState.getState() = " + mNetworkState.getState() +
            " mBatteryState.getState() = " + mBatteryState.getState() +
            " mScreenState.getState() = " + mScreenState.getState()
        );

        if (mIsUpdateRunning) {
            Logger.i("Ignoring request to check for updates - busy");
            return false;
        }

        mNotificationManager.cancel(NOTIFICATION_UPDATE);
        mNotificationManager.cancel(NOTIFICATION_ERROR);

        if (!mNetworkState.isConnected()) {
            mState.update(State.ERROR_CONNECTION);
            Logger.i("Ignoring request to check for updates - no data connection");
            return false;
        }
        boolean updateAllowed = false;
        if (!userInitiated) {
            updateAllowed = checkOnly >= PREF_AUTO_DOWNLOAD_CHECK;
            if (checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
                // must confirm to all if we may auto download
                updateAllowed = mNetworkState.getState()
                        && mBatteryState.getState() && isScreenStateEnabled();
                if (!updateAllowed) {
                    // fallback to check only
                    checkOnly = PREF_AUTO_DOWNLOAD_CHECK;
                    updateAllowed = true;
                    Logger.i("Auto-download not possible - fallback to check only");
                }
                mPrefs.edit().putLong(Scheduler.PREF_LAST_CHECK_ATTEMPT_TIME_NAME,
                        System.currentTimeMillis()).commit();
            }
        }

        if (userInitiated || updateAllowed) {
            Logger.i("Starting check for updates");
            checkForUpdatesAsync(userInitiated, checkOnly, forceFlash);
            return true;
        } else {
            Logger.i("Ignoring request to check for updates");
        }
        return false;
    }

    private void downloadBuild(String url, String sha256Sum, String imageName) {
        String fn = mConfig.getPathBase() + imageName;
        File f = new File(fn + ".part");
        Logger.d("download: %s --> %s", url, fn);

        // get rid of old .part files if any
        File[] files = new File(mConfig.getPathBase()).listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                String currName = file.getName();
                if (file.isFile() && currName.endsWith(".part")
                        && !currName.equals(f.getName())) {
                    file.delete();
                }
            }
        }

        mDownload = new Download(url, f, sha256Sum, this);
        if (mDownload.start() && f.renameTo(new File(fn))) {
            Logger.d("success");
            mPrefs.edit().putString(PREF_READY_FILENAME_NAME, fn).commit();
            mNotificationManager.cancel(NOTIFICATION_BUSY);
            startNotification(null, fn);
        } else {
            if (mDownload.getStatus() == Download.STATUS_DOWNLOAD_STOP) {
                f.delete();
                Logger.d("download stopped");
                autoState(false);
                mNotificationManager.cancel(NOTIFICATION_BUSY);
            } else if (mDownload.getStatus() != Download.STATUS_DOWNLOAD_RESUME &&
                       !mState.equals(State.ERROR_DOWNLOAD) &&
                       !mState.equals(State.ERROR_DOWNLOAD_SHA)) {
                // either pause or error
                final Long current = f.length();
                final Long total = mPrefs.getLong(PREF_DOWNLOAD_SIZE, 1500000000L /* 1.5GB */);
                final Long lastTime = mPrefs.getLong(PREF_LAST_DOWNLOAD_TIME, 0);
                final float progress = ((float) current / (float) total) * 100f;
                final boolean isPause = mDownload.getStatus() == Download.STATUS_DOWNLOAD_PAUSE;
                final @StateInt int newState = isPause ? State.ACTION_DOWNLOADING_PAUSED
                                                       : State.ERROR_DOWNLOAD_RESUME;
                Logger.d("download " + (isPause ? "paused" : "error"));
                mState.update(newState, progress, current, total, imageName, lastTime);
                // display paused notification with the proper title
                String title = getString(R.string.state_action_downloading_paused);
                if (!isPause) {
                    title = getString(R.string.state_error_download) + " (" +
                            getString(R.string.state_error_download_extra_resume) + ")";
                }
                mNotificationManager.cancel(NOTIFICATION_BUSY);
                newDownloadNotification(true, title);
                mDownloadNotificationBuilder.setProgress(100, Math.round(progress), false);
                mNotificationManager.notify(NOTIFICATION_BUSY, mDownloadNotificationBuilder.build());
            }
        }
    }

    /**
     * @param url - url to sha256sum file
     * @param fn - file name
     * @return true if sha256sum matches the file
     */
    private boolean checkBuildSHA256Sum(String url, String fn) {
        final String latestSUM = getLatestSHA256Sum(url);
        final File file = new File(fn);
        if (latestSUM != null){
            try {
                String fileSUM = getFileSHA256(file,
                        getSUMProgress(State.ACTION_CHECKING_SUM, file.getName()));
                boolean sumCheck = fileSUM.equals(latestSUM);
                Logger.d("fileSUM=" + fileSUM + " latestSUM=" + latestSUM);
                if (sumCheck) return true;
                Logger.i("fileSUM check failed for " + url);
            } catch(Exception e) {
                // WTH knows what can comes from the server
            }
        }
        return false;
    }

    public static String getFileSHA256(File file, ProgressListener progressListener) {
        String ret = null;
        int count = 0;

        long total = file.length();
        if (progressListener != null)
            progressListener.onProgress(getProgress(0, total), 0, total);

        try {
            try (FileInputStream is = new FileInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                int r;

                while ((r = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, r);
                    count += r;
                    if (progressListener != null)
                        progressListener.onProgress(getProgress(count, total), count, total);
                }

                ret = Download.digestToHexString(digest);
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

    private static void writeString(OutputStream os, String s)
            throws IOException {
        os.write((s + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private String handleUpdateCleanup() throws FileNotFoundException {
        String flashFilename = mPrefs.getString(PREF_READY_FILENAME_NAME, null);
        boolean fileFlash = mPrefs.getBoolean(PREF_FILE_FLASH, false);

        if (flashFilename == null
                || (!fileFlash && !flashFilename.startsWith(mConfig.getPathBase()))
                || !new File(flashFilename).exists()) {
            clearState();
            throw new FileNotFoundException("flashUpdate - no valid file to flash found " + flashFilename);
        }

        return flashFilename;
    }

    protected void onUpdateCompleted(int status, int errorCode) {
        Logger.d("onUpdateCompleted status = " + status);
        mNotificationManager.cancel(NOTIFICATION_UPDATE);
        mIsUpdateRunning = false;
        if (status == UpdateEngine.ErrorCodeConstants.SUCCESS) {
            mPrefs.edit().putBoolean(PREF_PENDING_REBOOT, true).commit();
            String flashFilename = mPrefs.getString(PREF_READY_FILENAME_NAME, null);
            if (flashFilename != null) {
                deleteOldFlashFile(flashFilename);
                mPrefs.edit().putString(PREF_CURRENT_FILENAME_NAME, flashFilename).commit();
            }
            startABRebootNotification(flashFilename);
            mState.update(State.ACTION_AB_FINISHED);
        } else {
            mState.update(State.ERROR_AB_FLASH, errorCode);
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
        mNotificationManager.notify(
                    NOTIFICATION_UPDATE, mFlashNotificationBuilder.build());
    }

    public synchronized void setDownloadNotificationProgress(float progress, long current, long total, long ms) {
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
        mNotificationManager.notify(
                NOTIFICATION_BUSY, mDownloadNotificationBuilder.build());
    }

    private void flashABUpdate() {
        flashABUpdate(false);
    }

    private void flashABUpdate(final boolean isStream) {
        Logger.d("flashABUpdate. isStream=" + isStream);
        String flashFilename;
        try {
            flashFilename = isStream
                    ? mPrefs.getString(PREF_READY_FILENAME_NAME, null)
                    : handleUpdateCleanup();
        } catch (Exception ex) {
            mIsUpdateRunning = false;
            mState.update(State.ERROR_AB_FLASH, ABUpdate.ERROR_NOT_FOUND);
            Logger.ex(ex);
            return;
        }

        // Save the filename for resuming
        mPrefs.edit().putString(PREF_CURRENT_AB_FILENAME_NAME, flashFilename).commit();

        // Clear the Download size to hide while flashing
        mPrefs.edit().putLong(PREF_DOWNLOAD_SIZE, -1).commit();

        String _filename = null;
        if (isStream) {
            final int eIndex = flashFilename.lastIndexOf('.');
            final int sIndex = flashFilename.lastIndexOf('/', eIndex);
            _filename = flashFilename.substring(sIndex + 1, eIndex);
        } else {
            _filename = new File(flashFilename).getName();
        }
        mState.update(State.ACTION_AB_FLASH, 0f, 0L, 100L, _filename, null);

        newFlashNotification(_filename);

        int code = -1;
        if (isStream) {
            Set<String> payloadSet = mPrefs.getStringSet(PREF_LATEST_PAYLOAD_PROPS, null);
            List<String> payloadProps = new ArrayList<>();
            long offset = 0;
            long size = 0;
            for (String str : payloadSet) {
                if (offset == 0 && str.startsWith(PAYLOAD_PROP_OFFSET)) {
                    offset = Long.parseLong(str.substring(PAYLOAD_PROP_OFFSET.length(), str.length()));
                    continue;
                }
                if (size == 0 && str.startsWith(PAYLOAD_PROP_SIZE))
                    size = Long.parseLong(str.substring(PAYLOAD_PROP_SIZE.length(), str.length()));
                payloadProps.add(str);
            }
            String[] headerKeyValuePairs = new String[payloadProps.size()];
            for (int i = 0; i < payloadProps.size(); i++)
                headerKeyValuePairs[i] = payloadProps.get(i);
            code = ABUpdate.getInstance(this).start(flashFilename, headerKeyValuePairs,
                    offset, size, mProgressListener);
        } else {
            code = ABUpdate.getInstance(this).start(flashFilename, mProgressListener);
        }
        if (code < 0) {
            mLastProgressTime = new long[] { 0, SystemClock.elapsedRealtime() };
            mProgressListener.setStatus(_filename);
            mIsUpdateRunning = true;
            return;
        }
        mNotificationManager.cancel(NOTIFICATION_UPDATE);
        mIsUpdateRunning = false;
        mState.update(State.ERROR_AB_FLASH, code);
    }

    @SuppressLint({"SdCardPath", "SetWorldReadable"})
    private void flashUpdate() {
        Logger.d("flashUpdate");
        if (getPackageManager().checkPermission(
                PERMISSION_ACCESS_CACHE_FILESYSTEM, getPackageName())
                != PackageManager.PERMISSION_GRANTED) {
            Logger.d("[%s] required beyond this point",
                    PERMISSION_ACCESS_CACHE_FILESYSTEM);
            return;
        }

        if (getPackageManager().checkPermission(PERMISSION_REBOOT,
                getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            Logger.d("[%s] required beyond this point", PERMISSION_REBOOT);
            return;
        }

        String flashFilename;
        try {
            flashFilename = handleUpdateCleanup();
        } catch (Exception ex) {
            mState.update(State.ERROR_FLASH);
            Logger.ex(ex);
            return;
        }

        deleteOldFlashFile(flashFilename);
        mPrefs.edit().putString(PREF_CURRENT_FILENAME_NAME, flashFilename).commit();
        clearState();

        // Remove the path to the storage from the filename, so we get a path
        // relative to the root of the storage
        String path_sd = Environment.getExternalStorageDirectory()
                + File.separator;
        flashFilename = flashFilename.substring(path_sd.length());

        // Find additional ZIPs to flash, strip path to sd
        List<String> extras = mConfig.getFlashAfterUpdateZIPs();
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
            if (mConfig.getUseTWRP()) {
                Logger.d("flashUpdate - create /cache/recovery/openrecoveryscript");

                try (FileOutputStream os = new FileOutputStream(
                        "/cache/recovery/openrecoveryscript", false)) {
                    writeString(os, "set tw_signed_zip_verify 0");
                    writeString(os, String.format("install %s", flashFilename));

                    // any program could have placed these ZIPs, so ignore
                    // them in secure mode
                    for (String file : extras) {
                        writeString(os, String.format("install %s", file));
                    }

                    writeString(os, "wipe cache");
                }

                final int res = FileUtils.setPermissions(
                        "/cache/recovery/openrecoveryscript",
                        420, Process.myUid(), 2001);
                if (res != 0) {
                    Logger.i("Failed setting /cache/recovery/openrecoveryscript permissions with code " + res);
                    Logger.i("Earlier FileUtils logs will point out the reason");
                    return;
                };

                Logger.d("flashUpdate - reboot to recovery");
                ((PowerManager) getSystemService(Context.POWER_SERVICE))
                        .rebootCustom(PowerManager.REBOOT_RECOVERY);
            } else {
                // AOSP recovery and derivatives
                // First copy the file to cache
                // Finally tell RecoverySystem to flash it via recovery
                Logger.d("flashUpdate - installing A-only OTA package");
                File src = new File(path_sd + flashFilename);
                File dst = new File("/cache/recovery/ota_package.zip");
                try (FileChannel srcCh = new FileInputStream(src).getChannel();
                     FileChannel dstCh = new FileOutputStream(dst, false).getChannel()) {
                    dstCh.transferFrom(srcCh, 0, srcCh.size());
                    dst.setReadable(true, false);
                    dst.setWritable(true, false);
                    dst.setExecutable(true, false);
                } catch (Exception e) {
                    dst.delete();
                    Logger.d("flashUpdate - Could not install OTA package:");
                    Logger.ex(e);
                    mState.update(State.ERROR_FLASH);
                    return;
                }

                try {
                    Set<PosixFilePermission> perms = Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.GROUP_READ
                    );
                    Files.setPosixFilePermissions(dst.toPath(), perms);
                    RecoverySystem.installPackage(getApplicationContext(), dst);
                } catch (Exception e) {
                    dst.delete();
                    Logger.d("flashUpdate - Could not install OTA package:");
                    Logger.ex(e);
                    mState.update(State.ERROR_FLASH);
                }
            }
        } catch (Exception e) {
            // We have failed to write something. There's not really anything
            // else to do at this stage than give up. No reason to crash though.
            Logger.ex(e);
            mState.update(State.ERROR_FLASH);
        }
    }

    private String getLatestSHA256Sum(String sumUrl) {
        String urlSuffix = mConfig.getUrlSuffix();
        if (mIsUrlOverride) {
            sumUrl = mSumUrlOvr;
        } else if (urlSuffix.length() > 0) {
            sumUrl += mConfig.getUrlSuffix();
        }
        String latestSum = Download.asString(sumUrl);
        if (latestSum != null) {
            String sumPart = latestSum;
            while (sumPart.length() > 64)
                sumPart = sumPart.substring(0, sumPart.length() - 1);
            Logger.d("getLatestSHA256Sum - sha256sum = " + sumPart);
            return sumPart;
        }
        return null;
    }

    private static float getProgress(long current, long total) {
        if (total == 0)
            return 0f;
        return ((float) current / (float) total) * 100f;
    }

    private int getAutoDownloadValue() {
        String autoDownload = mPrefs.getString(
                SettingsActivity.PREF_AUTO_DOWNLOAD,
                Integer.toString(PREF_AUTO_DOWNLOAD_CHECK));
        return Integer.parseInt(autoDownload);
    }

    private boolean isScreenStateEnabled() {
        if (mScreenState == null) {
            return false;
        }
        boolean screenStateValue = mScreenState.getState();
        boolean prefValue = mPrefs.getBoolean(SettingsActivity.PREF_SCREEN_STATE_OFF, true);
        if (prefValue) {
            // only when screen off
            return !screenStateValue;
        }
        // always allow
        return true;
    }

    private boolean isSnoozeNotification() {
        // check if we're snoozed, using abs for clock changes
        boolean timeSnooze = Math.abs(System.currentTimeMillis()
                - mPrefs.getLong(PREF_LAST_SNOOZE_TIME_NAME,
                        PREF_LAST_SNOOZE_TIME_DEFAULT)) <= SNOOZE_MS;
        if (timeSnooze) {
            String lastBuild = mPrefs.getString(PREF_LATEST_FULL_NAME, null);
            String snoozeBuild = mPrefs.getString(PREF_SNOOZE_UPDATE_NAME, null);
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
        SharedPreferences.Editor editor = mPrefs.edit();
        editor.putString(PREF_LATEST_FULL_NAME, null);
        editor.putString(PREF_LATEST_PAYLOAD_PROPS, null);
        editor.putString(PREF_READY_FILENAME_NAME, null);
        editor.putString(PREF_LATEST_CHANGELOG, null);
        editor.putLong(PREF_DOWNLOAD_SIZE, -1);
        editor.putBoolean(PREF_FILE_FLASH, false);
        editor.commit();
    }

    private void shouldShowErrorNotification() {
        boolean dailyAlarm = mPrefs.getString(SettingsActivity.PREF_SCHEDULER_MODE, SettingsActivity.PREF_SCHEDULER_MODE_SMART)
                .equals(SettingsActivity.PREF_SCHEDULER_MODE_DAILY);

        if (dailyAlarm || mFailedUpdateCount >= 4) {
            // if from scheduler show a notification cause user should
            // see that something went wrong
            // if we check only daily always show - if smart mode wait for 4
            // consecutive failure - would be about 24h
            startErrorNotification();
            mFailedUpdateCount = 0;
        }
    }

    private void checkForUpdatesAsync(final boolean userInitiated, final int checkOnly,
            final boolean forceFlash) {
        Logger.d("checkForUpdatesAsync");

        mState.update(State.ACTION_CHECKING);
        mWakeLock.acquire();
        mWifiLock.acquire();

        if (!userInitiated) {
            // scheduler triggered a check
            // stop when it's done
            mState.addStateCallback(mStopWhenDoneCallback);
        }

        newDownloadNotification(false,
                getString(R.string.state_action_downloading));

        mHandler.post(() -> {
            mIsUpdateRunning = true;

            try {
                String flashFilename = null;
                (new File(mConfig.getPathBase())).mkdir();
                (new File(mConfig.getPathFlashAfterUpdate())).mkdir();

                Logger.d("Checking for latest build");

                String url = mConfig.getUrlBaseJson();
                String latestBuild = null;
                String urlOverride = null;
                String sumOverride = null;
                List<String> payloadProps = null;

                String buildData = Download.asString(url);
                if (buildData == null || buildData.length() == 0) {
                    mState.update(State.ERROR_DOWNLOAD, url, Download.ERROR_CODE_NEWEST_BUILD);
                    mNotificationManager.cancel(NOTIFICATION_BUSY);
                }
                JSONObject object;
                try {
                    object = new JSONObject(buildData);
                    JSONArray updatesList = object.getJSONArray("response");
                    for (int i = 0; i < updatesList.length(); i++) {
                        if (updatesList.isNull(i)) {
                            continue;
                        }
                        try {
                            JSONObject build = updatesList.getJSONObject(i);
                            String fileName = new File(build.getString("filename")).getName();
                            if (build.has("url"))
                                urlOverride = build.getString("url");
                            if (build.has("sha256url"))
                                sumOverride = build.getString("sha256url");
                            if (build.has("payload")) {
                                payloadProps = new ArrayList<>();
                                JSONArray payloadList = build.getJSONArray("payload");
                                for (int j = 0; j < payloadList.length(); j++) {
                                    if (payloadList.isNull(j)) continue;
                                    JSONObject prop = payloadList.getJSONObject(j);
                                    Iterator<String> keys = prop.keys();
                                    while (keys.hasNext()) {
                                        final String key = keys.next();
                                        payloadProps.add(key + "=" + prop.get(key));
                                    }
                                }
                            }
                            Logger.d("parsed from json:");
                            Logger.d("fileName= " + fileName);
                            if (isMatchingImage(fileName))
                                latestBuild = fileName;
                            if (urlOverride != null && !urlOverride.equals(""))
                                Logger.d("url= " + urlOverride);
                            if (sumOverride != null && !sumOverride.equals("")) {
                                Logger.d("sha256 url= " + sumOverride);
                            }
                            if (payloadProps != null) {
                                for (String str : payloadProps) {
                                    Logger.d(str);
                                }
                            }
                        } catch (JSONException e) {
                            Logger.ex(e);
                        }
                    }
                } catch (Exception e) {
                    Logger.ex(e);
                    mState.update(State.ERROR_UNOFFICIAL, mConfig.getVersion());
                    return;
                }

                // if we don't even find a build on dl no sense to continue
                if (latestBuild == null || latestBuild.length() == 0) {
                    Logger.d("no latest build found at " + mConfig.getUrlBaseJson() +
                            " for " + mConfig.getDevice());
                    return;
                }

                String latestFetch;
                String latestFetchSUM;
                if (urlOverride == null || sumOverride == null) {
                    latestFetch = mConfig.getUrlBase() +
                            latestBuild + mConfig.getUrlSuffix();
                    latestFetchSUM = mConfig.getUrlBaseSum() +
                            latestBuild + ".sha256sum" + mConfig.getUrlSuffix();
                } else {
                    latestFetch = urlOverride;
                    latestFetchSUM = sumOverride;
                }
                Logger.d("latest build for device " + mConfig.getDevice() + " is " + latestFetch);

                String currentVersionZip = mConfig.getFilenameBase() + ".zip";
                boolean updateAvailable = latestBuild != null && forceFlash;
                if (latestBuild != null && !forceFlash) {
                    try {
                        final long currFileDate = Long.parseLong(currentVersionZip
                                .split("-")[4].substring(0, 8));
                        final long latestFileDate = Long.parseLong(latestBuild
                                .split("-")[4].substring(0, 8));
                        updateAvailable = latestFileDate > currFileDate;
                    } catch (NumberFormatException exception) {
                        // Just incase someone decides to 
                        // make up his own zip / build name and F's this up
                        Logger.d("Build name malformed");
                        Logger.ex(exception);
                    }
                }
                mPrefs.edit().putString(PREF_LATEST_FULL_NAME,
                        updateAvailable ? latestBuild : null).commit();
                if (!updateAvailable) return;

                if (payloadProps != null) {
                    mPrefs.edit().putStringSet(PREF_LATEST_PAYLOAD_PROPS,
                            payloadProps.stream().collect(Collectors.toSet())).commit();
                    mPrefs.edit().putString(PREF_READY_FILENAME_NAME, latestFetch).commit();
                    Logger.d("update supports streaming");
                } else {
                    mPrefs.edit().remove(PREF_LATEST_PAYLOAD_PROPS).commit();
                }

                final String changelog = getChangelogString();
                mPrefs.edit().putString(PREF_LATEST_CHANGELOG, changelog).commit();

                if (checkExistingBuild(latestBuild, latestFetchSUM)) return;
                
                final long size = Download.getSize(latestFetch);
                mPrefs.edit().putLong(PREF_DOWNLOAD_SIZE, size).commit();

                Logger.d("check done: latest build available = " +
                         mPrefs.getString(PREF_LATEST_FULL_NAME, null) +
                         " ; updateAvailable = " + updateAvailable);

                final StatFs stats = new StatFs(mConfig.getPathBase());
                final long blockSize = stats.getBlockSizeLong();
                final long blocks = (size + blockSize - 1) / blockSize;
                final long requiredSpace = blocks * blockSize;
                final long freeSpace = stats.getAvailableBytes();
                Logger.d("requiredSpace = " + requiredSpace +
                         " freeSpace = " + freeSpace);
                if (freeSpace < requiredSpace) {
                    mState.update(State.ERROR_DISK_SPACE,
                            null, freeSpace, requiredSpace, null, null);
                    Logger.d("not enough space!");
                    return;
                }

                if (checkOnly == PREF_AUTO_DOWNLOAD_FULL) {
                    if (userInitiated || mNetworkState.getState()) {
                        final String latestSUM = getLatestSHA256Sum(latestFetchSUM);
                        if (latestSUM != null) {
                            downloadBuild(latestFetch, latestSUM, latestBuild);
                        } else {
                            mState.update(State.ERROR_DOWNLOAD, Download.ERROR_CODE_NO_SUM_FILE);
                            Logger.d("aborting download due to sha256sum not found");
                        }
                    } else {
                        mState.update(State.ERROR_DOWNLOAD, Download.ERROR_CODE_NO_CONNECTION);
                        Logger.d("aborting download due to network state");
                    }
                }
            } finally {
                if (mWifiLock.isHeld()) mWifiLock.release();
                if (mWakeLock.isHeld()) mWakeLock.release();

                mPrefs.edit().putLong(PREF_LAST_CHECK_TIME_NAME,
                        System.currentTimeMillis()).commit();

                if (mState.isErrorState()) {
                    mFailedUpdateCount++;
                    clearState();
                    if (!userInitiated) {
                        shouldShowErrorNotification();
                    }
                } else {
                    mFailedUpdateCount = 0;
                    autoState(!userInitiated);
                }
                mIsUpdateRunning = false;
                mState.notifyCallbacks();

                for (CheckForUpdateListener listener : mCheckForUpdateListeners)
                    listener.onCheckDone(mState);
            }
        });
    }

    private boolean checkExistingBuild(String latestBuild, String latestFetchSUM) {
        String fn = mConfig.getPathBase() + latestBuild;
        File file = new File(fn);
        if (file.exists()) {
            if (checkBuildSHA256Sum(latestFetchSUM, fn)) {
                Logger.d("match found: " + fn);
                // zip exists and is valid - flash ready state
                mPrefs.edit().putString(PREF_READY_FILENAME_NAME, fn).commit();
                return true;
            }
            // get rid of rubbish
            file.delete();
        }
        return false;
    }

    private boolean checkForFinishedUpdate() {
        final boolean finished = 
                mPrefs.getBoolean(PREF_PENDING_REBOOT, false) ||
                mState.equals(State.ACTION_AB_FINISHED) ||
                ABUpdate.isInstallingUpdate(this);
        if (finished) {
            final String lastFilename = mPrefs.getString(PREF_CURRENT_AB_FILENAME_NAME, null);
            mPrefs.edit().putBoolean(PREF_PENDING_REBOOT, false).commit();
            ABUpdate.getInstance(this).pokeStatus();
        }
        return finished;
    }

    private boolean checkPermissions() {
        if (!Environment.isExternalStorageManager()) {
            Logger.d("checkPermissions failed");
            mState.update(State.ERROR_PERMISSIONS);
            return false;
        }
        return true;
    }

    private void deleteOldFlashFile(String newFlashFilename) {
        String oldFlashFilename = mPrefs.getString(PREF_CURRENT_FILENAME_NAME, null);
        Logger.d("delete oldFlashFilename " + oldFlashFilename + " " + newFlashFilename);

        if (oldFlashFilename != null && !oldFlashFilename.equals(newFlashFilename)
                && oldFlashFilename.startsWith(mConfig.getPathBase())) {
            File file = new File(oldFlashFilename);
            if (file.exists()) {
                Logger.d("delete oldFlashFilename " + oldFlashFilename);
                file.delete();
            }
        }
    }

    public SharedPreferences getPrefs() {
        return mPrefs;
    }

    public Config getConfig() {
        return mConfig;
    }

    public PowerManager.WakeLock getWakeLock() {
        return mWakeLock;
    }

    public void setFlashFilename(String flashFilename) {
        setFlashFilename(flashFilename, false);
    }

    public void setFlashFilename(String flashFilename, boolean forceFlash) {
        Logger.d("Flash file set: %s", flashFilename);
        if (flashFilename == null) {
            mState.update(State.ERROR_FLASH_FILE);
            return; 
        }
        File fn = new File(flashFilename);
        if (!fn.exists()) {
            mState.update(State.ERROR_FLASH_FILE);
            return;
        }
        if (!fn.getName().endsWith(".zip")) {
            mState.update(State.ERROR_FLASH_FILE);
            return;
        }
        mHandler.post(() -> {
            maybeFlashFile(flashFilename, forceFlash);
        });
    }

    private void maybeFlashFile(String flashFilename, boolean forceFlash) {
        mPrefs.edit().putString(PREF_READY_FILENAME_NAME, flashFilename).commit();
        File fn = new File(flashFilename);
        if (!forceFlash) {
            File shaFile = new File(flashFilename + ".sha256sum");
            if (!shaFile.exists()) {
                mState.update(State.ACTION_FLASH_FILE_NO_SUM, fn.getName());
                return;
            }
            // verify sha with local file
            String sha;
            try (BufferedReader br = new BufferedReader(new FileReader(shaFile))) {
                sha = br.readLine();
                while (sha.length() > 64)
                    sha = sha.substring(0, sha.length() - 1);
            } catch (Exception e) {
                Logger.ex(e);
                mState.update(State.ACTION_FLASH_FILE_INVALID_SUM, fn.getName());
                return;
            }
            final ProgressListener listener = getSUMProgress(
                    State.ACTION_CHECKING_SUM, flashFilename);
            final String fileSha = getFileSHA256(fn, listener);
            if (fileSha == null || sha == null || !fileSha.equals(sha)) {
                mState.update(State.ACTION_FLASH_FILE_INVALID_SUM, fn.getName());
                return;
            }
        }
        Logger.d("Set flash possible: %s", flashFilename);
        mPrefs.edit().putBoolean(PREF_FILE_FLASH, true).commit();
        mState.update(State.ACTION_FLASH_FILE_READY, fn.getName());
    }

    private void createInstallNotificationChannel() {
        final CharSequence name = getString(R.string.install_channel_name);
        final String description = getString(R.string.install_channel_description);
        final int importance = NotificationManager.IMPORTANCE_LOW;
        final NotificationChannel channel = new NotificationChannel(
                INSTALL_NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        mNotificationManager.createNotificationChannel(channel);
    }

    private void createUpdateNotificationChannel() {
        final CharSequence name = getString(R.string.update_channel_name);
        final String description = getString(R.string.update_channel_description);
        final int importance = NotificationManager.IMPORTANCE_DEFAULT;
        final NotificationChannel channel = new NotificationChannel(
                UPDATE_NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        channel.setBlockable(true);
        mNotificationManager.createNotificationChannel(channel);
    }

    private String getChangelogString() {
        final String jsURL = mConfig.getUrlBaseJson();
        StringBuilder changelog = new StringBuilder(
                Download.asString(jsURL.replace(
                mConfig.getDevice() + ".json",
                "Changelog.txt")));
        // currently changelog only contains the latest info
        // let us check if we have any builds the user skipped and add em
        try {
            final JSONArray jArr = new JSONArray(Download.asString(mConfig.getUrlAPIHistory()));
            for (int i = 1; i < jArr.length() && i < 10; i++) {
                try {
                    final String otaJsonURL = jsURL.replace(
                            mConfig.getUrlBranchName(),
                            jArr.getJSONObject(i).getString("sha"));
                    final JSONObject otaJson = new JSONObject(Download.asString(otaJsonURL));
                    final String filename = otaJson.getJSONArray("response")
                            .getJSONObject(0).getString("filename");
                    final Long fileDate = Long.parseLong(
                            filename.split("-")[4].substring(0, 8));
                    final Long currDate = Long.parseLong(
                            mConfig.getFilenameBase().split("-")[4].substring(0, 8));
                    if (fileDate <= currDate) break; // reached an older/same build

                    // fetch and add the changelog of that commit sha, titled by the date
                    final String currChangelog = Download.asString(
                            otaJsonURL.replace(mConfig.getDevice() + ".json", "Changelog.txt"));
                    changelog.append("\n" + fileDate + ":\n\n" + currChangelog);
                } catch (JSONException e) {
                    Logger.ex(e);
                }
            }
        } catch (Exception e) {
            Logger.ex(e);
        }
        return changelog.toString();
    }
}
