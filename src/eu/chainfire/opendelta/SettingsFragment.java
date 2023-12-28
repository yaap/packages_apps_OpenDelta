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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.MenuItem;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.preference.SwitchPreference;
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

public class SettingsFragment extends PreferenceFragment implements
        OnPreferenceChangeListener, OnTimeSetListener {
    private static final String KEY_NETWORKS = "metered_networks_config";
    private static final String KEY_AB_PERF_MODE = "ab_perf_mode";
    private static final String KEY_AB_WAKE_LOCK = "ab_wake_lock";
    private static final String KEY_AB_STREAM = "ab_stream_flashing";
    private static final String KEY_CATEGORY_DOWNLOAD = "category_download";
    private static final String KEY_CATEGORY_FLASHING = "category_flashing";
    private static final String PREF_FORCE_REFLASH = "force_reflash";
    private static final String PREF_CLEAN_FILES = "clear_files";

    private SwitchPreference mNetworksConfig;
    private ListPreference mAutoDownload;
    private ListPreference mBatteryLevel;
    private SwitchPreference mChargeOnly;
    private SwitchPreference mABPerfMode;
    private SwitchPreference mABWakeLock;
    private SwitchPreference mABStream;
    private Config mConfig;
    private PreferenceCategory mAutoDownloadCategory;
    private ListPreference mSchedulerMode;
    private SwitchPreference mSchedulerSleep;
    private Preference mSchedulerDailyTime;
    private Preference mForceReflash;
    private Preference mCleanFiles;
    private ListPreference mScheduleWeekDay;

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
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference == mNetworksConfig) {
            boolean value = ((SwitchPreference) preference).isChecked();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            prefs.edit().putBoolean(UpdateService.PREF_AUTO_UPDATE_METERED_NETWORKS, value).apply();
            return true;
        } else if (preference == mChargeOnly) {
            boolean value = ((SwitchPreference) preference).isChecked();
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
