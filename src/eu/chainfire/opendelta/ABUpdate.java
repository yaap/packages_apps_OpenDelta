/*
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.chainfire.opendelta;

import android.content.Context;
import android.content.res.Resources.NotFoundException;
import android.os.PowerManager.WakeLock;
import android.os.Handler;
import android.os.Looper;
import android.os.ServiceSpecificException;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;
import android.widget.Toast;

import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import eu.chainfire.opendelta.UpdateService.ProgressListener;

class ABUpdate {

    private static final String TAG = "ABUpdateInstaller";
    private static final String PAYLOAD_BIN_PATH = "payload.bin";
    private static final String PAYLOAD_PROPERTIES_PATH = "payload_properties.txt";
    private static final String PREFS_IS_INSTALLING_UPDATE = "prefs_is_installing_update";
    private static final String PREFS_IS_SUSPENDED = "prefs_is_suspended";
    private static final String FILE_PREFIX = "file://";
    private static final long WAKELOCK_TIMEOUT = 60 * 60 * 1000; /* 1 hour */

    // non UpdateEngine errors
    public static final int ERROR_NOT_FOUND = 99;
    private static final int ERROR_NOT_READY = 98;
    private static final int ERROR_CORRUPTED = 97;
    private static final int ERROR_INVALID = 96;

    private static ABUpdate mInstance;

    private final UpdateService mUpdateService;
    private final UpdateEngine mUpdateEngine;
    private final boolean mEnableABPerfMode;

    private String mZipPath;
    private ProgressListener mProgressListener;
    private boolean mBound;
    private boolean mIsStream;

    private final UpdateEngineCallback mUpdateEngineCallback = new UpdateEngineCallback() {
        @Override
        public void onStatusUpdate(int status, float percent) {
            Logger.d("onStatusUpdate = " + status + " " + percent + "%%");
            // downloading stage: 0% - 40%
            // when streaming: 0% - 60%
            int offset = 0;
            int weight = mIsStream ? 60 : 40;

            switch (status) {
                case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT:
                    setInstallingUpdate(false, mUpdateService);
                    mUpdateService.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.SUCCESS, -1);
                    return;
                case UpdateEngine.UpdateStatusConstants.REPORTING_ERROR_EVENT:
                    setInstallingUpdate(false, mUpdateService);
                    mUpdateService.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.ERROR, -1);
                    return;
                case UpdateEngine.UpdateStatusConstants.VERIFYING:
                    // verifying stage: 40% - 45%
                    // when streaming: 60% - 65%
                    offset = mIsStream ? 60 : 40;
                    weight = 5;
                    break;
                case UpdateEngine.UpdateStatusConstants.FINALIZING:
                    // finalizing stage: 45% - 100%
                    // when streaming: 65% - 100%
                    offset = mIsStream ? 65 : 45;
                    weight = mIsStream ? 35 : 55;
                    break;
            }

            if (mProgressListener != null) {
                try {
                    mProgressListener.setStatus(mUpdateService.getString(mUpdateService.getResources().getIdentifier(
                        "progress_status_" + status, "string", mUpdateService.getPackageName())));
                } catch (NotFoundException e) {
                    Logger.i("Couldn't find status string for status " + status);
                }
                mProgressListener.onProgress(percent * (float) weight + (float) offset,
                    (long) Math.round(percent * weight) + (long) offset, 100L);
            }
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            Logger.d("onPayloadApplicationComplete = " + errorCode);
            setInstallingUpdate(false, mUpdateService);
            if (errorCode == UpdateEngine.ErrorCodeConstants.UPDATED_BUT_NOT_ACTIVE) {
                mUpdateService.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.SUCCESS, errorCode);
            } else if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                mUpdateService.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.ERROR, errorCode);
            } else {
                mUpdateService.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.SUCCESS, -1);
            }
        }
    };

    public int start(String zipPath, ProgressListener listener) {
        try (ZipFile zipFile = new ZipFile(zipPath)) {
            if (!isABUpdate(zipFile)) return ERROR_INVALID;
        } catch (Exception ex) {
            Logger.ex(ex);
            return ERROR_INVALID;
        }
        return start(zipPath, null, 0, 0, listener);
    }

    public int start(String url, String[] headerKeyValuePairs,
                     long offset, long size, ProgressListener listener) {
        mIsStream = headerKeyValuePairs != null;
        mZipPath = url;
        mProgressListener = listener;
        if (isInstallingUpdate(mUpdateService)) {
            return -1;
        }
        final int installing = startUpdate(headerKeyValuePairs, offset, size);
        setInstallingUpdate(installing < 0, mUpdateService);
        return installing;
    }

    public void suspend() { // actually toggles suspend!
        if (!isInstallingUpdate(mUpdateService))
            return;
        if (!isSuspended(mUpdateService)) {
            mUpdateEngine.unbind();
            mBound = false;
            // the user can fast click suspend when an update was about to fail / be done
            // just early return in this case.
            try { mUpdateEngine.suspend(); } catch (Exception e) { return; }
            final WakeLock wakeLock = mUpdateService.getWakeLock();
            if (wakeLock.isHeld())
                wakeLock.release();
            setIsSuspended(true, mUpdateService);
            return;
        }
        mUpdateEngine.resume();
        resume();
    }

    public int resume() {
        final boolean installing = bindCallbacks();
        setInstallingUpdate(installing, mUpdateService);
        return installing ? -1 : ERROR_NOT_READY;
    }

    public void stop(boolean pendingReboot) {
        mUpdateEngine.unbind();
        mBound = false;
        if (pendingReboot) {
            mUpdateEngine.resetStatus();
        } else {
            mUpdateEngine.cancel();
        }
        setInstallingUpdate(false, mUpdateService);
    }

    public void pokeStatus() {
        if (mBound) {
            mUpdateEngine.unbind();
            mBound = false;
        }
        bindCallbacks();
    }

    static synchronized boolean isInstallingUpdate(UpdateService us) {
        return us.getPrefs()
                .getBoolean(PREFS_IS_INSTALLING_UPDATE, false);
    }

    static synchronized void setInstallingUpdate(boolean installing, UpdateService us) {
        final boolean enabled = us.getConfig().getABWakeLockCurrent();
        final WakeLock wakeLock = us.getWakeLock();
        if (installing && enabled && !wakeLock.isHeld())
            wakeLock.acquire(WAKELOCK_TIMEOUT);
        else if (wakeLock.isHeld())
            wakeLock.release();

        setIsSuspended(false, us);
        us.getPrefs().edit()
                .putBoolean(PREFS_IS_INSTALLING_UPDATE, installing).commit();
    }

    static synchronized boolean isSuspended(UpdateService us) {
        return us.getPrefs().getBoolean(PREFS_IS_SUSPENDED, false);
    }

    static synchronized void setIsSuspended(boolean suspended, UpdateService us) {
        us.getPrefs().edit().putBoolean(PREFS_IS_SUSPENDED, suspended).commit();
    }

    private ABUpdate(UpdateService service) {
        mUpdateService = service;
        mEnableABPerfMode = mUpdateService.getConfig().getABPerfModeCurrent();
        mUpdateEngine = new UpdateEngine();
    }

    public static ABUpdate getInstance(UpdateService service) {
        if (mInstance != null) {
            return mInstance;
        }
        mInstance = new ABUpdate(service);
        return mInstance;
    }

    private boolean bindCallbacks() {
        if (mBound) return true;
        mBound = mUpdateEngine.bind(mUpdateEngineCallback);
        if (!mBound) {
            Log.e(TAG, "Could not bind UpdateEngineCallback");
            return false;
        }
        return true;
    }

    private int startUpdate(String[] headerKeyValuePairs, long offset, long size) {
        Logger.d("startUpdate. mIsStream=" + mIsStream);
        File file = new File(mZipPath);
        if (!mIsStream) {
            if (!file.exists()) {
                Log.e(TAG, "The given update doesn't exist");
                return ERROR_NOT_FOUND;
            }
            try (ZipFile zipFile = new ZipFile(file)) {
                offset = getZipEntryOffset(zipFile, PAYLOAD_BIN_PATH);
                ZipEntry payloadPropEntry = zipFile.getEntry(PAYLOAD_PROPERTIES_PATH);
                try (InputStream is = zipFile.getInputStream(payloadPropEntry);
                    InputStreamReader isr = new InputStreamReader(is);
                    BufferedReader br = new BufferedReader(isr)) {
                    List<String> lines = new ArrayList<>();
                    for (String line; (line = br.readLine()) != null;) {
                        lines.add(line);
                    }
                    headerKeyValuePairs = new String[lines.size()];
                    headerKeyValuePairs = lines.toArray(headerKeyValuePairs);
                }
                Logger.d("payload offset=" + offset);
            } catch (IOException | IllegalArgumentException e) {
                Log.e(TAG, "Could not prepare " + file, e);
                return ERROR_CORRUPTED;
            }
        }

        try {
            mUpdateEngine.setPerformanceMode(mEnableABPerfMode);
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "Could not set performance mode, Earlier logs should point the reason. Trace:");
            e.printStackTrace();
            final Context context = mUpdateService.getApplicationContext();
            if (context != null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.ab_perf_mode_error),
                    Toast.LENGTH_LONG
                ).show();
            }
        }
        if (!bindCallbacks()) return ERROR_NOT_READY;
        String zipFileUri = mIsStream ? mZipPath : FILE_PREFIX + file.getAbsolutePath();

        Logger.d("Applying payload with params:");
        Logger.d("URI: " + zipFileUri);
        Logger.d("offset: " + offset);
        Logger.d("size: " + size);
        Logger.d("headerKeyValuePairs:");
        for (int i = 0; i < headerKeyValuePairs.length; i++)
            Logger.d(headerKeyValuePairs[i]);

        try {
            mUpdateEngine.applyPayload(zipFileUri, offset, size, headerKeyValuePairs);
        } catch (Exception e) {
            // if we're here it probably means an update is still processing...
            // Just poke status later
            new Handler(Looper.getMainLooper()).postDelayed(() -> pokeStatus(), 200);
            return ERROR_NOT_READY;
        }

        return -1;
    }

    private static boolean isABUpdate(ZipFile zipFile) {
        return zipFile.getEntry(PAYLOAD_BIN_PATH) != null &&
                zipFile.getEntry(PAYLOAD_PROPERTIES_PATH) != null;
    }

    /**
     * Get the offset to the compressed data of a file inside the given zip
     *
     * @param zipFile input zip file
     * @param entryPath full path of the entry
     * @return the offset of the compressed, or -1 if not found
     * @throws IOException for IO errors
     * @throws IllegalArgumentException if the given entry is not found
     */
    public static long getZipEntryOffset(ZipFile zipFile, String entryPath)
            throws IOException {
        // Each entry has an header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        final int FIXED_HEADER_SIZE = 30;
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        long offset = 0;
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = zipEntries.nextElement();
            int n = entry.getName().length();
            int m = entry.getExtra() == null ? 0 : entry.getExtra().length;
            int headerSize = FIXED_HEADER_SIZE + n + m;
            offset += headerSize;
            if (entry.getName().equals(entryPath)) {
                return offset;
            }
            offset += entry.getCompressedSize();
        }
        Log.e(TAG, "Entry " + entryPath + " not found");
        throw new IllegalArgumentException("The given entry was not found");
    }
}
