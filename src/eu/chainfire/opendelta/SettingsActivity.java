/*
 *  Copyright (C) 2015 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package eu.chainfire.opendelta;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;
import android.view.MenuItem;

public class SettingsActivity extends Activity {

    public static final String PREF_AUTO_DOWNLOAD = "auto_download_actions";
    public static final String PREF_CHARGE_ONLY = "charge_only";
    public static final String PREF_BATTERY_LEVEL = "battery_level_string";
    public static final String PREF_SCREEN_STATE_OFF = "screen_state_off";
    public static final String PREF_START_HINT_SHOWN = "start_hint_shown";
    public static final String PREF_FILE_FLASH = "file_flash_support";

    public static final String PREF_SCHEDULER_MODE = "scheduler_mode";
    public static final String PREF_SCHEDULER_MODE_SMART = String.valueOf(0);
    public static final String PREF_SCHEDULER_MODE_DAILY = String.valueOf(1);
    public static final String PREF_SCHEDULER_MODE_WEEKLY = String.valueOf(2);

    public static final String PREF_SCHEDULER_DAILY_TIME = "scheduler_daily_time";
    public static final String PREF_SCHEDULER_WEEK_DAY = "scheduler_week_day";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        getFragmentManager().beginTransaction().replace(android.R.id.content, new SettingsFragment()).commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }
}
