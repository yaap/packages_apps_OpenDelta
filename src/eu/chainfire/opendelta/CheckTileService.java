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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class CheckTileService extends TileService {

    private UpdateService mService;

    private boolean mChecking = false;
    private boolean mIsDoneCheck = false;
    private boolean mIsAvailable = false;
    private boolean mIsError = false;
    private boolean mIsCheckPending = false;

    private final UpdateService.CheckForUpdateListener mListener = new UpdateListener();
    private class UpdateListener implements UpdateService.CheckForUpdateListener {
        @Override
        public void onCheckDone(State state) {
            if (state == null || state.isErrorState()) {
                mIsAvailable = false;
                mIsError = true;
            } else if (state.isAvailableState()) {
                mIsAvailable = true;
                mIsError = false;
            }
            mChecking = false;
            mIsDoneCheck = true;
            refreshState();
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Logger.d("Service connected");
            UpdateService.LocalBinder binder = (UpdateService.LocalBinder) iBinder;
            mService = binder.getService();
            mService.addCheckForUpdateListener(mListener);
            if (mIsCheckPending) {
                mIsCheckPending = false;
                onClick();
                return;
            }
            refreshState();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Logger.d("Service disconnected");
            mService.removeCheckForUpdateListener(mListener);
            mService = null;
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        getQsTile().setSubtitle("");
        getQsTile().updateTile();
    }

    @Override
    public void onTileRemoved() {
        super.onTileRemoved();
    }

    @Override
    public synchronized void onStartListening() {
        super.onStartListening();
        refreshState();

        if (mService == null) {
            // bind the update service - calls refreshState() when connected
            Intent i = new Intent(this, UpdateService.class);
            startService(i);
            bindService(i, mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    public synchronized void onStopListening() {
        unbindService(mConnection);
        mChecking = false;
        mIsDoneCheck = false;
        mIsAvailable = false;
        mIsError = false;
        super.onStopListening();
    }

    @Override
    public synchronized void onClick() {
        if (mChecking) return;
        mChecking = true;
        mIsDoneCheck = false;
        mIsAvailable = false;
        mIsError = false;
        getQsTile().setState(Tile.STATE_ACTIVE);
        getQsTile().setSubtitle(getText(R.string.qs_check_checking));
        getQsTile().updateTile();
        if (mService == null) {
            // bind it now. check right after
            mIsCheckPending = true;
            Intent i = new Intent(this, UpdateService.class);
            startService(i);
            bindService(i, mConnection, Context.BIND_AUTO_CREATE);
            return;
        }
        if (!mService.onWantUpdateCheck(true)) {
            // no network connection - show as error
            // we won't receive a callback if reached here
            mIsError = true;
            mIsDoneCheck = true;
            mChecking = false;
            refreshState();
        }
    }

    private synchronized void refreshState() {
        final boolean isActive = mChecking || mIsDoneCheck;
        String subtitle = "";
        if (mIsAvailable) {
            subtitle = getString(R.string.state_action_available);
        } else if (mIsError) {
            subtitle = getString(R.string.qs_check_error);
        } else if (mIsDoneCheck) {
            subtitle = getString(R.string.qs_check_uptodate);
        }
        getQsTile().setState(isActive ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        getQsTile().setSubtitle(subtitle);
        getQsTile().updateTile();
    }
}
