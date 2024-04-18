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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class State {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        ACTION_NONE,
        ACTION_CHECKING,
        ACTION_CHECKING_SUM,
        ACTION_SEARCHING,
        ACTION_SEARCHING_SUM,
        ACTION_DOWNLOADING,
        ACTION_DOWNLOADING_PAUSED,
        ACTION_APPLYING,
        ACTION_APPLYING_PATCH,
        ACTION_APPLYING_SUM,
        ACTION_READY,
        ACTION_AB_FLASH,
        ACTION_AB_PAUSED,
        ACTION_AB_FINISHED,
        ACTION_AVAILABLE,
        ACTION_AVAILABLE_STREAM,
        ACTION_FLASH_FILE_NO_SUM,
        ACTION_FLASH_FILE_INVALID_SUM,
        ACTION_FLASH_FILE_READY,
        ERROR_DISK_SPACE,
        ERROR_UNKNOWN,
        ERROR_UNOFFICIAL,
        ERROR_DOWNLOAD,
        ERROR_DOWNLOAD_SHA,
        ERROR_DOWNLOAD_RESUME,
        ERROR_CONNECTION,
        ERROR_PERMISSIONS,
        ERROR_FLASH,
        ERROR_AB_FLASH,
        ERROR_FLASH_FILE
    })
    public @interface StateInt {}

    public static final int ACTION_NONE = 0;
    public static final int ACTION_CHECKING = 1;
    public static final int ACTION_CHECKING_SUM = 2;
    public static final int ACTION_SEARCHING = 3;
    public static final int ACTION_SEARCHING_SUM = 4;
    public static final int ACTION_DOWNLOADING = 5;
    public static final int ACTION_DOWNLOADING_PAUSED = 6;
    public static final int ACTION_APPLYING = 7;
    public static final int ACTION_APPLYING_PATCH = 8;
    public static final int ACTION_APPLYING_SUM = 9;
    public static final int ACTION_READY = 10;
    public static final int ACTION_AB_FLASH = 11;
    public static final int ACTION_AB_PAUSED = 12;
    public static final int ACTION_AB_FINISHED = 13;
    public static final int ACTION_AVAILABLE = 14;
    public static final int ACTION_AVAILABLE_STREAM = 15;
    public static final int ACTION_FLASH_FILE_NO_SUM = 16;
    public static final int ACTION_FLASH_FILE_INVALID_SUM = 17;
    public static final int ACTION_FLASH_FILE_READY = 18;
    public static final int ERROR_DISK_SPACE = 19;
    public static final int ERROR_UNKNOWN = 20;
    public static final int ERROR_UNOFFICIAL = 21;
    public static final int ERROR_DOWNLOAD = 22;
    public static final int ERROR_DOWNLOAD_SHA = 23;
    public static final int ERROR_DOWNLOAD_RESUME = 24;
    public static final int ERROR_CONNECTION = 25;
    public static final int ERROR_PERMISSIONS = 26;
    public static final int ERROR_FLASH = 27;
    public static final int ERROR_AB_FLASH = 28;
    public static final int ERROR_FLASH_FILE = 29;

    private static final HashMap<Integer, String> STATE_STRING_MAP;
    static {
        HashMap<Integer, String> tMap = new HashMap<>();
        tMap.put(ACTION_NONE, "action_none");
        tMap.put(ACTION_CHECKING, "action_checking");
        tMap.put(ACTION_CHECKING_SUM, "action_checking_sum");
        tMap.put(ACTION_SEARCHING, "action_searching");
        tMap.put(ACTION_SEARCHING_SUM, "action_searching_sum");
        tMap.put(ACTION_DOWNLOADING, "action_downloading");
        tMap.put(ACTION_DOWNLOADING_PAUSED, "action_downloading_paused");
        tMap.put(ACTION_APPLYING, "action_applying");
        tMap.put(ACTION_APPLYING_PATCH, "action_applying_patch");
        tMap.put(ACTION_APPLYING_SUM, "action_applying_sum");
        tMap.put(ACTION_READY, "action_ready");
        tMap.put(ACTION_AB_FLASH, "action_ab_flash");
        tMap.put(ACTION_AB_PAUSED, "action_ab_paused");
        tMap.put(ACTION_AB_FINISHED, "action_ab_finished");
        tMap.put(ACTION_AVAILABLE, "action_available");
        tMap.put(ACTION_AVAILABLE_STREAM, "action_available_stream");
        tMap.put(ACTION_FLASH_FILE_NO_SUM, "action_flash_file_no_sum");
        tMap.put(ACTION_FLASH_FILE_INVALID_SUM, "action_flash_file_invalid_sum");
        tMap.put(ACTION_FLASH_FILE_READY, "action_flash_file_ready");
        tMap.put(ERROR_DISK_SPACE, "error_disk_space");
        tMap.put(ERROR_UNKNOWN, "error_unknown");
        tMap.put(ERROR_UNOFFICIAL, "error_unofficial");
        tMap.put(ERROR_DOWNLOAD, "error_download");
        tMap.put(ERROR_DOWNLOAD_SHA, "error_download_sha");
        tMap.put(ERROR_DOWNLOAD_RESUME, "error_download_resume");
        tMap.put(ERROR_CONNECTION, "error_connection");
        tMap.put(ERROR_PERMISSIONS, "error_permissions");
        tMap.put(ERROR_FLASH, "error_flash");
        tMap.put(ERROR_AB_FLASH, "error_ab_flash");
        tMap.put(ERROR_FLASH_FILE, "error_flash_file");
        STATE_STRING_MAP = new HashMap<>(tMap);
    }

    private static final HashSet<Integer> mProgressStates = new HashSet<>(Arrays.asList(
        ACTION_DOWNLOADING,
        ACTION_SEARCHING,
        ACTION_SEARCHING_SUM,
        ACTION_CHECKING,
        ACTION_CHECKING_SUM,
        ACTION_APPLYING,
        ACTION_APPLYING_SUM,
        ACTION_APPLYING_PATCH,
        ACTION_AB_FLASH
    ));

    private static final HashSet<Integer> mErrorStates = new HashSet<>(Arrays.asList(
        ERROR_DOWNLOAD,
        ERROR_DOWNLOAD_SHA,
        ERROR_DOWNLOAD_RESUME,
        ERROR_DISK_SPACE,
        ERROR_UNKNOWN,
        ERROR_UNOFFICIAL,
        ERROR_CONNECTION,
        ERROR_AB_FLASH,
        ERROR_FLASH_FILE,
        ERROR_FLASH
    ));

    private static final HashSet<Integer> mAvailableStates = new HashSet<>(Arrays.asList(
        ACTION_READY,
        ACTION_AVAILABLE,
        ACTION_AVAILABLE_STREAM
    ));

    private static State mState;
    private @StateInt int mStateInt = ACTION_NONE;
    private Float mProgress = null;
    private Long mCurrent = null;
    private Long mTotal = null;
    private String mFilename = null;
    private Long mMs = null;
    private int mErrorCode = -1;
    private List<StateCallback> mStateCallbacks = new CopyOnWriteArrayList<>();

    private State() {}

    public interface StateCallback {
        void update(@StateInt int state, Float progress,
                Long current, Long total, String filename,
                Long ms, int errorCode);
    }

    public void addStateCallback(StateCallback callback) {
        mStateCallbacks.add(callback);
        callback.update(mStateInt, mProgress, mCurrent, mTotal, mFilename, mMs, mErrorCode);
    }

    public void removeStateCallback(StateCallback callback) {
        if (!mStateCallbacks.contains(callback)) return;
        mStateCallbacks.remove(callback);
    }

    public synchronized void notifyCallbacks() {
        if (mStateCallbacks.size() == 0) return;
        for (StateCallback callback : mStateCallbacks) {
            callback.update(mStateInt, mProgress, mCurrent,
                    mTotal, mFilename, mMs, mErrorCode);
        }
    }

    public static State getInstance() {
        if (mState == null) mState = new State();
        return mState;
    }

    public void update(@StateInt int state) {
        update(state, -1);
    }

    public void update(@StateInt int state, int errorCode) {
        update(state, null, null, null, null, null, errorCode);
    }

    public void update(@StateInt int state, Long ms) {
        update(state, null, ms);
    }

    public void update(@StateInt int state, String filename) {
        update(state, filename, null);
    }

    public void update(@StateInt int state, String filename, Long ms) {
        update(state, null, null, null, filename, ms);
    }

    public void update(@StateInt int state, String filename, int errorCode) {
        update(state, null, null, null, filename, null, errorCode);
    }

    public void update(@StateInt int state, Float progress,
            Long current, Long total, String filename, Long ms) {
        update(state, progress, current,  total,  filename,  ms, -1);
    }

    public synchronized void update(@StateInt int state, Float progress,
            Long current, Long total, String filename, Long ms, int errorCode) {
        mStateInt = state;
        mProgress = progress;
        mCurrent = current;
        mTotal = total;
        mFilename = filename;
        mMs = ms;
        mErrorCode = errorCode;
        notifyCallbacks();
    }

    @StateInt
    public synchronized int getState() {
        return mStateInt;
    }

    public synchronized boolean isProgressState() {
        return isProgressState(mStateInt);
    }

    public synchronized boolean isErrorState() {
        return isErrorState(mStateInt);
    }

    public synchronized boolean isAvailableState() {
        return isAvailableState(mStateInt);
    }

    public static boolean isProgressState(@StateInt int state) {
        return mProgressStates.contains(state);
    }

    public static boolean isErrorState(@StateInt int state) {
        return mErrorStates.contains(state);
    }

    public static boolean isAvailableState(@StateInt int state) {
        return mAvailableStates.contains(state);
    }

    public boolean equals(@StateInt int state) {
        return state == mStateInt;
    }

    @Override
    public String toString() {
        return getStateString(mStateInt);
    }

    public static String getStateString(@StateInt int state) {
        return STATE_STRING_MAP.get(state);
    }
}
