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
import android.os.PowerManager;

public class ScreenState {
    public interface OnScreenStateListener {
        public void onScreenState(boolean state);
    }

    private Context context = null;
    private OnScreenStateListener onScreenStateListener = null;
    private volatile Boolean stateLast = null;

    private IntentFilter filter = null;
    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateState(intent);
        }
    };

    public ScreenState() {
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
    }

    private void updateState(Intent intent) {
        if (onScreenStateListener != null) {
            Boolean state = null;
            if (intent != null) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()))
                    state = true;
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))
                    state = false;
            }
            if (state == null) {
                state = ((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                        .isInteractive();
            }

            if ((stateLast == null) || (stateLast != state)) {
                stateLast = state;
                onScreenStateListener.onScreenState(state);
            }
        }
    }

    public boolean start(Context context, OnScreenStateListener onScreenStateListener) {
        if (this.context == null) {
            this.context = context;
            this.onScreenStateListener = onScreenStateListener;
            context.registerReceiver(receiver, filter);
            updateState(null);
            return true;
        }
        return false;
    }

    public boolean stop() {
        if (context != null) {
            context.unregisterReceiver(receiver);
            onScreenStateListener = null;
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
}
