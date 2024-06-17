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

import android.util.Log;

import java.util.Locale;

public class Logger {
    private final static String LOG_TAG = "OpenDelta";
    
    private static boolean log = true;

    public static void setDebugLogging(boolean enabled) {
        log = enabled;
    }

    public static void d(String message, Object... args) {
        if (!log) return;
        Log.d(LOG_TAG, String.format(Locale.ENGLISH, message, args));
    }

    public static void w(String message, Object... args) {
        Log.w(LOG_TAG, String.format(Locale.ENGLISH, message, args));
    }

    public static void ex(Exception e) {
        Log.e(LOG_TAG, "Exception trace:", e);
    }

    public static void i(String message, Object... args) {
        Log.i(LOG_TAG, String.format(Locale.ENGLISH, message, args));
    }
}
