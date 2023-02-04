/* 
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2015 The OmniROM Project
 * Copyright (C) 2020-2023 Yet Another AOSP Project
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
/*
 * We're using three different alarms for scheduling. The primary is an
 * (inexact) interval alarm that is fired every 30-60 minutes (if the device 
 * is already awake anyway) to see if conditions are right to automatically 
 * check for updates. 
 * 
 * The second alarm is a backup (inexact) alarm that will actually wake up 
 * the device every few hours (if our interval alarm has not been fired 
 * because of no background activity). Because this only happens once every 
 * 3-6 hours and Android will attempt to schedule it together with other 
 * wakeups, effect on battery life should be completely insignificant. 
 *  
 * Last but not least, we're using an (exact) alarm that will fire if the
 * screen has been off for 5.5 hours. The idea is that you might be asleep
 * at this time and will wake up soon-ish, and we would not mind surprising
 * you with a fresh nightly.
 * 
 * The first two alarms only request a check for updates if the previous
 * check was 6 hours or longer ago. The last alarm will request that check
 * regardless. Regardless of those parameters, the update service will still
 * only perform the actual check if it's happy with the current network
 * (Wi-Fi) and battery (charging / juice aplenty) state. 
 */

package eu.chainfire.opendelta;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

import eu.chainfire.opendelta.ScreenState.OnScreenStateListener;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class Scheduler extends Service implements OnScreenStateListener {

    public static final String ACTION_SCHEDULER_START = "eu.chainfire.opendelta.action.Scheduler.START";
    public static final String ACTION_SCHEDULER_ALARM = "eu.chainfire.opendelta.action.Scheduler.ALARM";
    public static final String PREF_LAST_CHECK_ATTEMPT_TIME_NAME = "last_check_attempt_time";
    private static final long PREF_LAST_CHECK_ATTEMPT_TIME_DEFAULT = 0L;

    private static final long CHECK_THRESHOLD = 6 * AlarmManager.INTERVAL_HOUR;
    private static final long ALARM_INTERVAL = 3 * AlarmManager.INTERVAL_HOUR;
    private static final long ALARM_SECONDARY = AlarmManager.INTERVAL_HALF_DAY;
    private static final long ALARM_DETECT_SLEEP_TIME = 5 * AlarmManager.INTERVAL_HOUR;

    private AlarmManager mAlarmManager;
    private SharedPreferences mPrefs;
    private PendingIntent mAlarmInterval;
    private PendingIntent mAlarmSecondaryWake;
    private PendingIntent mAlarmDetectSleep;
    private PendingIntent mAlarmCustom;

    private ScreenState mScreenState;
    private boolean mIsStopped;
    private boolean mIsCustomAlarm;

    private final SimpleDateFormat mSdf = new SimpleDateFormat("HH:mm", Locale.ENGLISH);

    public static void start(Context context, String action) {
        start(context, action, -1);
    }

    public static void start(Context context, String action, int extra) {
        Intent i = new Intent(context, Scheduler.class);
        i.setAction(action);
        i.putExtra(UpdateService.EXTRA_ALARM_ID, extra);
        context.startService(i);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, Scheduler.class));
    }

    @Override
    public void onCreate() {
        mAlarmManager = (AlarmManager) this.getSystemService(Context.ALARM_SERVICE);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mAlarmInterval = alarmPending(this, 1);
        mAlarmSecondaryWake = alarmPending(this, 2);
        mAlarmDetectSleep = alarmPending(this, 3);
        mAlarmCustom = alarmPending(this, 4);
        mScreenState = new ScreenState();
        mIsStopped = true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Logger.i("Scheduler onStartCommand=" + intent.getAction());
        switch (intent.getAction()) {
            case ACTION_SCHEDULER_START:
                return startScheduler();
            case ACTION_SCHEDULER_ALARM:
                return alarm(intent.getIntExtra(UpdateService.EXTRA_ALARM_ID, -1));
        }
        return START_REDELIVER_INTENT;
    }

    private int startScheduler() {
        cancelSecondaryWakeAlarm();
        cancelDetectSleepAlarm();
        mAlarmManager.cancel(mAlarmInterval);
        mAlarmManager.cancel(mAlarmCustom);

        final String alarmType = mPrefs.getString(SettingsActivity.PREF_SCHEDULER_MODE,
                SettingsActivity.PREF_SCHEDULER_MODE_SMART);
        mIsCustomAlarm = alarmType.equals(SettingsActivity.PREF_SCHEDULER_MODE_DAILY) ||
                alarmType.equals(SettingsActivity.PREF_SCHEDULER_MODE_WEEKLY);

        mIsStopped = false;
        if (mIsCustomAlarm) {
            setCustomAlarmFromPrefs(alarmType);
            stopSelf();
            return START_NOT_STICKY;
        }

        // smart mode
        final long time = getMaxTime(ALARM_INTERVAL);
        Logger.i("Setting a repeating alarm (inexact) for %s", mSdf.format(new Date(time)));
        mAlarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                time, ALARM_INTERVAL, mAlarmInterval);
        setSecondaryWakeAlarm();
        if (!Config.getInstance(this).getSchedulerSleepEnabled()) {
            mScreenState.stop();
            return START_NOT_STICKY;
        }
        mScreenState.start(this, this);
        return START_REDELIVER_INTENT;
    }

    private int alarm(int id) {
        switch (id) {
            case 1:
                // This is the interval alarm, called only if the device is
                // already awake for some reason. Might as well see if
                // conditions match to check for updates, right ?
                Logger.i("Interval alarm fired");
                checkForUpdates(false);
                break;
            case 2:
                // Fallback alarm. Our interval alarm has not been called for
                // 12 hours. The device might have been woken up just
                // for us. Let's see if conditions are good to check for
                // updates.
                Logger.i("Secondary alarm fired");
                checkForUpdates(false);
                break;
            case 3:
                // The screen has been off for 5:00 hours, with luck we've
                // caught the user asleep and we'll have a fresh build waiting
                // when (s)he wakes!
                Logger.i("Sleep detection alarm fired");
                checkForUpdates(true);
                break;
            case 4:
                // fixed daily alarm triggers
                Logger.i("Daily alarm fired");
                checkForUpdates(true);
                break;
        }

        if (mIsCustomAlarm) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Reset fallback wakeup command, we don't need to be called for another
        // few hours
        cancelSecondaryWakeAlarm();
        setSecondaryWakeAlarm();
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        Logger.i("Stopping scheduler");
        cancelSecondaryWakeAlarm();
        cancelDetectSleepAlarm();
        if (!mIsCustomAlarm) {
            mAlarmManager.cancel(mAlarmInterval);
            mAlarmManager.cancel(mAlarmCustom);
        }
        mScreenState.stop();
        mIsStopped = true;
        super.onDestroy();
    }

    private static PendingIntent alarmPending(Context context, int id) {
        Intent intent = new Intent(context, UpdateService.class);
        intent.setAction(UpdateService.ACTION_ALARM);
        intent.putExtra(UpdateService.EXTRA_ALARM_ID, id);
        return PendingIntent.getService(context, id, intent, PendingIntent.FLAG_MUTABLE);
    }

    public static boolean isCustomAlarm(SharedPreferences prefs) {
        final String alarmType = prefs.getString(SettingsActivity.PREF_SCHEDULER_MODE,
                SettingsActivity.PREF_SCHEDULER_MODE_SMART);
        final boolean isCustomAlarm = alarmType.equals(SettingsActivity.PREF_SCHEDULER_MODE_DAILY) ||
                alarmType.equals(SettingsActivity.PREF_SCHEDULER_MODE_WEEKLY);
        return isCustomAlarm;
    }

    /**
     * @return true if we passed {@link #CHECK_THRESHOLD}
     */
    private boolean isTimePassed() {
        return isTimePassed(mPrefs);
    }

    /**
     * @param prefs SharedPreferences for static ref
     * @return true if we passed {@link #CHECK_THRESHOLD}
     */
    public static boolean isTimePassed(SharedPreferences prefs) {
        return getLastAttemptTimePassed(prefs) > CHECK_THRESHOLD;
    }

    /**
     * @return the time passed since last check attempt
     */
    private long getLastAttemptTimePassed() {
        return getLastAttemptTimePassed(mPrefs);
    }

    /**
     * @param prefs SharedPreferences for static ref
     * @return the time passed since last check attempt
     */
    private static long getLastAttemptTimePassed(SharedPreferences prefs) {
        // Using abs here in case user changes date/time
        final long lastAttempt = prefs.getLong(
                PREF_LAST_CHECK_ATTEMPT_TIME_NAME, PREF_LAST_CHECK_ATTEMPT_TIME_DEFAULT);
        return Math.abs(System.currentTimeMillis() - lastAttempt);
    }

    /**
     * Get the later time
     * @param time the time to compare in ms - non relative
     * @return whichever is later, 10ms after {@link #CHECK_THRESHOLD} or given time
     *         returned value is relative
     */
    private long getMaxTime(long time) {
        final long timeRemaining = CHECK_THRESHOLD - getLastAttemptTimePassed();
        return System.currentTimeMillis() + Math.max(timeRemaining + 10, time);
    }

    private void setSecondaryWakeAlarm() {
        final long time = getMaxTime(ALARM_SECONDARY);
        Logger.d("Setting secondary alarm for %s", mSdf.format(new Date(time)));
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, time, mAlarmSecondaryWake);
    }

    private void cancelSecondaryWakeAlarm() {
        Logger.d("Cancelling secondary alarm");
        mAlarmManager.cancel(mAlarmSecondaryWake);
    }

    private void setDetectSleepAlarm() {
        final long time = System.currentTimeMillis() + ALARM_DETECT_SLEEP_TIME;
        Logger.i("Setting sleep detection alarm (exact) for %s", mSdf.format(new Date(time)));
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, time, mAlarmDetectSleep);
    }

    private void cancelDetectSleepAlarm() {
        Logger.d("Cancelling sleep detection alarm");
        mAlarmManager.cancel(mAlarmDetectSleep);
    }

    @Override
    public void onScreenState(boolean state) {
        final boolean enabled = Config.getInstance(this).getSchedulerSleepEnabled();
        if (!enabled)
            mScreenState.stop();
        if (mIsStopped || mIsCustomAlarm || !enabled)
            return;
        Logger.d("onScreenState = " + state);
        if (!state) {
            setDetectSleepAlarm();
            return;
        }
        cancelDetectSleepAlarm();
        // we received a screen on state, do a check if we passed the threshold
        checkForUpdates(false);
    }

    private void checkForUpdates(boolean force) {
        if (!force && !isTimePassed()) {
            Logger.i("Skip scheduler checkForUpdates");
            return;
        }
        UpdateService.start(this, UpdateService.ACTION_SCHEDULER);
    }

    private void setCustomAlarmFromPrefs(final String alarmType) {
        final String dailyAlarmTime = mPrefs.getString(
                SettingsActivity.PREF_SCHEDULER_DAILY_TIME, "00:00");
        final String weeklyAlarmDay = mPrefs.getString(
                SettingsActivity.PREF_SCHEDULER_WEEK_DAY, "1");
        final boolean dailyAlarm = alarmType.equals(SettingsActivity.PREF_SCHEDULER_MODE_DAILY);
        final boolean weeklyAlarm = alarmType.equals(SettingsActivity.PREF_SCHEDULER_MODE_WEEKLY);

        if (dailyAlarm) {
            String[] timeParts = dailyAlarmTime.split(":");
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);
            final Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, minute);

            SimpleDateFormat format = new SimpleDateFormat("HH:mm");
            Logger.i("Setting daily alarm to %s", format.format(c.getTime()));
            mAlarmManager.cancel(mAlarmCustom);
            mAlarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                    c.getTimeInMillis(), AlarmManager.INTERVAL_DAY, mAlarmCustom);
        } else if (weeklyAlarm) {
            String[] timeParts = dailyAlarmTime.split(":");
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);
            final Calendar c = Calendar.getInstance();
            c.set(Calendar.DAY_OF_WEEK, Integer.parseInt(weeklyAlarmDay));
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, minute);
            // next week
            if (c.getTimeInMillis() < Calendar.getInstance().getTimeInMillis()) {
                c.set(Calendar.WEEK_OF_YEAR, Calendar.getInstance().get(Calendar.WEEK_OF_YEAR) + 1);
            }

            SimpleDateFormat format = new SimpleDateFormat("EEEE, MMMM d, yyyy - HH:mm");
            Logger.i("Setting weekly alarm to %s", format.format(c.getTime()));
            mAlarmManager.cancel(mAlarmCustom);
            mAlarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                    c.getTimeInMillis(), AlarmManager.INTERVAL_DAY * 7, mAlarmCustom);
        }
    }
}
