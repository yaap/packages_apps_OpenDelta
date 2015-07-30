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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;

public class NetworkState {
    public interface OnNetworkStateListener {
        public void onNetworkState(boolean state);
    }

    public static final int ALLOW_UNKNOWN = 1;
    public static final int ALLOW_2G = 2;
    public static final int ALLOW_3G = 4;
    public static final int ALLOW_4G = 8;
    public static final int ALLOW_WIFI = 16;
    public static final int ALLOW_ETHERNET = 32;

    private Context context = null;
    private OnNetworkStateListener onNetworkStateListener = null;
    private ConnectivityManager connectivityManager = null;
    private volatile Boolean stateLast = null;
    private boolean connected;

    private int flags = ALLOW_WIFI | ALLOW_ETHERNET;

    private IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateState();
        }
    };

    private boolean haveFlag(int flag) {
        return ((flags & flag) == flag);
    }

    private void updateState() {
        if (onNetworkStateListener != null) {
            NetworkInfo info = connectivityManager.getActiveNetworkInfo();

            boolean state = false;
            connected = (info != null) && info.isConnected();
            if (connected) {
                // My definitions of 2G/3G/4G may not match yours... :)
                // Speed estimates courtesy (c) 2013 the internets
                switch (info.getType()) {
                    case ConnectivityManager.TYPE_MOBILE:
                        switch (info.getSubtype()) {
                            case TelephonyManager.NETWORK_TYPE_1xRTT:
                                // 2G ~ 50-100 kbps
                            case TelephonyManager.NETWORK_TYPE_CDMA:
                                // 2G ~ 14-64 kbps
                            case TelephonyManager.NETWORK_TYPE_EDGE:
                                // 2G ~ 50-100 kbps
                            case TelephonyManager.NETWORK_TYPE_GPRS:
                                // 2G ~ 100 kbps *
                            case TelephonyManager.NETWORK_TYPE_IDEN:
                                // 2G ~ 25 kbps
                                state = haveFlag(ALLOW_2G);
                                break;
                            case TelephonyManager.NETWORK_TYPE_EHRPD:
                                // 3G ~ 1-2 Mbps
                            case TelephonyManager.NETWORK_TYPE_EVDO_0:
                                // 3G ~ 400-1000 kbps
                            case TelephonyManager.NETWORK_TYPE_EVDO_A:
                                // 3G ~ 600-1400 kbps
                            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                                // 3G ~ 5 Mbps
                            case TelephonyManager.NETWORK_TYPE_HSDPA:
                                // 3G ~ 2-14 Mbps
                            case TelephonyManager.NETWORK_TYPE_HSPA:
                                // 3G ~ 700-1700 kbps
                            case TelephonyManager.NETWORK_TYPE_HSUPA:
                                // 3G ~ 1-23 Mbps *
                            case TelephonyManager.NETWORK_TYPE_UMTS:
                                // 3G ~ 400-7000 kbps
                                state = haveFlag(ALLOW_3G);
                                break;
                            case TelephonyManager.NETWORK_TYPE_HSPAP:
                                // 4G ~ 10-20 Mbps
                            case TelephonyManager.NETWORK_TYPE_LTE:
                                // 4G ~ 10+ Mbps
                                state = haveFlag(ALLOW_4G);
                                break;
                            case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                            default:
                                state = haveFlag(ALLOW_UNKNOWN);
                                break;
                        }
                        break;
                    case ConnectivityManager.TYPE_WIFI:
                        state = haveFlag(ALLOW_WIFI);
                        break;
                    case ConnectivityManager.TYPE_ETHERNET:
                        state = haveFlag(ALLOW_ETHERNET);
                        break;
                    case ConnectivityManager.TYPE_WIMAX:
                        // 4G
                        state = haveFlag(ALLOW_4G);
                        break;
                    default:
                        state = haveFlag(ALLOW_UNKNOWN);
                        break;
                }
            }

            if ((stateLast == null) || (stateLast != state)) {
                stateLast = state;
                onNetworkStateListener.onNetworkState(state);
            }
        }
    }

    public boolean start(Context context, OnNetworkStateListener onNetworkStateListener, int flags) {
        if (this.context == null) {
            this.context = context;
            this.onNetworkStateListener = onNetworkStateListener;
            updateFlags(flags);
            connectivityManager = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            context.registerReceiver(receiver, filter);
            updateState();
            return true;
        }
        return false;
    }

    public boolean stop() {
        if (context != null) {
            context.unregisterReceiver(receiver);
            onNetworkStateListener = null;
            connectivityManager = null;
            context = null;
            return true;
        }
        return false;
    }

    public Boolean getState() {
        if (stateLast == null)
            return false;
        return stateLast.booleanValue();
    }

    public void updateFlags(int newFlags) {
        flags = newFlags;
        Logger.d("networkstate flags --> %d", newFlags);
        if (connectivityManager != null)
            updateState();
    }

    public boolean isConnected() {
        return connected;
    }
}
