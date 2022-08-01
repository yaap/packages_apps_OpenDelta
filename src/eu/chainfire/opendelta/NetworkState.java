/* 
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2014 The OmniROM Project
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
import android.net.ConnectivityManager;
import android.net.Network;

public class NetworkState {
    public interface OnNetworkStateListener {
        void onNetworkState(boolean state);
    }

    private Context context = null;
    private OnNetworkStateListener onNetworkStateListener = null;
    private volatile Boolean stateLast = null;
    private boolean mIsConnected;
    private boolean mIsMetered;
    private boolean mIsMeteredAllowed;

    private ConnectivityManager mConnectivityManager;
    private final ConnectivityManager.NetworkCallback mNetworkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            mIsConnected = true;
            updateState();
        }

        @Override
        public void onLost(Network network) {
            mIsConnected = false;
            updateState();
        }
    };

    private void updateState() {
        if (onNetworkStateListener != null) {
            mIsMetered = mConnectivityManager.isActiveNetworkMetered();
            boolean state = (!mIsMetered || mIsMeteredAllowed) && mIsConnected;

            if ((stateLast == null) || (stateLast != state)) {
                stateLast = state;
                onNetworkStateListener.onNetworkState(state);
            }
        }
    }

    public boolean start(Context context, OnNetworkStateListener onNetworkStateListener) {
        if (this.context == null) {
            this.context = context;
            this.onNetworkStateListener = onNetworkStateListener;
            mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            mIsConnected = mConnectivityManager.getActiveNetwork() != null;
            mConnectivityManager.registerDefaultNetworkCallback(mNetworkCallback);
            updateState();
            return true;
        }
        return false;
    }

    public boolean stop() {
        if (context != null) {
            onNetworkStateListener = null;
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mConnectivityManager = null;
            context = null;
            return true;
        }
        return false;
    }

    public Boolean getState() {
        if (stateLast == null)
            return false;
        return stateLast;
    }

    public void setMeteredAllowed(boolean meteredAllowed) {
        mIsMeteredAllowed = meteredAllowed;
    }

    public boolean isConnected() {
        return mIsConnected;
    }

    public boolean isMetered() {
        return mIsMetered;
    }
}
