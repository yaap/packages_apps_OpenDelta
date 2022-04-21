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
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import android.app.Activity;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.UpdateEngine;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Html;
import android.text.format.Formatter;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

public class MainActivity extends Activity {
    private TextView title = null;
    private TextView sub = null;
    private ProgressBar progress = null;
    private Button checkNow = null;
    private Button flashNow = null;
    private TextView updateVersion = null;
    private Button buildNow = null;
    private ImageButton stopNow = null;
    private Button rebootNow = null;
    private TextView currentVersion = null;
    private TextView currentVersionHeader;
    private TextView lastChecked = null;
    private TextView lastCheckedHeader = null;
    private TextView downloadSizeHeader = null;
    private TextView downloadSize = null;
    private Config config;
    private boolean mPermOk;
    private TextView mSub2;
    private TextView mProgressPercent;
    private View mProgressEndSpace;
    private int mProgressCurrent = 0;
    private int mProgressMax = 1;
    private boolean mProgressEnabled = false;
    private Button mFileFlashButton;
    private SharedPreferences mPrefs;
    private TextView mUpdateVersionTitle;
    private TextView mExtraText;
    private TextView mInfoText;
    private ImageView mInfoImage;

    private static final int PERMISSIONS_REQUEST_MANAGE_EXTERNAL_STORAGE = 0;
    private static final int ACTIVITY_SELECT_FLASH_FILE = 1;
    private static final String COLON = ":";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        try {
            getActionBar().setIcon(
                    getPackageManager().getApplicationIcon(
                            "com.android.settings"));
        } catch (NameNotFoundException e) {
            // The standard Settings package is not present, so we can't snatch
            // its icon
            Logger.ex(e);
        }
        getActionBar().setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP | ActionBar.DISPLAY_SHOW_TITLE);

        setContentView(R.layout.activity_main);

        title = findViewById(R.id.text_title);
        sub = findViewById(R.id.progress_text);
        mSub2 = findViewById(R.id.progress_text2);
        progress = findViewById(R.id.progress_bar);
        checkNow = findViewById(R.id.button_check_now);
        flashNow = findViewById(R.id.button_flash_now);
        rebootNow = findViewById(R.id.button_reboot_now);
        updateVersion = findViewById(R.id.text_update_version);
        buildNow = findViewById(R.id.button_build_delta);
        stopNow = findViewById(R.id.button_stop);
        currentVersion = findViewById(R.id.text_current_version);
        currentVersionHeader = findViewById(R.id.text_current_version_header);
        lastChecked = findViewById(R.id.text_last_checked);
        lastCheckedHeader = findViewById(R.id.text_last_checked_header);
        downloadSize = findViewById(R.id.text_download_size);
        downloadSizeHeader = findViewById(R.id.text_download_size_header);
        mProgressPercent = findViewById(R.id.progress_percent);
        mProgressEndSpace = findViewById(R.id.progress_end_margin);
        mFileFlashButton = findViewById(R.id.button_select_file);
        mUpdateVersionTitle = findViewById(R.id.text_update_version_header);
        mExtraText = findViewById(R.id.extra_text);
        mInfoText = findViewById(R.id.info_text);
        mInfoImage = findViewById(R.id.info_image);

        config = Config.getInstance(this);
        mPermOk = false;
        requestPermissions();
        updateInfoVisibility();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    private void showAbout() {
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        String opendelta = (thisYear == 2013) ? "2013" : "2013-"
                + thisYear;
        String xdelta = (thisYear == 1997) ? "1997" : "1997-"
                + thisYear;

        AlertDialog dialog = (new AlertDialog.Builder(this))
                .setTitle(R.string.app_name)
                .setMessage(
                        Html.fromHtml(getString(R.string.about_content)
                                .replace("_COPYRIGHT_OPENDELTA_", opendelta)
                                .replace("_COPYRIGHT_XDELTA_", xdelta), Html.FROM_HTML_MODE_LEGACY))
                .setNeutralButton(android.R.string.ok, null)
                .setCancelable(true).show();
        TextView textView = dialog
                .findViewById(android.R.id.message);
        if (textView != null)
            textView.setTypeface(title.getTypeface());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Do not use res IDs in a switch case
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.settings) {
            Intent settingsActivity = new Intent(this, SettingsActivity.class);
            startActivity(settingsActivity);
            return true;
        }
        if (id == R.id.changelog) {
            Intent changelogActivity = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(config.getUrlBaseJson().replace(
                            config.getDevice() + ".json", "Changelog.txt")));
            startActivity(changelogActivity);
            return true;
        }
        if (id == R.id.action_about) {
            showAbout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final IntentFilter updateFilter = new IntentFilter(
            UpdateService.BROADCAST_INTENT);
    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        private String formatLastChecked(long ms) {
            //Date date = new Date(ms);
            if (ms == 0) {
                return "";
            } else {
                SimpleDateFormat format = new SimpleDateFormat("EEEE, MMMM d, yyyy - HH:mm");
                return format.format(ms);
                /*return getString(
                        R.string.last_checked,
                        DateFormat.getDateFormat(MainActivity.this).format(
                                date),
                        DateFormat.getTimeFormat(MainActivity.this).format(
                                date));*/
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String title = "";
            String sub = "";
            String sub2 = "";
            String progressPercent = "";
            String updateVersion = "";
            String extraText = "";
            String downloadSizeText = "";
            long current = 0L;
            long total = 1L;
            boolean enableCheck = false;
            boolean enableFlash = false;
            boolean enableBuild = false;
            boolean enableStop = false;
            boolean enableReboot = false;
            boolean deltaUpdatePossible;
            boolean fullUpdatePossible;
            boolean enableProgress = false;
            boolean disableCheckNow = false;
            boolean disableDataSpeed = false;
            long lastCheckedSaved = mPrefs.getLong(UpdateService.PREF_LAST_CHECK_TIME_NAME,
                    UpdateService.PREF_LAST_CHECK_TIME_DEFAULT);
            String lastCheckedText = lastCheckedSaved != UpdateService.PREF_LAST_CHECK_TIME_DEFAULT ?
                    formatLastChecked(lastCheckedSaved) : getString(R.string.last_checked_never_title_new);
            String fullVersion = config.getVersion();
            String[] versionParts = fullVersion.split("-");
            String versionType = "";
            try {
                versionType = versionParts[3];
            } catch (Exception ignored) {
            }

            String state = intent.getStringExtra(UpdateService.EXTRA_STATE);
            // don't try this at home
            if (state != null) {
                try {
                    title = getString(getResources().getIdentifier(
                            "state_" + state, "string", getPackageName()));
                } catch (Exception e) {
                    // String for this state could not be found (displays empty
                    // string)
                    //Logger.ex(e);
                }
                // check for first start until check button has been pressed
                // use a special title then - but only once
                if (UpdateService.STATE_ACTION_NONE.equals(state)
                        && !mPrefs.getBoolean(SettingsActivity.PREF_START_HINT_SHOWN, false)) {
                    title = getString(R.string.last_checked_never_title_new);
                }
                // don't spill for progress
                if (!UpdateService.isProgressState(state)) {
                    Logger.d("onReceive state = " + state);
                }
            }

            if (UpdateService.STATE_ERROR_DISK_SPACE.equals(state)) {
                enableCheck = true;
                progress.setIndeterminate(false);
                current = intent.getLongExtra(UpdateService.EXTRA_CURRENT,
                        current);
                total = intent.getLongExtra(UpdateService.EXTRA_TOTAL, total);

                current /= 1024L * 1024L;
                total /= 1024L * 1024L;

                extraText = getString(R.string.error_disk_space_sub, current,
                        total);
            } else if (UpdateService.STATE_ERROR_UNKNOWN.equals(state)) {
                enableCheck = true;
                progress.setIndeterminate(false);
            } else if (UpdateService.STATE_ERROR_UNOFFICIAL.equals(state)) {
                enableCheck = true;
                progress.setIndeterminate(false);
                extraText = getString(R.string.state_error_not_official_extra, versionType);
            } else if (UpdateService.STATE_ERROR_DOWNLOAD.equals(state)) {
                enableCheck = true;
                progress.setIndeterminate(false);
            } else if (UpdateService.STATE_ERROR_CONNECTION.equals(state)) {
                enableCheck = true;
                progress.setIndeterminate(false);
            } else if (UpdateService.STATE_ERROR_PERMISSIONS.equals(state)) {
                enableCheck = true;
                progress.setIndeterminate(false);
            } else if (UpdateService.STATE_ERROR_FLASH.equals(state)) {
                enableCheck = true;
                enableFlash = true;
                progress.setIndeterminate(false);
                title = getString(R.string.state_error_flash_title);
            } else if (UpdateService.STATE_ERROR_AB_FLASH.equals(state)) {
                enableCheck = true;
                progress.setIndeterminate(false);
                title = getString(R.string.state_error_ab_flash_title);
                int errorCode = intent.getIntExtra(UpdateService.EXTRA_ERROR_CODE, -1);
                if (errorCode == UpdateEngine.ErrorCodeConstants.PAYLOAD_TIMESTAMP_ERROR) {
                    extraText = getString(R.string.error_ab_timestamp);
                } else if (errorCode == UpdateEngine.ErrorCodeConstants.UPDATED_BUT_NOT_ACTIVE) {
                    extraText = getString(R.string.error_ab_inactive);
                }
            } else if (UpdateService.STATE_ERROR_FLASH_FILE.equals(state)) {
                enableCheck = true;
                progress.setIndeterminate(false);
                title = getString(R.string.state_error_flash_file_title);
            } else if (UpdateService.STATE_ACTION_NONE.equals(state)) {
                enableCheck = true;
                progress.setIndeterminate(false);
            } else if (UpdateService.STATE_ACTION_READY.equals(state)) {
                enableCheck = true;
                enableFlash = true;
                progress.setIndeterminate(false);

                final String flashImage = mPrefs.getString(
                        UpdateService.PREF_READY_FILENAME_NAME, null);
                String flashImageBase = flashImage != null ? new File(
                        flashImage).getName() : null;
                if (flashImageBase != null) {
                    updateVersion = flashImageBase.substring(0,
                            flashImageBase.lastIndexOf('.'));
                }
                mUpdateVersionTitle.setText(R.string.text_update_version_title);
            } else if (UpdateService.STATE_ACTION_FLASH_FILE_READY.equals(state)) {
                enableCheck = true;
                enableFlash = true;
                progress.setIndeterminate(false);
                final String flashImage = mPrefs.getString(
                        UpdateService.PREF_READY_FILENAME_NAME, null);
                mPrefs.edit().putBoolean(UpdateService.PREF_FILE_FLASH, true).commit();
                String flashImageBase = flashImage != null ? new File(
                        flashImage).getName() : null;
                if (flashImageBase != null) {
                    updateVersion = flashImageBase;
                }
                mUpdateVersionTitle.setText(R.string.text_update_file_flash_title);
            } else if (UpdateService.STATE_ACTION_AB_FINISHED.equals(state)) {
                enableReboot = true;
                disableCheckNow = true;
                progress.setIndeterminate(false);

                final String flashImage = mPrefs.getString(
                        UpdateService.PREF_READY_FILENAME_NAME, null);
                String flashImageBase = flashImage != null ? new File(
                        flashImage).getName() : null;
                if (flashImageBase != null) {
                    updateVersion = flashImageBase.substring(0,
                            flashImageBase.lastIndexOf('.'));
                }

                mPrefs.edit().putString(UpdateService.PREF_READY_FILENAME_NAME, null).commit();
                mPrefs.edit().putBoolean(UpdateService.PREF_FILE_FLASH, false).commit();
                mPrefs.edit().putString(UpdateService.PREF_LATEST_FULL_NAME, null).commit();

            } else if (UpdateService.STATE_ACTION_BUILD.equals(state)) {
                enableCheck = true;
                progress.setIndeterminate(false);

                final String latestFull = mPrefs.getString(
                        UpdateService.PREF_LATEST_FULL_NAME, null);
                final String latestDelta = mPrefs.getString(
                        UpdateService.PREF_LATEST_DELTA_NAME, null);

                String latestDeltaZip = latestDelta != null ? new File(
                        latestDelta).getName() : null;

                deltaUpdatePossible = latestDeltaZip != null;
                fullUpdatePossible = latestFull != null;

                if (deltaUpdatePossible) {
                    String latestDeltaBase = latestDelta.substring(0,
                            latestDelta.lastIndexOf('.'));
                    enableBuild = true;
                    updateVersion = latestDeltaBase;
                    title = getString(R.string.state_action_build_delta);
                } else if (fullUpdatePossible) {
                    String latestFullBase = latestFull.substring(0,
                            latestFull.lastIndexOf('.'));
                    enableBuild = true;
                    updateVersion = latestFullBase;
                    title = getString(R.string.state_action_build_full);
                }
                long downloadSize = mPrefs.getLong(
                        UpdateService.PREF_DOWNLOAD_SIZE, -1);
                if(downloadSize == -1) {
                    downloadSizeText = "";
                } else if (downloadSize == 0) {
                    downloadSizeText = getString(R.string.text_download_size_unknown);
                } else {
                    downloadSizeText = Formatter.formatFileSize(context, downloadSize);
                }
            } else if (UpdateService.STATE_ACTION_SEARCHING.equals(state)
                    || UpdateService.STATE_ACTION_CHECKING.equals(state)) {
                enableProgress = true;
                progress.setIndeterminate(true);
                current = 1;
            } else {
                enableProgress = true;
                if (UpdateService.STATE_ACTION_AB_FLASH.equals(state)) {
                    disableDataSpeed = true;
                }
                if (UpdateService.STATE_ACTION_DOWNLOADING.equals(state)) {
                    enableStop = true;
                }
                current = intent.getLongExtra(UpdateService.EXTRA_CURRENT,
                        current);
                total = intent.getLongExtra(UpdateService.EXTRA_TOTAL, total);
                progress.setIndeterminate(false);

                long downloadSize = mPrefs.getLong(
                        UpdateService.PREF_DOWNLOAD_SIZE, -1);
                if(downloadSize == -1) {
                    downloadSizeText = "";
                } else if (downloadSize == 0) {
                    downloadSizeText = getString(R.string.text_download_size_unknown);
                } else {
                    downloadSizeText = Formatter.formatFileSize(context, downloadSize);
                }

                updateVersion = getUpdateVersionString();

                final String flashImage = mPrefs.getString(
                        UpdateService.PREF_READY_FILENAME_NAME, null);
                String flashImageBase = flashImage != null ? new File(flashImage).getName() : null;
                if (flashImageBase != null) {
                    updateVersion = flashImageBase.substring(0,
                            flashImageBase.lastIndexOf('.'));
                }

                // long --> int overflows FTL (progress.setXXX)
                boolean progressInK = false;
                if (total > 1024L * 1024L * 1024L) {
                    progressInK = true;
                    current /= 1024L;
                    total /= 1024L;
                }

                String filename = intent
                        .getStringExtra(UpdateService.EXTRA_FILENAME);
                if (filename != null) {
                    sub = filename;
                    long ms = intent.getLongExtra(UpdateService.EXTRA_MS, 0);
                    progressPercent = String.format(Locale.ENGLISH, "%.0f %%",
                                intent.getFloatExtra(UpdateService.EXTRA_PROGRESS, 0));

                    if ((ms > 500) && (current > 0) && (total > 0)) {
                        float kibps = ((float) current / 1024f)
                                / ((float) ms / 1000f);
                        if (progressInK)
                            kibps *= 1024f;
                        int sec = (int) (((((float) total / (float) current) * (float) ms) - ms) / 1000f);
                        if(disableDataSpeed) {
                            sub2 = String.format(Locale.ENGLISH,
                                    "%02d:%02d",
                                    sec / 60, sec % 60);
                        } else {
                            if (kibps < 1024) {
                                sub2 = String.format(Locale.ENGLISH,
                                        "%.0f KiB/s, %02d:%02d",
                                        kibps, sec / 60, sec % 60);
                            } else {
                                sub2 = String.format(Locale.ENGLISH,
                                        "%.0f MiB/s, %02d:%02d",
                                        kibps / 1024f, sec / 60, sec % 60);
                            }
                        }
                    }
                }
            }
            MainActivity.this.title.setText(title);
            MainActivity.this.sub.setText(sub);
            MainActivity.this.mSub2.setText(sub2);
            MainActivity.this.mProgressPercent.setText(progressPercent);
            MainActivity.this.updateVersion.setText(updateVersion);
            MainActivity.this.mUpdateVersionTitle
                .setText(TextUtils.isEmpty(updateVersion) ? "" : (getString(R.string.text_update_version_title) + COLON));
            MainActivity.this.currentVersion.setText(config.getFilenameBase());
            MainActivity.this.currentVersionHeader.setText(String.format("%s%s", getString(R.string.text_current_version_header_title), COLON));
            MainActivity.this.lastChecked.setText(lastCheckedText);
            MainActivity.this.lastCheckedHeader.setText(String.format("%s%s", getString(R.string.text_last_checked_header_title), COLON));
            MainActivity.this.mExtraText.setText(extraText);
            MainActivity.this.downloadSize.setText(downloadSizeText);
            MainActivity.this.downloadSizeHeader
                .setText(TextUtils.isEmpty(downloadSizeText) ? "" : (getString(R.string.text_download_size_header_title) + COLON));

            mProgressCurrent = (int) current;
            mProgressMax = (int) total;
            mProgressEnabled = enableProgress;

            handleProgressBar();

            checkNow.setEnabled(mPermOk && enableCheck);
            buildNow.setEnabled(mPermOk && enableBuild);
            flashNow.setEnabled(mPermOk && enableFlash);
            rebootNow.setEnabled(enableReboot);
            mFileFlashButton.setEnabled(mPermOk && enableCheck);

            checkNow.setVisibility(disableCheckNow ? View.GONE : View.VISIBLE);
            flashNow.setVisibility(enableFlash ? View.VISIBLE : View.GONE);
            buildNow.setVisibility(!enableBuild || enableFlash ? View.GONE : View.VISIBLE);
            stopNow.setVisibility(enableStop ? View.VISIBLE : View.GONE);
            rebootNow.setVisibility(enableReboot ? View.VISIBLE : View.GONE);
            mFileFlashButton.setVisibility(disableCheckNow ? View.GONE : View.VISIBLE);
            mProgressEndSpace.setVisibility(enableStop ? View.VISIBLE : View.GONE);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(updateReceiver, updateFilter);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(updateReceiver);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleProgressBar();
        updateInfoVisibility();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Called for a new screen orientation
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            handleProgressBar();
        }
    }

    public void onButtonCheckNowClick(View v) {
        mPrefs.edit().putBoolean(SettingsActivity.PREF_START_HINT_SHOWN, true).commit();
        UpdateService.startCheck(this);
    }

    public void onButtonRebootNowClick(View v) {
        if (getPackageManager().checkPermission(UpdateService.PERMISSION_REBOOT,
                getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            Logger.d("[%s] required beyond this point", UpdateService.PERMISSION_REBOOT);
            return;
        }
        ((PowerManager) getSystemService(Context.POWER_SERVICE)).rebootCustom(null);
    }

    public void onButtonBuildNowClick(View v) {
        UpdateService.startBuild(this);
    }

    public void onButtonFlashNowClick(View v) {
        if (Config.isABDevice()) {
            flashStart.run();
        } else {
            flashRecoveryWarning.run();
        }
    }

    public void onButtonStopClick(View v) {
        stopDownload();
    }

    private final Runnable flashRecoveryWarning = new Runnable() {
        @Override
        public void run() {
            // Show a warning message about recoveries we support, depending
            // on the state of secure mode and if we've shown the message before

            final Runnable next = flashWarningFlashAfterUpdateZIPs;

            CharSequence message = null;
            if (!config.getSecureModeCurrent()
                    && !config.getShownRecoveryWarningNotSecure()) {
                message = Html
                        .fromHtml(getString(R.string.recovery_notice_description_not_secure),
                                Html.FROM_HTML_MODE_LEGACY);
                config.setShownRecoveryWarningNotSecure();
            } else if (config.getSecureModeCurrent()
                    && !config.getShownRecoveryWarningSecure()) {
                message = Html
                        .fromHtml(getString(R.string.recovery_notice_description_secure),
                                Html.FROM_HTML_MODE_LEGACY);
                config.setShownRecoveryWarningSecure();
            }

            if (message != null) {
                (new AlertDialog.Builder(MainActivity.this))
                        .setTitle(R.string.recovery_notice_title)
                        .setMessage(message)
                        .setCancelable(true)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok,
                                (dialog, which) -> next.run()).show();
            } else {
                next.run();
            }
        }
    };

    private final Runnable flashWarningFlashAfterUpdateZIPs = new Runnable() {
        @Override
        public void run() {
            // If we're in secure mode, but additional ZIPs to flash have been
            // detected, warn the user that these will not be flashed

            final Runnable next = flashStart;

            if (config.getSecureModeCurrent()
                    && (config.getFlashAfterUpdateZIPs().size() > 0)) {
                (new AlertDialog.Builder(MainActivity.this))
                        .setTitle(R.string.flash_after_update_notice_title)
                        .setMessage(
                                Html.fromHtml(getString(R.string.flash_after_update_notice_description),
                                        Html.FROM_HTML_MODE_LEGACY))
                        .setCancelable(true)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> next.run()).show();
            } else {
                next.run();
            }
        }
    };

    private final Runnable flashStart = () -> {
        checkNow.setEnabled(false);
        flashNow.setEnabled(false);
        buildNow.setEnabled(false);
        UpdateService.startFlash(MainActivity.this);
    };

    private void stopDownload() {
        mPrefs.edit()
                .putBoolean(
                        UpdateService.PREF_STOP_DOWNLOAD,
                        !mPrefs.getBoolean(UpdateService.PREF_STOP_DOWNLOAD,
                                false)).commit();
    }

    private void requestPermissions() {
        if (!Environment.isExternalStorageManager()) {
            // should never reach here if it's a system priv-app
            // this permission is granted by default
            // this block exists in case the user manually revoked that perm
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(uri);
            startActivityForResult(intent, PERMISSIONS_REQUEST_MANAGE_EXTERNAL_STORAGE);
        } else {
            mPermOk = true;
            UpdateService.start(this);
        }
    }

    private void handleProgressBar() {
        progress.setProgress(mProgressCurrent);
        progress.setMax(mProgressMax);
        progress.setVisibility(mProgressEnabled ? View.VISIBLE : View.INVISIBLE);
    }

    private void updateInfoVisibility() {
        boolean showInfo = config.getShowInfo();
        if (mInfoImage != null && mInfoText != null) {
            mInfoImage.setVisibility(showInfo ? View.VISIBLE : View.INVISIBLE);
            mInfoText.setVisibility(showInfo ? View.VISIBLE : View.INVISIBLE);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ACTIVITY_SELECT_FLASH_FILE && resultCode == Activity.RESULT_OK) {
            Uri uri = data.getData();
            Logger.d("Try flash file: %s", uri.getPath());
            String flashFilename = getPath(uri);
            if (flashFilename != null) {
                UpdateService.startFlashFile(this, flashFilename);
            } else {
                Intent i = new Intent(UpdateService.BROADCAST_INTENT);
                i.putExtra(UpdateService.EXTRA_STATE, UpdateService.STATE_ERROR_FLASH_FILE);
                sendStickyBroadcast(i);
            }
        } else if (requestCode == PERMISSIONS_REQUEST_MANAGE_EXTERNAL_STORAGE
                && resultCode == Activity.RESULT_OK) {
            mPermOk = Environment.isExternalStorageManager();
            UpdateService.start(this);
        }
    }

    private boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    private boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    private String getPath(Uri uri) {
        if (DocumentsContract.isDocumentUri(this, uri)) {
            final String docId = DocumentsContract.getDocumentId(uri);
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                Logger.d("isExternalStorageDocument: %s", uri.getPath());
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }
                if ("home".equalsIgnoreCase(type)) {
                    return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) + "/" + split[1];
                }
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {
                Logger.d("isDownloadsDocument: %s", uri.getPath());
                String fileName = getFileNameColumn(uri);
                if (fileName != null) {
                    return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + fileName;
                }
            }
        }
        return null;
    }

    private String getFileNameColumn(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            while (cursor.moveToNext()) {
                int index = cursor.getColumnIndexOrThrow("_display_name");
                return cursor.getString(index);
            }
        } catch (Exception e) {
            Logger.d("Failed to resolve file name", e);
        }
        return null;
    }

    public void onButtonSelectFileClick(View v) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);

        try {
            startActivityForResult(Intent.createChooser(intent,
                    getResources().getString(R.string.select_file_activity_title)),
                    ACTIVITY_SELECT_FLASH_FILE);
        } catch (android.content.ActivityNotFoundException ex) {
            Intent i = new Intent(UpdateService.BROADCAST_INTENT);
            i.putExtra(UpdateService.EXTRA_STATE, UpdateService.STATE_ERROR_FLASH_FILE);
            sendStickyBroadcast(i);
        }
    }

    private String getUpdateVersionString() {
        final String latestFull = mPrefs.getString(
                UpdateService.PREF_LATEST_FULL_NAME, null);
        final String latestDelta = mPrefs.getString(
                UpdateService.PREF_LATEST_DELTA_NAME, null);

        String latestDeltaZip = latestDelta != null ? new File(
                latestDelta).getName() : null;

        if (latestDeltaZip != null) {
            return latestDelta.substring(0,
                    latestDelta.lastIndexOf('.'));
        } else if (latestFull != null) {
            return latestFull.substring(0,
                    latestFull.lastIndexOf('.'));
        }
        return "";
    }
}
