/*
 * Copyright (C) 2022 Yet Another AOSP Project
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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

import eu.chainfire.opendelta.UpdateService.ProgressListener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

public class Download {
    private static final int HTTP_READ_TIMEOUT = 30000;
    private static final int HTTP_CONNECTION_TIMEOUT = 30000;
    private static final String DIGEST_ALGO = "SHA-256";

    public static final int STATUS_DOWNLOAD_STOP = 0;
    public static final int STATUS_DOWNLOAD_PAUSE = 1;
    public static final int STATUS_DOWNLOAD_RESUME = 2;

    public static final int ERROR_CODE_NEWEST_BUILD = 1;
    public static final int ERROR_CODE_NO_SUM_FILE = 2;
    public static final int ERROR_CODE_NO_CONNECTION = 3;
    public static final int ERROR_CODE_JSON_MALFORMED = 4;

    private final String mURL;
    private final File mFile;
    private final String mMatchSUM;
    private final UpdateService mUpdateService;
    private boolean mIsRunning = false;
    private int mStatus = -1;

    private final State mState;
    private final SharedPreferences mPrefs;

    public Download(String url, File file, String matchSUM, UpdateService us) {
        mURL = url;
        mFile = file;
        mMatchSUM = matchSUM;
        mUpdateService = us;
        mState = State.getInstance();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(us);
    }

    public String asString() {
        return asString(mURL);
    }

    public static String asString(String url) {
        Logger.d("download as string: %s", url);

        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null) return null;

            InputStream is = urlConnection.getInputStream();
            ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
            int byteInt;

            while((byteInt = is.read()) >= 0)
                byteArray.write(byteInt);

            byte[] bytes = byteArray.toByteArray();
            if (bytes == null) return null;

            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return null;
        } finally {
            if (urlConnection != null)
                urlConnection.disconnect();
        }
    }

    public long getSize() {
        return getSize(mURL);
    }

    public static long getSize(String url) {
        Logger.d("getSize: %s", url);

        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = setupHttpsRequest(url);
            if (urlConnection == null) return 0;

            return urlConnection.getContentLength();
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e);
            return 0;
        } finally {
            if (urlConnection != null)
                urlConnection.disconnect();
        }
    }

    public boolean start() {
        mStatus = -1;
        Logger.d("download: %s", mURL);

        HttpsURLConnection urlConnection = null;
        InputStream is = null;
        FileOutputStream os = null;
        MessageDigest digest = null;
        long len = 0;
        if (mMatchSUM != null) {
            try {
                digest = MessageDigest.getInstance(DIGEST_ALGO);
            } catch (NoSuchAlgorithmException e) {
                // No SHA-256 algorithm support
                Logger.ex(e);
            }
        }

        long lastTime = SystemClock.elapsedRealtime();
        long offset = 0;
        if (mFile.exists()) offset = mFile.length();

        try {
            final String userFN = mFile.getName().substring(0, mFile.getName().length() - 5);
            mState.update(State.ACTION_DOWNLOADING, 0f, 0L, 0L, userFN, null);
            urlConnection = setupHttpsRequest(mURL);
            if (urlConnection == null) return false;

            len = urlConnection.getContentLength();
            mPrefs.edit().putLong(UpdateService.PREF_DOWNLOAD_SIZE, len).apply();
            if (offset > 0 && offset < len) {
                urlConnection.disconnect();
                urlConnection = setupHttpsRequest(mURL, offset);
                if (urlConnection == null) return false;
                Logger.d("Resuming download at: " + offset);
            }

            mState.update(State.ACTION_DOWNLOADING, 0f, 0L, len, userFN, null);

            long freeSpace = (new StatFs(Config.getInstance(mUpdateService).getPathBase()))
                    .getAvailableBytes();
            if (freeSpace < len - offset) {
                mState.update(State.ERROR_DISK_SPACE, null, freeSpace, len, null,
                        null);
                Logger.d("not enough space!");
                return false;
            }

            if (offset > 0)
                lastTime -= mPrefs.getLong(UpdateService.PREF_LAST_DOWNLOAD_TIME, 0);
            final long[] last = new long[] { 0, len, 0, lastTime };
            ProgressListener progressListener = new ProgressListener() {
                @Override
                public void onProgress(float progress, long current, long total) {
                    current += last[0];
                    total = last[1];
                    progress = ((float) current / (float) total) * 100f;
                    long now = SystemClock.elapsedRealtime();
                    if (now >= last[2] + 250L) {
                        mState.update(State.ACTION_DOWNLOADING, progress,
                                current, total, userFN, now - last[3]);
                        mUpdateService.setDownloadNotificationProgress(progress, current,
                                total, now - last[3]);
                        last[2] = now;
                    }
                }

                public void setStatus(String s){
                    // do nothing
                }
            };

            long recv = offset;
            if ((len > 0) && (len < 4L * 1024L * 1024L * 1024L)) {
                mIsRunning = true;
                byte[] buffer = new byte[262144];

                is = urlConnection.getInputStream();
                os = new FileOutputStream(mFile, offset > 0);
                int r;
                while ((r = is.read(buffer)) > 0) {
                    if (mStatus >= 0) {
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

                String sumStr;
                if (offset > 0) {
                    final ProgressListener listener = mUpdateService.getSUMProgress(
                            State.ACTION_CHECKING_SUM, mFile.getName());
                    sumStr = UpdateService.getFileSHA256(mFile, listener);
                } else {
                    if (digest == null) return false;
                    sumStr = digestToHexString(digest);
                }
                boolean sumCheck = sumStr.equals(mMatchSUM);
                Logger.d("sumStr=" + sumStr + " matchSUM=" + mMatchSUM);
                if (!sumCheck) {
                    mIsRunning = false;
                    Logger.i("SUM check failed for " + mURL);
                    // if sum does not match when done, get rid
                    mFile.delete();
                    mState.update(State.ERROR_DOWNLOAD_SHA);
                }
                return sumCheck;
            }
            return false;
        } catch (Exception e) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            mIsRunning = false;
            Logger.ex(e);
            mPrefs.edit().putLong(UpdateService.PREF_LAST_DOWNLOAD_TIME,
                    SystemClock.elapsedRealtime() - lastTime).apply();
            if (urlConnection != null) urlConnection.disconnect();
            try { if (is != null) is.close(); } catch (IOException ignored) {}
            try { if (os != null) os.close(); } catch (IOException ignored) {}
            return false;
        } finally {
            mIsRunning = false;
            if (urlConnection != null) urlConnection.disconnect();
            try { if (is != null) is.close(); } catch (IOException ignored) {}
            try { if (os != null) os.close(); } catch (IOException ignored) {}
        }
    }

    public synchronized void stop() {
        mStatus = STATUS_DOWNLOAD_STOP;
        mIsRunning = false;
    }

    public synchronized void pause() {
        mStatus = STATUS_DOWNLOAD_PAUSE;
        mIsRunning = false;
    }

    public synchronized void resetState() {
        mStatus = -1;
        mIsRunning = false;
    }

    public synchronized boolean getIsRunning() {
        return mIsRunning;
    }

    public synchronized int getStatus() {
        return mStatus;
    }

    private static HttpsURLConnection setupHttpsRequest(String urlStr) {
        return setupHttpsRequest(urlStr, 0);
    }

    private static HttpsURLConnection setupHttpsRequest(String urlStr, long offset) {
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
            if (offset > 0 && code != HttpsURLConnection.HTTP_PARTIAL) {
                Logger.d("response: %d expected: %d", code,
                        HttpsURLConnection.HTTP_PARTIAL);
                return null;
            }
            if (offset == 0 && code != HttpsURLConnection.HTTP_OK) {
                Logger.d("response: %d expected: %d", code,
                        HttpsURLConnection.HTTP_OK);
                return null;
            }
            return urlConnection;
        } catch (Exception e) {
            Logger.i("Failed to connect to server");
            Logger.ex(e);
            return null;
        }
    }

    public static String digestToHexString(MessageDigest digest) {
        final BigInteger bi = new BigInteger(1, digest.digest());
        final StringBuilder sb = new StringBuilder(
                bi.toString(16).toLowerCase(Locale.ENGLISH));
        while (sb.length() < digest.getDigestLength() * 2)
            sb.insert(0, "0");
        return sb.toString();
    }
}
