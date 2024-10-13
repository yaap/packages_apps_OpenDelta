/*
 *  Copyright (C) 2015 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package eu.chainfire.opendelta;

import android.app.TimePickerDialog;
import android.app.TimePickerDialog.OnTimeSetListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.MenuItem;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SettingsFragment extends PreferenceFragment implements
        OnPreferenceChangeListener, OnTimeSetListener {
    private static final String KEY_NETWORKS = "metered_networks_config";
    private static final String KEY_AB_PERF_MODE = "ab_perf_mode";
    private static final String KEY_AB_WAKE_LOCK = "ab_wake_lock";
    private static final String KEY_AB_STREAM = "ab_stream_flashing";
    private static final String KEY_CATEGORY_DOWNLOAD = "category_download";
    private static final String KEY_CATEGORY_FLASHING = "category_flashing";
    private static final String KEY_CERT_CHECK = "cert_check";
    private static final String KEY_CERT_STATUS = "cert_status";
    private static final String PREF_FORCE_REFLASH = "force_reflash";
    private static final String PREF_CLEAN_FILES = "clear_files";
    private static final String CERT_OVERLAY_PKG_NAME = "android.yaap.certifiedprops.overlay";

    private SwitchPreferenceCompat mNetworksConfig;
    private ListPreference mAutoDownload;
    private ListPreference mBatteryLevel;
    private SwitchPreferenceCompat mChargeOnly;
    private SwitchPreferenceCompat mABPerfMode;
    private SwitchPreferenceCompat mABWakeLock;
    private SwitchPreferenceCompat mABStream;
    private Config mConfig;
    private PreferenceCategory mAutoDownloadCategory;
    private ListPreference mSchedulerMode;
    private SwitchPreferenceCompat mSchedulerSleep;
    private Preference mSchedulerDailyTime;
    private Preference mForceReflash;
    private Preference mCleanFiles;
    private ListPreference mScheduleWeekDay;
    private Preference mCertCheck;
    private Preference mCertStatus;

    private final HandlerThread mHandlerThread = new HandlerThread("OpenDelta: Cert handler thread");
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Handler mHandler = null;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        mConfig = Config.getInstance(getContext());

        addPreferencesFromResource(R.xml.settings);
        mNetworksConfig = findPreference(KEY_NETWORKS);
        mNetworksConfig.setChecked(prefs.getBoolean(UpdateService.PREF_AUTO_UPDATE_METERED_NETWORKS, false));

        final String autoDownload = prefs.getString(SettingsActivity.PREF_AUTO_DOWNLOAD,
                Integer.toString(UpdateService.PREF_AUTO_DOWNLOAD_CHECK));
        mAutoDownload = findPreference(SettingsActivity.PREF_AUTO_DOWNLOAD);
        mAutoDownload.setOnPreferenceChangeListener(this);
        mAutoDownload.setValue(autoDownload);
        mAutoDownload.setSummary(mAutoDownload.getEntry());
        mAutoDownloadCategory = findPreference(KEY_CATEGORY_DOWNLOAD);

        mChargeOnly = findPreference(SettingsActivity.PREF_CHARGE_ONLY);
        mBatteryLevel = findPreference(SettingsActivity.PREF_BATTERY_LEVEL);
        mBatteryLevel.setOnPreferenceChangeListener(this);
        mBatteryLevel.setSummary(mBatteryLevel.getEntry());

        if (!Config.isABDevice() || !mConfig.getABPerfModeSupport()) {
            getPreferenceScreen().removePreference(findPreference(KEY_AB_PERF_MODE));
        } else {
            mABPerfMode = findPreference(KEY_AB_PERF_MODE);
            mABPerfMode.setChecked(mConfig.getABPerfModeCurrent());
            mABPerfMode.setOnPreferenceChangeListener(this);
        }

        if (!Config.isABDevice()) {
            getPreferenceScreen().removePreference(findPreference(KEY_CATEGORY_FLASHING));
        } else {
            mABWakeLock = findPreference(KEY_AB_WAKE_LOCK);
            mABWakeLock.setChecked(mConfig.getABWakeLockCurrent());
            mABWakeLock.setOnPreferenceChangeListener(this);
            mABStream = findPreference(KEY_AB_STREAM);
            mABStream.setChecked(mConfig.getABStreamCurrent());
            mABStream.setOnPreferenceChangeListener(this);
        }

        mSchedulerMode = findPreference(SettingsActivity.PREF_SCHEDULER_MODE);
        mSchedulerMode.setOnPreferenceChangeListener(this);
        mSchedulerMode.setSummary(mSchedulerMode.getEntry());

        final String schedulerMode = prefs.getString(SettingsActivity.PREF_SCHEDULER_MODE,
                SettingsActivity.PREF_SCHEDULER_MODE_SMART);
        mSchedulerDailyTime = findPreference(SettingsActivity.PREF_SCHEDULER_DAILY_TIME);
        mSchedulerDailyTime.setSummary(prefs.getString(
                SettingsActivity.PREF_SCHEDULER_DAILY_TIME, "00:00"));
        
        final boolean sleepEnabled = prefs.getBoolean(SettingsActivity.PREF_SCHEDULER_SLEEP, true);
        mSchedulerSleep = findPreference(SettingsActivity.PREF_SCHEDULER_SLEEP);
        mSchedulerSleep.setChecked(sleepEnabled);
        mSchedulerSleep.setOnPreferenceChangeListener(this);

        mForceReflash = findPreference(PREF_FORCE_REFLASH);
        mCleanFiles = findPreference(PREF_CLEAN_FILES);

        mScheduleWeekDay = findPreference(SettingsActivity.PREF_SCHEDULER_WEEK_DAY);
        mScheduleWeekDay.setEntries(getWeekdays());
        mScheduleWeekDay.setSummary(mScheduleWeekDay.getEntry());
        mScheduleWeekDay.setOnPreferenceChangeListener(this);

        updateEnablement(autoDownload, mSchedulerMode.getEntry().toString());

        mCertCheck = findPreference(KEY_CERT_CHECK);
        mCertStatus = findPreference(KEY_CERT_STATUS);

        updateCertStatus(-1);
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference == mNetworksConfig) {
            boolean value = ((SwitchPreferenceCompat) preference).isChecked();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            prefs.edit().putBoolean(UpdateService.PREF_AUTO_UPDATE_METERED_NETWORKS, value).apply();
            return true;
        } else if (preference == mChargeOnly) {
            boolean value = ((SwitchPreferenceCompat) preference).isChecked();
            mBatteryLevel.setEnabled(!value);
            return true;
        } else if (preference == mSchedulerDailyTime) {
            showTimePicker();
            return true;
        } else if (preference == mCleanFiles) {
            int numDeletedFiles = cleanFiles();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            clearState(prefs);
            prefs.edit().putBoolean(SettingsActivity.PREF_START_HINT_SHOWN, false).apply();
            Toast.makeText(getContext(), String.format(getString(R.string.clean_files_feedback),
                    numDeletedFiles), Toast.LENGTH_LONG).show();
            State.getInstance().update(State.ACTION_NONE);
            return true;
        } else if (preference == mForceReflash) {
            UpdateService.start(getContext(), UpdateService.ACTION_FORCE_FLASH);
            Toast.makeText(getContext(), getString(R.string.force_flash_feedback),
                    Toast.LENGTH_LONG).show();
            return true;
        } else if (preference == mCertCheck) {
            mCertCheck.setEnabled(false);
            mCertCheck.setSummary(R.string.state_action_checking);
            checkForCerts();
            return true;
        } else if (preference == mCertStatus) {
            mCertStatus.setEnabled(false);
            mCertCheck.setEnabled(false);
            mCertCheck.setSummary(R.string.state_action_downloading);
            updateCerts();
        }
        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mAutoDownload) {
            String value = (String) newValue;
            int idx = mAutoDownload.findIndexOfValue(value);
            mAutoDownload.setSummary(mAutoDownload.getEntries()[idx]);
            mAutoDownload.setValueIndex(idx);
            updateEnablement(value, null);
            return true;
        } else if (preference == mBatteryLevel) {
            String value = (String) newValue;
            int idx = mBatteryLevel.findIndexOfValue(value);
            mBatteryLevel.setSummary(mBatteryLevel.getEntries()[idx]);
            mBatteryLevel.setValueIndex(idx);
            return true;
        } else if (preference == mSchedulerMode) {
            String value = (String) newValue;
            int idx = mSchedulerMode.findIndexOfValue(value);
            mSchedulerMode.setSummary(mSchedulerMode.getEntries()[idx]);
            mSchedulerMode.setValueIndex(idx);
            updateEnablement(null, value);
            return true;
        } else if (preference == mSchedulerSleep) {
            mConfig.setSchedulerSleepEnabled((boolean) newValue);
            return true;
        } else if (preference == mScheduleWeekDay) {
            int idx = mScheduleWeekDay.findIndexOfValue((String) newValue);
            mScheduleWeekDay.setSummary(mScheduleWeekDay.getEntries()[idx]);
            return true;
        } else if (preference.equals(mABPerfMode)) {
            mConfig.setABPerfModeCurrent((boolean) newValue);
            return true;
        } else if (preference.equals(mABWakeLock)) {
            mConfig.setABWakeLockCurrent((boolean) newValue);
            return true;
        } else if (preference.equals(mABStream)) {
            mConfig.setABStreamCurrent((boolean) newValue);
            return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Respond to the action bar's Up/Home button
        if (item.getItemId() == android.R.id.home) {
            getActivity().finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        String prefValue = String.format(Locale.ENGLISH, "%02d:%02d",
                hourOfDay, minute);
        prefs.edit().putString(SettingsActivity.PREF_SCHEDULER_DAILY_TIME, prefValue).apply();
        mSchedulerDailyTime.setSummary(prefValue);
    }

    @Override
    public void onStop() {
        if (mHandler != null) {
            mHandlerThread.quitSafely();
        }
        super.onStop();
    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
        }
        return mHandler;
    }

    private void updateEnablement(String autoDownload, String schedulerMode) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        if (autoDownload == null) {
            autoDownload = prefs.getString(SettingsActivity.PREF_AUTO_DOWNLOAD,
                    Integer.toString(UpdateService.PREF_AUTO_DOWNLOAD_CHECK));
        }
        if (schedulerMode == null) {
            schedulerMode = prefs.getString(SettingsActivity.PREF_SCHEDULER_MODE,
                    SettingsActivity.PREF_SCHEDULER_MODE_SMART);
        }

        final int autoDownloadValue = Integer.parseInt(autoDownload);
        final boolean isEnabled = autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_DISABLED;
        final boolean isSmart = isEnabled &&
                schedulerMode.equals(SettingsActivity.PREF_SCHEDULER_MODE_SMART);
        final boolean isWeekly = isEnabled && !isSmart &&
                schedulerMode.equals(SettingsActivity.PREF_SCHEDULER_MODE_WEEKLY);
        final boolean isDownload = isEnabled &&
                autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_CHECK;

        mSchedulerMode.setEnabled(isEnabled);
        mSchedulerSleep.setEnabled(isEnabled && isSmart);
        mScheduleWeekDay.setEnabled(isEnabled && isWeekly);
        mSchedulerDailyTime.setEnabled(isEnabled && !isSmart);
        mBatteryLevel.setEnabled(isDownload && !mChargeOnly.isChecked());
        mAutoDownloadCategory.setEnabled(isDownload);
    }

    private void checkForCerts() {
        getHandler().post(() -> {
            String jsonStr = Download.asString(mConfig.getUrlCertJson());
            if (jsonStr == null || jsonStr.length() == 0) {
                Toast.makeText(getContext(), R.string.cert_fetch_fail,
                        Toast.LENGTH_LONG).show();
                mMainHandler.post(() -> {
                    mCertCheck.setEnabled(true);
                    mCertCheck.setSummary(R.string.cert_check_summary);
                });
                return;
            }
            try {
                JSONObject object = new JSONObject(jsonStr);
                if (object.has("version")) {
                    int version = object.getInt("version");
                    mMainHandler.post(() -> { updateCertStatus(version); });
                }
            } catch (Exception e) {
                Logger.ex(e);
            } finally {
                mMainHandler.post(() -> {
                    mCertCheck.setEnabled(true);
                    mCertCheck.setSummary(R.string.cert_check_summary);
                });
            }
        });
    }

    private void updateCerts() {
        final String path = mConfig.getPathBase() + "cert.apk";
        final String url = mConfig.getUrlCertJson().replace(".json", ".apk");
        Download.ApkDownloadListener listener = new Download.ApkDownloadListener() {
            @Override
            public void onFinish(boolean success) {
                if (!success) {
                    Toast.makeText(getContext(), R.string.state_error_download,
                            Toast.LENGTH_LONG).show();
                } else {
                    final Uri uri = FileProvider.getUriForFile(getContext(),
                            getContext().getApplicationContext().getPackageName() + ".provider",
                            new File(path));
                    Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE, uri);
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getContext().startActivity(intent);
                    Toast.makeText(getContext(), R.string.cert_reboot_notice,
                            Toast.LENGTH_LONG).show();
                }
                mCertCheck.setEnabled(true);
                mCertCheck.setSummary(R.string.cert_check_summary);
                mCertStatus.setEnabled(!success);
            }
        };
        getHandler().post(() -> {
            Download.downloadApk(path, url, listener, mMainHandler);
        });
    }

    private long getLocalVersion() {
        PackageManager pm = getContext().getPackageManager();
        long version = -1;
        try {
            PackageInfo pi = pm.getPackageInfo(CERT_OVERLAY_PKG_NAME,
                    PackageManager.MATCH_SYSTEM_ONLY);
            if (pi != null) {
                version = pi.getLongVersionCode();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // do nothing
        }
        return version;
    }

    private void updateCertStatus(long remoteVersion) {
        Resources res = getContext().getResources();
        long version = getLocalVersion();

        final String unknownStr = res.getString(R.string.text_download_size_unknown);
        StringBuilder status = new StringBuilder();
        final String versionStr = String.format(res.getString(R.string.cert_status_version),
                version != -1 ? String.valueOf(version) : unknownStr, Locale.getDefault());
        final String remoteStr = String.format(res.getString(R.string.cert_status_remote),
                remoteVersion != -1 ? String.valueOf(remoteVersion) : unknownStr, Locale.getDefault());
        status.append(versionStr);
        status.append(remoteStr);
        final boolean available = remoteVersion > version;
        if (available) {
            status.append("\n");
            status.append(res.getString(R.string.cert_status_available));
        }
        mCertStatus.setSummary(status.toString());
        mCertStatus.setEnabled(available);
    }

    private void showTimePicker() {
        final Calendar c = Calendar.getInstance();
        final int hour = c.get(Calendar.HOUR_OF_DAY);
        final int minute = c.get(Calendar.MINUTE);

        new TimePickerDialog(getContext(), this, hour, minute,
                DateFormat.is24HourFormat(getContext())).show();
    }

    private int cleanFiles() {
        int deletedFiles = 0;
        String dataFolder = mConfig.getPathBase();
        File[] contents = new File(dataFolder).listFiles();
        if (contents != null) {
            for (File file : contents) {
                if (file.isFile() && file.getName().startsWith(mConfig.getFileBaseNamePrefix())) {
                    file.delete();
                    deletedFiles++;
                }
            }
        }
        return deletedFiles;
    }

    private String[] getWeekdays() {
        DateFormatSymbols dfs = new DateFormatSymbols();
        List<String> weekDayList = new ArrayList<>();
        weekDayList.addAll(Arrays.asList(dfs.getWeekdays()).subList(1, dfs.getWeekdays().length));
        return weekDayList.toArray(new String[weekDayList.size()]);
    }

    private void clearState(SharedPreferences prefs) {
        prefs.edit().putString(UpdateService.PREF_LATEST_FULL_NAME, null).commit();
        prefs.edit().putString(UpdateService.PREF_READY_FILENAME_NAME, null).commit();
        prefs.edit().putLong(UpdateService.PREF_DOWNLOAD_SIZE, -1).commit();
    }
}
