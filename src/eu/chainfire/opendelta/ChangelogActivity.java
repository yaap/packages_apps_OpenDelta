/*
 * Copyright (C) 2024 Yet Another AOSP Project
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

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ChangelogActivity extends Activity {

    private LinearLayout mChangelogLayout;
    private TextView mLoadingText;
    private int mMarginPx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_changelog);

        mMarginPx = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics()));
        mChangelogLayout = findViewById(R.id.changelog_layout);
        mLoadingText = findViewById(R.id.loading_text);

        Config config = Config.getInstance(this);
        HandlerThread ht = new HandlerThread("ChangelogThread");
        ht.start();

        // fetch last 20 changelogs / until we reach current and display
        new Handler(ht.getLooper()).post(() -> {
            final Long currDate = Long.parseLong(
                    config.getFilenameBase().split("-")[4].substring(0, 8));
            // unformatted device.json URL 
            final String jsURL = config.getUrlBaseJson().replace(
                    config.getUrlBranchName(), "%s");
            // unformatted changelog.txt URL
            final String clURL = jsURL.replace(
                    config.getDevice() + ".json",
                    "Changelog.txt");
            boolean error = false;
            try {
                boolean reached = false;
                int reachedI = 0;
                final JSONArray jArr = new JSONArray(Download.asString(config.getUrlAPIHistory()));
                for (int i = 0; i < jArr.length() && (i < 20 || !reached); i++) {
                    try {
                        // figure out the title and date
                        final String currSha = jArr.getJSONObject(i).getString("sha");
                        final String otaJsonURL = String.format(Locale.ENGLISH, jsURL, currSha);
                        final JSONObject otaJson = new JSONObject(Download.asString(otaJsonURL));
                        final String filename = otaJson.getJSONArray("response")
                                .getJSONObject(0).getString("filename");
                        final Long fileDate = Long.parseLong(
                                filename.split("-")[4].substring(0, 8));
                        // fetch and add the changelog of that commit sha
                        final String changelogURL = String.format(Locale.ENGLISH, clURL, currSha);
                        final String currChangelog = Download.asString(changelogURL);
                        // we could be on a testing build with no matching changelog date
                        // count as reached and mark newest as current
                        if (!reached) {
                            reached = fileDate <= currDate;
                            reachedI = i;
                        }
                        final boolean isCurrent = fileDate == currDate || reached && i == reachedI;
                        addTitle(fileDate.toString(), isCurrent);
                        addText(currChangelog);
                    } catch (JSONException e) {
                        Logger.ex(e);
                        error = true;
                        break;
                    }
                }
            } catch (Exception e) {
                Logger.ex(e);
                error = true;
            } finally {
                final boolean isError = error;
                mChangelogLayout.post(() -> { handleLoadingText(isError); } );
                ht.quitSafely();
            }
        });
    }

    private void addTitle(String title, boolean current) {
        if (current) title +=  " (" + getString(R.string.current) + ")";
        title += ":";
        TextView view = new TextView(this);
        MarginLayoutParams params = new MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.topMargin = mMarginPx;
        params.bottomMargin = mMarginPx;
        view.setLayoutParams(params);
        view.setText(title);
        view.setTextAppearance(R.style.HeaderText);
        view.setTextSize(14);
        mChangelogLayout.post(() -> { addView(view); });
    }

    private void addText(String text) {
        TextView view = new TextView(this);
        view.setLayoutParams(new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        view.setText(text);
        view.setTextAppearance(R.style.ValueText);
        view.setTextSize(14);
        mChangelogLayout.post(() -> { addView(view); });
    }

    private synchronized void addView(View view) {
        mChangelogLayout.addView(view);
    }

    private synchronized void handleLoadingText(boolean isError) {
        if (isError) {
            mLoadingText.setText(R.string.qs_check_error);
            return;
        }
        mLoadingText.setVisibility(View.GONE);
    }
}
