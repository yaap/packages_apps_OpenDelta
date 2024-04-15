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

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Set;

public class State {
    public static final String ACTION_NONE = "action_none";
    public static final String ACTION_CHECKING = "action_checking";
    public static final String ACTION_CHECKING_SUM = "action_checking_sum";
    public static final String ACTION_SEARCHING = "action_searching";
    public static final String ACTION_SEARCHING_SUM = "action_searching_sum";
    public static final String ACTION_DOWNLOADING = "action_downloading";
    public static final String ACTION_DOWNLOADING_PAUSED = "action_downloading_paused";
    public static final String ACTION_APPLYING = "action_applying";
    public static final String ACTION_APPLYING_PATCH = "action_applying_patch";
    public static final String ACTION_APPLYING_SUM = "action_applying_sum";
    public static final String ACTION_READY = "action_ready";
    public static final String ACTION_AB_FLASH = "action_ab_flash";
    public static final String ACTION_AB_PAUSED = "action_ab_paused";
    public static final String ACTION_AB_FINISHED = "action_ab_finished";
    public static final String ACTION_AVAILABLE = "action_available";
    public static final String ACTION_AVAILABLE_STREAM = "action_available_stream";
    public static final String ACTION_FLASH_FILE_NO_SUM = "action_flash_file_no_sum";
    public static final String ACTION_FLASH_FILE_INVALID_SUM = "action_flash_file_invalid_sum";
    public static final String ACTION_FLASH_FILE_READY = "action_flash_file_ready";
    public static final String ERROR_DISK_SPACE = "error_disk_space";
    public static final String ERROR_UNKNOWN = "error_unknown";
    public static final String ERROR_UNOFFICIAL = "error_unofficial";
    public static final String ERROR_DOWNLOAD = "error_download";
    public static final String ERROR_DOWNLOAD_SHA = "error_download_sha";
    public static final String ERROR_DOWNLOAD_RESUME = "error_download_resume";
    public static final String ERROR_CONNECTION = "error_connection";
    public static final String ERROR_PERMISSIONS = "error_permissions";
    public static final String ERROR_FLASH = "error_flash";
    public static final String ERROR_AB_FLASH = "error_ab_flash";
    public static final String ERROR_FLASH_FILE = "error_flash_file";

    private static final Set<String> mProgressStates = Set.of(
        ACTION_DOWNLOADING,
        ACTION_SEARCHING,
        ACTION_SEARCHING_SUM,
        ACTION_CHECKING,
        ACTION_CHECKING_SUM,
        ACTION_APPLYING,
        ACTION_APPLYING_SUM,
        ACTION_APPLYING_PATCH,
        ACTION_AB_FLASH
    );

    private static final Set<String> mErrorStates = Set.of(
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
    );

    private static final Set<String> mAvailableStates = Set.of(
        ACTION_AVAILABLE,
        ACTION_AVAILABLE_STREAM
    );

    private static State mState;
    private String mStateStr = ACTION_NONE;
    private Float mProgress = null;
    private Long mCurrent = null;
    private Long mTotal = null;
    private String mFilename = null;
    private Long mMs = null;
    private int mErrorCode = -1;
    private List<StateCallback> mStateCallbacks = new CopyOnWriteArrayList<>();

    private State() {}

    public interface StateCallback {
        void update(String state, Float progress,
                Long current, Long total, String filename,
                Long ms, int errorCode);
    }

    public void addStateCallback(StateCallback callback) {
        mStateCallbacks.add(callback);
        callback.update(mStateStr, mProgress, mCurrent, mTotal, mFilename, mMs, mErrorCode);
    }

    public void removeStateCallback(StateCallback callback) {
        if (!mStateCallbacks.contains(callback)) return;
        mStateCallbacks.remove(callback);
    }

    public synchronized void notifyCallbacks() {
        if (mStateCallbacks.size() == 0) return;
        for (StateCallback callback : mStateCallbacks) {
            callback.update(mStateStr, mProgress, mCurrent,
                    mTotal, mFilename, mMs, mErrorCode);
        }
    }

    public static State getInstance() {
        if (mState == null) mState = new State();
        return mState;
    }

    public void update(String state) {
        update(state, -1);
    }

    public void update(String state, int errorCode) {
        update(state, null, null, null, null, null, errorCode);
    }

    public void update(String state, Long ms) {
        update(state, null, ms);
    }

    public void update(String state, String filename) {
        update(state, filename, null);
    }

    public void update(String state, String filename, Long ms) {
        update(state, null, null, null, filename, ms);
    }

    public void update(String state, String filename, int errorCode) {
        update(state, null, null, null, filename, null, errorCode);
    }

    public void update(String state, Float progress,
            Long current, Long total, String filename, Long ms) {
        update(state, progress, current,  total,  filename,  ms, -1);
    }

    public synchronized void update(String state, Float progress,
            Long current, Long total, String filename, Long ms, int errorCode) {
        mStateStr = state;
        mProgress = progress;
        mCurrent = current;
        mTotal = total;
        mFilename = filename;
        mMs = ms;
        mErrorCode = errorCode;
        notifyCallbacks();
    }

    public synchronized String getState() {
        return mStateStr;
    }

    public synchronized boolean isProgressState() {
        return isProgressState(mStateStr);
    }

    public synchronized boolean isErrorState() {
        return isErrorState(mStateStr);
    }

    public synchronized boolean isAvailableState() {
        return isAvailableState(mStateStr);
    }

    public static boolean isProgressState(String state) {
        return mProgressStates.contains(state);
    }

    public static boolean isErrorState(String state) {
        return mErrorStates.contains(state);
    }

    public static boolean isAvailableState(String state) {
        return mAvailableStates.contains(state);
    }

    public boolean equals(String state) {
        return state.equals(mStateStr);
    }

    @Override
    public String toString() {
        return mStateStr;
    }
}
