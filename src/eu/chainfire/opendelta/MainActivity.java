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

import static android.Manifest.permission.POST_NOTIFICATIONS;

import android.app.Activity;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.UpdateEngine;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Html;
import android.text.format.Formatter;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.preference.PreferenceManager;

import eu.chainfire.opendelta.State.StateInt;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.HashSet;

public class MainActivity extends Activity {
    private static final int PERMISSIONS_REQUEST_MANAGE_EXTERNAL_STORAGE = 0;
    private static final int PERMISSIONS_REQUEST_NOTIFICATION = 1;
    private static final int ACTIVITY_SELECT_FLASH_FILE = 2;

    // states that flash button should be visible for
    private static final HashSet<Integer> FLASH_STATES = new HashSet<>(Arrays.asList(
        State.ACTION_READY,
        State.ACTION_FLASH_FILE_READY,
        State.ACTION_AVAILABLE_STREAM,
        State.ERROR_FLASH,
        State.ERROR_FLASH_FILE
    ));

    // states that download buttons (controls) should be visible for
    private static final HashSet<Integer> DOWNLOAD_STATES = new HashSet<>(Arrays.asList(
        State.ACTION_AB_PAUSED,
        State.ACTION_AB_FLASH,
        State.ACTION_DOWNLOADING,
        State.ACTION_DOWNLOADING_PAUSED,
        State.ERROR_DOWNLOAD_RESUME
    ));

    // states that resume button should be visible for
    private static final HashSet<Integer> RESUME_STATES = new HashSet<>(Arrays.asList(
        State.ACTION_AB_PAUSED,
        State.ACTION_DOWNLOADING_PAUSED,
        State.ERROR_DOWNLOAD_RESUME
    ));

    // states that check button should not be visible for
    private static final HashSet<Integer> NO_CHECK_STATES = new HashSet<>(Arrays.asList(
        State.ACTION_AB_PAUSED,
        State.ACTION_AB_FINISHED,
        State.ACTION_AB_FLASH,
        State.ACTION_DOWNLOADING,
        State.ACTION_DOWNLOADING_PAUSED,
        State.ERROR_DOWNLOAD_RESUME
    ));

    // states that changelog should be visible for
    private static final HashSet<Integer> CHANGELOG_STATES = new HashSet<>(Arrays.asList(
        State.ACTION_READY,
        State.ACTION_AB_FINISHED,
        State.ACTION_AVAILABLE,
        State.ACTION_AVAILABLE_STREAM
    ));

    // also states that changelog should not be visible for
    // (should be visible for all progress states but these)
    private static final HashSet<Integer> INTERMEDIATE_STATES = new HashSet<>(Arrays.asList(
        State.ACTION_SEARCHING,
        State.ACTION_CHECKING
    ));

    // states that update version text should be visible for
    // (apart from [progress - INTERMEDIATE_STATES])
    private static final HashSet<Integer> VERSION_STATES = new HashSet<>(Arrays.asList(
        State.ACTION_READY,
        State.ACTION_FLASH_FILE_NO_SUM,
        State.ACTION_FLASH_FILE_INVALID_SUM,
        State.ACTION_FLASH_FILE_READY,
        State.ACTION_AB_FINISHED,
        State.ACTION_AVAILABLE,
        State.ACTION_AVAILABLE_STREAM
    ));

    private UpdateService mUpdateService;
    private Config mConfig;
    private Handler mHandler;
    private @StateInt int mState;
    private TextView mTitle;
    private TextView mSub;
    private ProgressBar mProgress;
    private Button mCheckBtn;
    private Button mFlashBtn;
    private TextView mUpdateVersion;
    private Button mBuildBtn;
    private Button mStopBtn;
    private Button mPauseBtn;
    private Button mRebootBtn;
    private TextView mCurrentVersion;
    private TextView mLastChecked;
    private TextView mDownloadSizeHeader;
    private TextView mDownloadSize;
    private Space mDownloadSizeSpacer;
    private TextView mChangelogHeader;
    private TextView mChangelog;
    private Space mChangelogPlaceholder;
    private TextView mSub2;
    private Button mFileFlashButton;
    private SharedPreferences mPrefs;
    private TextView mUpdateVersionTitle;
    private TextView mExtraText;
    private TextView mProgressPercent;
    private int mProgressCurrent = 0;
    private int mProgressMax = 1;
    private boolean mPermOk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final Toolbar toolbar = findViewById(R.id.action_bar);
        setActionBar(toolbar);

        // Enable title and home button by default
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayShowTitleEnabled(false);
        }

        mHandler = new Handler(getMainLooper());
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        mTitle = findViewById(R.id.text_title);
        mSub = findViewById(R.id.progress_text);
        mSub2 = findViewById(R.id.progress_text2);
        mProgress = findViewById(R.id.progress_bar);
        mCheckBtn = findViewById(R.id.button_check_now);
        mFlashBtn = findViewById(R.id.button_flash_now);
        mRebootBtn = findViewById(R.id.button_reboot_now);
        mUpdateVersion = findViewById(R.id.text_update_version);
        mBuildBtn = findViewById(R.id.button_build_delta);
        mStopBtn = findViewById(R.id.button_stop);
        mPauseBtn = findViewById(R.id.button_pause);
        mCurrentVersion = findViewById(R.id.text_current_version);
        mLastChecked = findViewById(R.id.text_last_checked);
        mDownloadSize = findViewById(R.id.text_download_size);
        mDownloadSizeHeader = findViewById(R.id.text_download_size_header);
        mDownloadSizeSpacer = findViewById(R.id.download_size_spacer);
        mChangelog = findViewById(R.id.text_changelog);
        mChangelogHeader = findViewById(R.id.text_changelog_header);
        mChangelogPlaceholder = findViewById(R.id.changelog_placeholder);
        mProgressPercent = findViewById(R.id.progress_percent);
        mFileFlashButton = findViewById(R.id.button_select_file);
        mUpdateVersionTitle = findViewById(R.id.text_update_version_header);
        mExtraText = findViewById(R.id.extra_text);

        mChangelog.setMovementMethod(new ScrollingMovementMethod());

        mConfig = Config.getInstance(this);
        mPermOk = false;
        requestPermissions();

        // show info dialog once
        if (!mConfig.getInfoDisplayed()) {
            showInfo();
            mConfig.setInfoDisplayed();
        }

        if (mUpdateService == null) {
            startUpdateService(State.getStateString(State.ACTION_NONE));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    private void showInfo() {
        AlertDialog dialog = (new AlertDialog.Builder(this))
                .setTitle(R.string.menu_info)
                .setMessage(getString(R.string.text_info_section))
                .setNeutralButton(android.R.string.ok, null)
                .setCancelable(true).show();
        TextView textView = dialog
                .findViewById(android.R.id.message);
        if (textView != null)
            textView.setTypeface(mTitle.getTypeface());
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
            textView.setTypeface(mTitle.getTypeface());
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
                    Uri.parse(mConfig.getUrlBaseJson().replace(
                            mConfig.getDevice() + ".json", "Changelog.txt")));
            startActivity(changelogActivity);
            return true;
        }
        if (id == R.id.info) {
            showInfo();
            return true;
        }
        if (id == R.id.action_about) {
            showAbout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Logger.d("Service connected");
            UpdateService.LocalBinder binder = (UpdateService.LocalBinder) iBinder;
            mUpdateService = binder.getService();
            mUpdateService.getState().addStateCallback(updateReceiver);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Logger.d("Service disconnected");
            mUpdateService.getState().removeStateCallback(updateReceiver);
            mUpdateService = null;
        }
    };

    private void startUpdateService(String action) {
        Intent i = new Intent(this, UpdateService.class);
        i.setAction(action);
        if (mUpdateService == null) {
            startService(i);
            bindService(i, mConnection, Context.BIND_AUTO_CREATE);
            return;
        }
        mUpdateService.performAction(i);
    }

    private void startUpdateServiceFile(String flashFilename) {
        if (mUpdateService == null) {
            Intent i = new Intent(this, UpdateService.class);
            i.setAction(UpdateService.ACTION_FLASH_FILE);
            i.putExtra(UpdateService.EXTRA_FILENAME, flashFilename);
            startService(i);
            bindService(i, mConnection, Context.BIND_AUTO_CREATE);
            return;
        }
        if (flashFilename == null) return;
        mUpdateService.setFlashFilename(flashFilename);
    }

    private final State.StateCallback updateReceiver = new State.StateCallback() {
        private String formatLastChecked(long ms) {
            if (ms == 0) {
                return "";
            } else {
                SimpleDateFormat format = new SimpleDateFormat("EEEE, MMMM d, yyyy - HH:mm");
                return format.format(ms);
            }
        }

        @Override
        public void update(@StateInt int state, Float progress,
                           Long current, Long total, String filename,
                           Long ms, int errorCode) {
            mHandler.post(() -> {
                final boolean hintShown = mPrefs.getBoolean(
                        SettingsActivity.PREF_START_HINT_SHOWN, false);
                final boolean isFile = mPrefs.getBoolean(
                        UpdateService.PREF_FILE_FLASH, false);
                final boolean isProgress = State.isProgressState(state);
                final boolean isIntermediate = INTERMEDIATE_STATES.contains(state);
                final boolean isProgressOngoing = isProgress && !isIntermediate;
                final long lastCheckedSaved = mPrefs.getLong(
                        UpdateService.PREF_LAST_CHECK_TIME_NAME,
                        UpdateService.PREF_LAST_CHECK_TIME_DEFAULT);
                long localTotal = total != null ? total : 0L;
                long localCurrent = current != null ? current : 1L;
                long localMS = ms != null ? ms : 0L;

                String stateStr = State.getStateString(state);
                String title = getTitleForState(state, stateStr, hintShown);
                String sub = "";
                String sub2 = "";
                String progressPercent = "";
                String updateVersion = getVersionForState(state, filename, isProgressOngoing, isFile);
                String updateVersionTitle = getVersionTitleForState(state);
                String extraText = getExtraForState(state, localCurrent, localTotal, errorCode);
                String downloadSizeText = getSizeForState(state, isProgressOngoing);
                String lastCheckedText = lastCheckedSaved != UpdateService.PREF_LAST_CHECK_TIME_DEFAULT
                        ? formatLastChecked(lastCheckedSaved)
                        : getString(R.string.last_checked_never_title_new);
                boolean enableFlash = FLASH_STATES.contains(state);
                boolean enableBuild = state == State.ACTION_AVAILABLE;
                boolean enableDownload = DOWNLOAD_STATES.contains(state);
                boolean enableResume = RESUME_STATES.contains(state);
                boolean enableReboot = state == State.ACTION_AB_FINISHED;
                boolean hideCheck = NO_CHECK_STATES.contains(state);
                boolean disableDataSpeed = state == State.ACTION_AB_FLASH;
                boolean enableChangelog = !isFile && CHANGELOG_STATES.contains(state) ||
                        !isFile && isProgressOngoing;

                // don't spam for progress
                if (!isProgress || !State.isProgressState(mState)) {
                    Logger.d("onReceive state = " + State.getStateString(state));
                } else if (isProgress && state == mState) {
                    // same progress state as before.
                    // save a lot of time by only updating progress
                    final ProgressGenerator pgen = new ProgressGenerator(
                        localCurrent,
                        localTotal,
                        localMS,
                        progress,
                        disableDataSpeed,
                        filename
                    );
                    mSub.setText(pgen.sub);
                    mSub.setSelected(true); // allow scrolling
                    mSub2.setText(pgen.sub2);
                    mProgressPercent.setText(pgen.progressPercent);
                    mProgressCurrent = Math.round(pgen.localCurrent);
                    mProgressMax = Math.round(pgen.localTotal);
                    handleProgressBar();
                    return;
                }
                mState = state;

                if (state == State.ACTION_FLASH_FILE_NO_SUM ||
                        state == State.ACTION_FLASH_FILE_INVALID_SUM) {
                    // warn the user once
                    showLocalSumWarnDialog(state);
                } else if (state == State.ACTION_AB_FINISHED) {
                    mPrefs.edit().putString(UpdateService.PREF_READY_FILENAME_NAME, null).commit();
                    mPrefs.edit().putString(UpdateService.PREF_LATEST_FULL_NAME, null).commit();
                } else if (isProgressOngoing) {
                    // handle progress
                    final ProgressGenerator pgen = new ProgressGenerator(
                        localCurrent,
                        localTotal,
                        localMS,
                        progress,
                        disableDataSpeed,
                        filename
                    );
                    sub = pgen.sub;
                    sub2 = pgen.sub2;
                    progressPercent = pgen.progressPercent;
                    localCurrent = pgen.localCurrent;
                    localTotal = pgen.localTotal;
                }

                // update the views
                mTitle.setText(title);
                mSub.setText(sub);
                mSub.setSelected(true); // allow scrolling
                mSub2.setText(sub2);
                mProgress.setIndeterminate(isIntermediate);
                mProgressPercent.setText(progressPercent);
                final boolean hideVersion = TextUtils.isEmpty(updateVersion);
                if (!hideVersion) mUpdateVersion.setText(updateVersion);
                mUpdateVersion.setVisibility(hideVersion ? View.GONE : View.VISIBLE);
                mUpdateVersionTitle.setVisibility(hideVersion ? View.GONE : View.VISIBLE);
                final boolean setVersionTitle = !hideVersion && !TextUtils.isEmpty(updateVersionTitle);
                if (setVersionTitle) mUpdateVersionTitle.setText(updateVersionTitle);
                mCurrentVersion.setText(mConfig.getFilenameBase());
                mLastChecked.setText(lastCheckedText);
                mExtraText.setText(extraText);
                final boolean hideSize = TextUtils.isEmpty(downloadSizeText);
                if (!hideSize) mDownloadSize.setText(downloadSizeText);
                mDownloadSize.setVisibility(hideSize ? View.GONE : View.VISIBLE);
                mDownloadSizeHeader.setVisibility(hideSize ? View.GONE : View.VISIBLE);
                mDownloadSizeSpacer.setVisibility(hideSize ? View.GONE : View.VISIBLE);

                mProgressCurrent = Math.round(isIntermediate ? 1L : localCurrent);
                mProgressMax = Math.round(localTotal);
                handleProgressBar();

                mCheckBtn.setEnabled(mPermOk && !isProgress);
                mBuildBtn.setEnabled(mPermOk && enableBuild);
                mFlashBtn.setEnabled(mPermOk && enableFlash);
                mRebootBtn.setEnabled(enableReboot);
                mFileFlashButton.setEnabled(mPermOk && !isProgress);
                mCheckBtn.setVisibility(hideCheck ? View.GONE : View.VISIBLE);
                mFlashBtn.setVisibility(enableFlash ? View.VISIBLE : View.GONE);
                mBuildBtn.setVisibility(enableBuild ? View.VISIBLE : View.GONE);
                mRebootBtn.setVisibility(enableReboot ? View.VISIBLE : View.GONE);
                mFileFlashButton.setVisibility(hideCheck ? View.GONE : View.VISIBLE);

                // handle changelog
                if (enableChangelog) {
                    final String cl = mPrefs.getString(UpdateService.PREF_LATEST_CHANGELOG, null);
                    if (cl != null && !cl.isEmpty()) mChangelog.setText(cl);
                    else enableChangelog = false;
                }
                mChangelog.setVisibility(enableChangelog ? View.VISIBLE : View.GONE);
                mChangelogHeader.setVisibility(enableChangelog ? View.VISIBLE : View.GONE);
                mChangelogPlaceholder.setVisibility(enableChangelog ? View.GONE : View.VISIBLE);

                // download buttons
                final int vis = enableDownload ? View.VISIBLE : View.GONE;
                mStopBtn.setVisibility(enableReboot ? View.VISIBLE : vis);
                mPauseBtn.setVisibility(vis);
                mPauseBtn.setText(getString(enableResume ? R.string.button_resume_text
                        : R.string.button_pause_text));
            });
        }
    };

    private String getTitleForState(@StateInt int state, String stateStr, boolean hintShown) {
        switch (state) {
            case State.ACTION_NONE:
                if (!hintShown)
                    return getString(R.string.last_checked_never_title_new);
                break;
            case State.ERROR_DOWNLOAD_SHA:
            case State.ERROR_DOWNLOAD_RESUME:
                return getString(R.string.state_error_download);
            case State.ACTION_AVAILABLE:
            case State.ACTION_AVAILABLE_STREAM:
                return getString(R.string.state_action_build_full);
        }
        return tryGetResourceString("state_" + stateStr);
    }

    private String getVersionTitleForState(@StateInt int state) {
        switch (state) {
            case State.ACTION_READY:
                return getString(R.string.text_update_version_title);
            case State.ACTION_FLASH_FILE_READY:
            case State.ACTION_FLASH_FILE_NO_SUM:
            case State.ACTION_FLASH_FILE_INVALID_SUM:
                return getString(R.string.text_update_file_flash_title);
        }
        return "";
    }

    private String getVersionForState(@StateInt int state, String filename,
            boolean isOngoing, boolean isFile) {
        if (!VERSION_STATES.contains(state) && !isOngoing)
            return "";

        String flashImage = filename;
        if (state == State.ACTION_READY || state == State.ACTION_AB_FINISHED || isOngoing)
            flashImage = mPrefs.getString(UpdateService.PREF_READY_FILENAME_NAME, null);
        else if (state == State.ACTION_AVAILABLE || state == State.ACTION_AVAILABLE_STREAM)
            flashImage = mPrefs.getString(UpdateService.PREF_LATEST_FULL_NAME, null);
        if (flashImage == null)
            return "";

        String flashImageBase = new File(flashImage).getName();
        if (flashImageBase == null)
            return "";

        if (isFile)
            return flashImageBase;

        return flashImageBase.substring(0, flashImageBase.lastIndexOf('.'));
    }

    private String getExtraForState(@StateInt int state, long localCurrent,
            long localTotal, int errorCode) {
        switch (state) {
            case State.ERROR_DISK_SPACE:
                localCurrent /= 1024L * 1024L;
                localTotal /= 1024L * 1024L;
                return getString(R.string.error_disk_space_sub, localCurrent, localTotal);
            case State.ERROR_UNOFFICIAL:
                String[] versionParts = mConfig.getVersion().split("-");
                String versionType = "";
                try { versionType = versionParts[3]; } catch (Exception ignored) {}
                return getString(R.string.state_error_not_official_extra, versionType);
            case State.ERROR_DOWNLOAD:
                return tryGetResourceString("state_error_download_extra_" + errorCode);
            case State.ERROR_DOWNLOAD_SHA:
                return getString(R.string.state_error_download_extra_sha);
            case State.ERROR_AB_FLASH:
                return tryGetResourceString("error_ab_" + errorCode);
            case State.ERROR_DOWNLOAD_RESUME:
                return getString(R.string.state_error_download_extra_resume);
        }
        return "";
    }

    private String getSizeForState(@StateInt int state, boolean isOngoing) {
        if (!isOngoing &&
                state != State.ACTION_AVAILABLE &&
                state != State.ACTION_AVAILABLE_STREAM) {
            return "";
        }

        long downloadSize = mPrefs.getLong(UpdateService.PREF_DOWNLOAD_SIZE, -1);
        if (downloadSize == -1)
            return "";
        if (downloadSize == 0)
            return getString(R.string.text_download_size_unknown);
        return Formatter.formatFileSize(getApplicationContext(), downloadSize);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleProgressBar();
    }

    public void onButtonCheckNowClick(View v) {
        mPrefs.edit().putBoolean(SettingsActivity.PREF_START_HINT_SHOWN, true).commit();
        mPrefs.edit().putLong(Scheduler.PREF_LAST_CHECK_ATTEMPT_TIME_NAME,
                System.currentTimeMillis()).commit();
        startUpdateService(UpdateService.ACTION_CHECK);
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
        startUpdateService(UpdateService.ACTION_DOWNLOAD);
    }

    public void onButtonFlashNowClick(View v) {
        if (Config.isABDevice()) {
            if (mState == State.ACTION_AVAILABLE_STREAM) {
                streamStart.run();
                return;
            }
            flashStart.run();
            return;
        }
        flashRecoveryWarning.run();
    }

    public void onButtonStopClick(View v) {
        showAreYouSureDialog(() -> {
            startUpdateService(UpdateService.ACTION_DOWNLOAD_STOP);
        });
    }

    public void onButtonPauseClick(View v) {
        startUpdateService(UpdateService.ACTION_DOWNLOAD_PAUSE);
    }

    private void showAreYouSureDialog(Runnable run) {
        final String msg = getString(R.string.sure_dialog_msg);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(msg)
            .setPositiveButton(getString(R.string.button_yes_text),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        run.run();
                    }
                }
            )
            .setNegativeButton(getString(R.string.button_no_text), null);
        AlertDialog alert = builder.create();
        alert.show();
    }

    private void showLocalSumWarnDialog(int state) {
        final String msg = state == State.ACTION_FLASH_FILE_NO_SUM
                ? getString(R.string.no_sum_dialog_msg)
                : getString(R.string.invalid_sum_dialog_msg);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(msg)
            .setPositiveButton(getString(R.string.button_ignore_text),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        final String flashFilename = mPrefs.getString(
                                UpdateService.PREF_READY_FILENAME_NAME, null);
                        mUpdateService.setFlashFilename(flashFilename, true);
                    }
                }
            )
            .setNegativeButton(getString(R.string.button_stop_text),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        mPrefs.edit().putString(
                                UpdateService.PREF_READY_FILENAME_NAME, null).commit();
                    }
                }
            )
            .setOnCancelListener(
                new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) {
                        mPrefs.edit().putString(
                                UpdateService.PREF_READY_FILENAME_NAME, null).commit();
                    }
                }
            );
        AlertDialog alert = builder.create();
        alert.show();
    }

    private final Runnable flashRecoveryWarning = new Runnable() {
        @Override
        public void run() {
            // Show a warning message about recoveries we support, depending
            // on the state of secure mode and if we've shown the message before

            final Runnable next = flashStart;

            CharSequence message;
            if (mConfig.getUseTWRP()) {
                message = Html.fromHtml(
                        getString(R.string.recovery_notice_description_not_secure),
                        Html.FROM_HTML_MODE_LEGACY);
            } else {
                message = Html.fromHtml(
                        getString(R.string.recovery_notice_description_reboot),
                        Html.FROM_HTML_MODE_LEGACY);
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

    private final Runnable flashStart = () -> {
        mCheckBtn.setEnabled(false);
        mFlashBtn.setEnabled(false);
        mBuildBtn.setEnabled(false);
        startUpdateService(UpdateService.ACTION_FLASH);
    };

    private final Runnable streamStart = () -> {
        mCheckBtn.setEnabled(false);
        mFlashBtn.setEnabled(false);
        mBuildBtn.setEnabled(false);
        startUpdateService(UpdateService.ACTION_STREAM);
    };

    private void requestPermissions() {
        mPermOk = true;
        if (!Environment.isExternalStorageManager()) {
            mPermOk = false;
            // should never reach here if it's a system priv-app
            // this permission is granted by default
            // this block exists in case the user manually revoked that perm
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(uri);
            startActivityForResult(intent, PERMISSIONS_REQUEST_MANAGE_EXTERNAL_STORAGE);
        }
        
        // check for notification perm, but don't fail for it
        if (getApplicationContext().checkSelfPermission(POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // whatever you decide is cool.
            // I will, however, nudge you about it 
            // every time you re-create the activity.
            this.requestPermissions(new String[]{POST_NOTIFICATIONS},
                    PERMISSIONS_REQUEST_NOTIFICATION);
        }

        if (mPermOk) startUpdateService(null);
    }

    private void handleProgressBar() {
        final boolean enabled = State.isProgressState(mState);
        mProgress.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        if (!enabled) return;
        mProgress.setMax(mProgressMax);
        mProgress.setProgress(mProgressCurrent,
                mProgressCurrent > mProgress.getProgress());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ACTIVITY_SELECT_FLASH_FILE && resultCode == Activity.RESULT_OK) {
            Uri uri = data.getData();
            Logger.d("Try flash file: %s", uri.getPath());
            String flashFilename = getPath(uri);
            if (flashFilename != null) {
                startUpdateServiceFile(flashFilename);
            } else {
                Intent i = new Intent(UpdateService.BROADCAST_INTENT);
                i.putExtra(UpdateService.EXTRA_STATE, State.ERROR_FLASH_FILE);
                sendBroadcast(i);
            }
        } else if (requestCode == PERMISSIONS_REQUEST_MANAGE_EXTERNAL_STORAGE
                && resultCode == Activity.RESULT_OK) {
            mPermOk = Environment.isExternalStorageManager();
            startUpdateService(null);
        }
    }

    @Override
    public void onDestroy() {
        unbindService(mConnection);
        super.onDestroy();
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
            if (cursor.moveToNext()) {
                int index = cursor.getColumnIndexOrThrow("_display_name");
                return cursor.getString(index);
            }
        } catch (Exception e) {
            Logger.d("Failed to resolve file name", e);
        }
        return null;
    }

    private String tryGetResourceString(String str) {
        try {
            return getString(getResources().getIdentifier(
                    str, "string", getPackageName()));
        } catch (Exception e) {
            Logger.w("Couldn't find a string for " + str);
        }
        return "";
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
            i.putExtra(UpdateService.EXTRA_STATE, State.ERROR_FLASH_FILE);
            sendBroadcast(i);
        }
    }

    private class ProgressGenerator {
        public String sub;
        public String sub2;
        public String progressPercent;
        public long localCurrent;
        public long localTotal;

        public ProgressGenerator(
                long localCurrent,
                long localTotal,
                long localMS,
                Float progress,
                boolean disableDataSpeed,
                String filename
                ) {
            this.localCurrent = localCurrent;
            this.localTotal = localTotal;
            generate(localMS, progress, disableDataSpeed, filename);
        }

        private void generate(long localMS, Float progress,
                boolean disableDataSpeed, String filename) {
            // long --> int overflows FTL (progress.setXXX)
            boolean progressInK = false;
            if (localTotal > 1024L * 1024L * 1024L) {
                progressInK = true;
                localCurrent /= 1024L;
                localTotal /= 1024L;
            }

            if (filename != null) {
                sub = filename;
                progressPercent = String.format(Locale.ENGLISH, "%.0f %%", progress);
                if (localMS < 500 || localCurrent < 0 || localTotal < 0) return;
                float kibps = ((float) localCurrent / 1024f) / ((float) localMS / 1000f);
                if (progressInK) kibps *= 1024f;
                int sec = (int) (((((float) localTotal / (float) localCurrent) *
                        (float) localMS) - localMS) / 1000f);
                String timeStr = Character.toString('\u221E'); // infinity symbol unicode
                if (sec < 3600) { // consider anything >= 60m infinity
                    timeStr = String.format(Locale.ENGLISH,
                            "%02d:%02d",
                            sec / 60, sec % 60);
                }
                if (disableDataSpeed) {
                    sub2 = timeStr;
                    return;
                }
                if (kibps < 1024) {
                    sub2 = String.format(Locale.ENGLISH,
                            "%.0f KiB/s, %s",
                            kibps, timeStr);
                    return;
                }
                sub2 = String.format(Locale.ENGLISH,
                        "%.0f MiB/s, %s",
                        kibps / 1024f, timeStr);
            }
        }
    }
}
