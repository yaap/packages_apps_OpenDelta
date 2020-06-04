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
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnMultiChoiceClickListener;
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
import android.view.MenuItem;
import android.widget.TimePicker;
import android.widget.Toast;

public class SettingsFragment extends PreferenceFragment implements
        OnPreferenceChangeListener, OnTimeSetListener {
    private static final String KEY_NETWORKS = "networks_config";
    private static final String KEY_SECURE_MODE = "secure_mode";
    private static final String KEY_AB_PERF_MODE = "ab_perf_mode";
    private static final String KEY_CATEGORY_DOWNLOAD = "category_download";
    private static final String KEY_CATEGORY_FLASHING = "category_flashing";
    private static final String KEY_SHOW_INFO = "show_info";
    private static final String PREF_CLEAN_FILES = "clear_files";
    private static final String PREF_FILE_FLASH_HINT_SHOWN = "file_flash_hint_shown";
    private static final String KEY_CATEGORY_ADMIN = "category_admin";


    private Preference mNetworksConfig;
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
    private SwitchPreference mFileFlash;
    private SwitchPreference mShowInfo;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getContext());
        mConfig = Config.getInstance(getContext());

        addPreferencesFromResource(R.xml.settings);
        mNetworksConfig = (Preference) findPreference(KEY_NETWORKS);

        String autoDownload = prefs.getString(SettingsActivity.PREF_AUTO_DOWNLOAD, getDefaultAutoDownloadValue());
        int autoDownloadValue = Integer.valueOf(autoDownload).intValue();
        mAutoDownload = (ListPreference) findPreference(SettingsActivity.PREF_AUTO_DOWNLOAD);
        mAutoDownload.setOnPreferenceChangeListener(this);
        mAutoDownload.setValue(autoDownload);
        mAutoDownload.setSummary(mAutoDownload.getEntry());

        mBatteryLevel = (ListPreference) findPreference(SettingsActivity.PREF_BATTERY_LEVEL);
        mBatteryLevel.setOnPreferenceChangeListener(this);
        mBatteryLevel.setSummary(mBatteryLevel.getEntry());
        mChargeOnly = (SwitchPreference) findPreference(SettingsActivity.PREF_CHARGE_ONLY);
        mBatteryLevel.setEnabled(!prefs.getBoolean(SettingsActivity.PREF_CHARGE_ONLY, true));
        mSecureMode = (SwitchPreference) findPreference(KEY_SECURE_MODE);
        mSecureMode.setEnabled(mConfig.getSecureModeEnable());
        mSecureMode.setChecked(mConfig.getSecureModeCurrent());
        mABPerfMode = (SwitchPreference) findPreference(KEY_AB_PERF_MODE);
        mABPerfMode.setChecked(mConfig.getABPerfModeCurrent());
        mABPerfMode.setOnPreferenceChangeListener(this);
        mFileFlash = (SwitchPreference) findPreference(SettingsActivity.PREF_FILE_FLASH);
        mShowInfo = (SwitchPreference) findPreference(KEY_SHOW_INFO);
        mShowInfo.setChecked(mConfig.getShowInfo());

        mAutoDownloadCategory = (PreferenceCategory) findPreference(KEY_CATEGORY_DOWNLOAD);
        PreferenceCategory flashingCategory =
                (PreferenceCategory) findPreference(KEY_CATEGORY_FLASHING);

        if (!Config.isABDevice()) {
            flashingCategory.removePreference(mABPerfMode);
            flashingCategory.removePreference(mFileFlash);
        }

        mAutoDownloadCategory
                .setEnabled(autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_CHECK);

        mSchedulerMode = (ListPreference) findPreference(SettingsActivity.PREF_SCHEDULER_MODE);
        mSchedulerMode.setOnPreferenceChangeListener(this);
        mSchedulerMode.setSummary(mSchedulerMode.getEntry());
        mSchedulerMode
                .setEnabled(autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_DISABLED);

        String schedulerMode = prefs.getString(SettingsActivity.PREF_SCHEDULER_MODE, SettingsActivity.PREF_SCHEDULER_MODE_SMART);
        mSchedulerDailyTime = (Preference) findPreference(SettingsActivity.PREF_SCHEDULER_DAILY_TIME);
        mSchedulerDailyTime.setEnabled(!schedulerMode.equals(SettingsActivity.PREF_SCHEDULER_MODE_SMART));
        mSchedulerDailyTime.setSummary(prefs.getString(
                SettingsActivity.PREF_SCHEDULER_DAILY_TIME, "00:00"));

        mCleanFiles = (Preference) findPreference(PREF_CLEAN_FILES);

        mScheduleWeekDay = (ListPreference) findPreference(SettingsActivity.PREF_SCHEDULER_WEEK_DAY);
        mScheduleWeekDay.setEntries(getWeekdays());
        mScheduleWeekDay.setSummary(mScheduleWeekDay.getEntry());
        mScheduleWeekDay.setOnPreferenceChangeListener(this);
        mScheduleWeekDay.setEnabled(schedulerMode.equals(SettingsActivity.PREF_SCHEDULER_MODE_WEEKLY));
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {

        if (preference == mNetworksConfig) {
            showNetworks();
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
                                    : R.string.secure_mode_disabled_description)))
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
        } else if (preference == mFileFlash) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            if (!prefs.getBoolean(PREF_FILE_FLASH_HINT_SHOWN, false)) {
                (new AlertDialog.Builder(getContext()))
                        .setTitle(R.string.flash_file_notice_title)
                        .setMessage(R.string.flash_file_notice_message)
                        .setCancelable(true)
                        .setPositiveButton(android.R.string.ok, null).show();
            }
            prefs.edit().putBoolean(PREF_FILE_FLASH_HINT_SHOWN, true).commit();
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
            int autoDownloadValue = Integer.valueOf(value).intValue();
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

    private void showNetworks() {
        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getContext());

        int flags = prefs.getInt(UpdateService.PREF_AUTO_UPDATE_NETWORKS_NAME,
                UpdateService.PREF_AUTO_UPDATE_NETWORKS_DEFAULT);
        final boolean[] checkedItems = new boolean[] {
                (flags & NetworkState.ALLOW_2G) == NetworkState.ALLOW_2G,
                (flags & NetworkState.ALLOW_3G) == NetworkState.ALLOW_3G,
                (flags & NetworkState.ALLOW_4G) == NetworkState.ALLOW_4G,
                (flags & NetworkState.ALLOW_WIFI) == NetworkState.ALLOW_WIFI,
                (flags & NetworkState.ALLOW_ETHERNET) == NetworkState.ALLOW_ETHERNET,
                (flags & NetworkState.ALLOW_UNKNOWN) == NetworkState.ALLOW_UNKNOWN };

        (new AlertDialog.Builder(getContext()))
                .setTitle(R.string.title_networks)
                .setMultiChoiceItems(
                        new CharSequence[] { getString(R.string.network_2g),
                                getString(R.string.network_3g),
                                getString(R.string.network_4g),
                                getString(R.string.network_wifi),
                                getString(R.string.network_ethernet),
                                getString(R.string.network_unknown), },
                        checkedItems, new OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog,
                                    int which, boolean isChecked) {
                                checkedItems[which] = isChecked;
                            }
                        })
                .setPositiveButton(android.R.string.ok, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int flags = 0;
                        if (checkedItems[0])
                            flags += NetworkState.ALLOW_2G;
                        if (checkedItems[1])
                            flags += NetworkState.ALLOW_3G;
                        if (checkedItems[2])
                            flags += NetworkState.ALLOW_4G;
                        if (checkedItems[3])
                            flags += NetworkState.ALLOW_WIFI;
                        if (checkedItems[4])
                            flags += NetworkState.ALLOW_ETHERNET;
                        if (checkedItems[5])
                            flags += NetworkState.ALLOW_UNKNOWN;
                        prefs.edit()
                                .putInt(UpdateService.PREF_AUTO_UPDATE_NETWORKS_NAME,
                                        flags).commit();
                    }
                }).setNegativeButton(android.R.string.cancel, null)
                .setCancelable(true).show();
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
        List<String> weekDayList = new ArrayList<String>();
        weekDayList.addAll(Arrays.asList(dfs.getWeekdays()).subList(1, dfs.getWeekdays().length));
        return weekDayList.toArray(new String[weekDayList.size()]);
    }

    private void clearState(SharedPreferences prefs) {
        prefs.edit().putString(UpdateService.PREF_LATEST_FULL_NAME, UpdateService.PREF_READY_FILENAME_DEFAULT).commit();
        prefs.edit().putString(UpdateService.PREF_LATEST_DELTA_NAME, UpdateService.PREF_READY_FILENAME_DEFAULT).commit();
        prefs.edit().putString(UpdateService.PREF_READY_FILENAME_NAME, UpdateService.PREF_READY_FILENAME_DEFAULT).commit();
        prefs.edit().putLong(UpdateService.PREF_DOWNLOAD_SIZE, -1).commit();
        prefs.edit().putBoolean(UpdateService.PREF_DELTA_SIGNATURE, false).commit();
        prefs.edit().putString(UpdateService.PREF_INITIAL_FILE, UpdateService.PREF_READY_FILENAME_DEFAULT).commit();
    }
}
