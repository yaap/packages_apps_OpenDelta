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

import java.io.File;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.app.TimePickerDialog.OnTimeSetListener;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.SwitchPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import android.text.Html;
import android.text.format.DateFormat;
import android.widget.TimePicker;
import android.widget.Toast;

public class SettingsFragment extends PreferenceFragment implements
        OnPreferenceChangeListener, OnTimeSetListener {
    private static final String KEY_NETWORKS = "metered_networks_config";
    private static final String KEY_SECURE_MODE = "secure_mode";
    private static final String KEY_AB_PERF_MODE = "ab_perf_mode";
    private static final String KEY_CATEGORY_DOWNLOAD = "category_download";
    private static final String KEY_CATEGORY_FLASHING = "category_flashing";
    private static final String KEY_SHOW_INFO = "show_info";
    private static final String PREF_CLEAN_FILES = "clear_files";

    private SwitchPreference mNetworksConfig;
    private ListPreference mAutoDownload;
    private ListPreference mBatteryLevel;
    private SwitchPreference mChargeOnly;
    private SwitchPreference mSecureMode;
    private SwitchPreference mABPerfMode;
    private Config mConfig;
    private PreferenceCategory mAutoDownloadCategory;
    private ListPreference mSchedulerMode;
    private Preference mSchedulerDailyTime;
    private Preference mCleanFiles;
    private ListPreference mScheduleWeekDay;
    private SwitchPreference mShowInfo;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getContext());
        mConfig = Config.getInstance(getContext());

        addPreferencesFromResource(R.xml.settings);
        mNetworksConfig = findPreference(KEY_NETWORKS);
        mNetworksConfig.setChecked(prefs.getBoolean(UpdateService.PREF_AUTO_UPDATE_METERED_NETWORKS, false));

        String autoDownload = prefs.getString(SettingsActivity.PREF_AUTO_DOWNLOAD, getDefaultAutoDownloadValue());
        int autoDownloadValue = Integer.parseInt(autoDownload);
        mAutoDownload = findPreference(SettingsActivity.PREF_AUTO_DOWNLOAD);
        mAutoDownload.setOnPreferenceChangeListener(this);
        mAutoDownload.setValue(autoDownload);
        mAutoDownload.setSummary(mAutoDownload.getEntry());

        mBatteryLevel = findPreference(SettingsActivity.PREF_BATTERY_LEVEL);
        mBatteryLevel.setOnPreferenceChangeListener(this);
        mBatteryLevel.setSummary(mBatteryLevel.getEntry());
        mChargeOnly = findPreference(SettingsActivity.PREF_CHARGE_ONLY);
        mBatteryLevel.setEnabled(!prefs.getBoolean(SettingsActivity.PREF_CHARGE_ONLY, true));
        mSecureMode = findPreference(KEY_SECURE_MODE);
        mSecureMode.setEnabled(mConfig.getSecureModeEnable());
        mSecureMode.setChecked(mConfig.getSecureModeCurrent());
        mABPerfMode = findPreference(KEY_AB_PERF_MODE);
        mABPerfMode.setChecked(mConfig.getABPerfModeCurrent());
        mABPerfMode.setOnPreferenceChangeListener(this);
        mShowInfo = findPreference(KEY_SHOW_INFO);
        mShowInfo.setChecked(mConfig.getShowInfo());

        mAutoDownloadCategory = findPreference(KEY_CATEGORY_DOWNLOAD);
        PreferenceCategory flashingCategory = findPreference(KEY_CATEGORY_FLASHING);

        if (!Config.isABDevice()) {
            flashingCategory.removePreference(mABPerfMode);
        }

        mAutoDownloadCategory
                .setEnabled(autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_CHECK);

        mSchedulerMode = findPreference(SettingsActivity.PREF_SCHEDULER_MODE);
        mSchedulerMode.setOnPreferenceChangeListener(this);
        mSchedulerMode.setSummary(mSchedulerMode.getEntry());
        mSchedulerMode
                .setEnabled(autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_DISABLED);

        String schedulerMode = prefs.getString(SettingsActivity.PREF_SCHEDULER_MODE, SettingsActivity.PREF_SCHEDULER_MODE_SMART);
        mSchedulerDailyTime = findPreference(SettingsActivity.PREF_SCHEDULER_DAILY_TIME);
        mSchedulerDailyTime.setEnabled(!schedulerMode.equals(SettingsActivity.PREF_SCHEDULER_MODE_SMART));
        mSchedulerDailyTime.setSummary(prefs.getString(
                SettingsActivity.PREF_SCHEDULER_DAILY_TIME, "00:00"));

        mCleanFiles = findPreference(PREF_CLEAN_FILES);

        mScheduleWeekDay = findPreference(SettingsActivity.PREF_SCHEDULER_WEEK_DAY);
        mScheduleWeekDay.setEntries(getWeekdays());
        mScheduleWeekDay.setSummary(mScheduleWeekDay.getEntry());
        mScheduleWeekDay.setOnPreferenceChangeListener(this);
        mScheduleWeekDay.setEnabled(schedulerMode.equals(SettingsActivity.PREF_SCHEDULER_MODE_WEEKLY));
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference == mNetworksConfig) {
            boolean value = ((SwitchPreference) preference).isChecked();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            prefs.edit().putBoolean(UpdateService.PREF_AUTO_UPDATE_METERED_NETWORKS, value).commit();
            return true;
        } else if (preference == mChargeOnly) {
            boolean value = ((SwitchPreference) preference).isChecked();
            mBatteryLevel.setEnabled(!value);
            return true;
        } else if (preference == mSecureMode) {
            boolean value = ((SwitchPreference) preference).isChecked();
            mConfig.setSecureModeCurrent(value);
            (new AlertDialog.Builder(getContext()))
                    .setTitle(
                            value ? R.string.secure_mode_enabled_title
                                    : R.string.secure_mode_disabled_title)
                    .setMessage(
                            Html.fromHtml(getString(value ? R.string.secure_mode_enabled_description
                                    : R.string.secure_mode_disabled_description), Html.FROM_HTML_MODE_LEGACY))
                    .setCancelable(true)
                    .setPositiveButton(android.R.string.ok, null).show();
            return true;
        } else if (preference == mSchedulerDailyTime) {
            showTimePicker();
            return true;
        } else if (preference == mCleanFiles) {
            int numDeletedFiles = cleanFiles();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            clearState(prefs);
            prefs.edit().putBoolean(SettingsActivity.PREF_START_HINT_SHOWN, false).commit();
            Toast.makeText(getContext(), String.format(getString(R.string.clean_files_feedback),
                    numDeletedFiles), Toast.LENGTH_LONG).show();
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
            int autoDownloadValue = Integer.parseInt(value);
            mAutoDownloadCategory
                    .setEnabled(autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_CHECK);
            mSchedulerMode
                    .setEnabled(autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_DISABLED);
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
            mSchedulerDailyTime.setEnabled(!value.equals(SettingsActivity.PREF_SCHEDULER_MODE_SMART));
            mScheduleWeekDay.setEnabled(value.equals(SettingsActivity.PREF_SCHEDULER_MODE_WEEKLY));
            return true;
        } else if (preference == mScheduleWeekDay) {
            int idx = mScheduleWeekDay.findIndexOfValue((String) newValue);
            mScheduleWeekDay.setSummary(mScheduleWeekDay.getEntries()[idx]);
            return true;
        } else if (preference.equals(mABPerfMode)) {
            mConfig.setABPerfModeCurrent((boolean) newValue);
            return true;
        } else if (preference.equals(mShowInfo)) {
            mConfig.setShowInfo((boolean) newValue);
            return true;
        }
        return false;
    }

    @Override
    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getContext());
        String prefValue = String.format(Locale.ENGLISH, "%02d:%02d",
                hourOfDay, minute);
        prefs.edit().putString(SettingsActivity.PREF_SCHEDULER_DAILY_TIME, prefValue).commit();
        mSchedulerDailyTime.setSummary(prefValue);
    }

    private void showTimePicker() {
        final Calendar c = Calendar.getInstance();
        final int hour = c.get(Calendar.HOUR_OF_DAY);
        final int minute = c.get(Calendar.MINUTE);

        new TimePickerDialog(getContext(), this, hour, minute,
                DateFormat.is24HourFormat(getContext())).show();
    }

    private String getDefaultAutoDownloadValue() {
        return isSupportedVersion() ? UpdateService.PREF_AUTO_DOWNLOAD_CHECK_STRING : UpdateService.PREF_AUTO_DOWNLOAD_DISABLED_STRING;
    }

    private boolean isSupportedVersion() {
        return mConfig.isOfficialVersion();
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
        prefs.edit().putString(UpdateService.PREF_LATEST_DELTA_NAME, null).commit();
        prefs.edit().putString(UpdateService.PREF_READY_FILENAME_NAME, null).commit();
        prefs.edit().putLong(UpdateService.PREF_DOWNLOAD_SIZE, -1).commit();
        prefs.edit().putBoolean(UpdateService.PREF_DELTA_SIGNATURE, false).commit();
        prefs.edit().putString(UpdateService.PREF_INITIAL_FILE, null).commit();
    }
}
