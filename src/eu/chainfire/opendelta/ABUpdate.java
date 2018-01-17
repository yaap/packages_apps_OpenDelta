/*
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.chainfire.opendelta;

import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

class ABUpdate {

    private static final String TAG = "ABUpdateInstaller";

    private static boolean sIsInstallingUpdate;

    private static final String PAYLOAD_BIN_PATH = "payload.bin";
    private static final String PAYLOAD_PROPERTIES_PATH = "payload_properties.txt";

    private final String zipPath;

    private UpdaterListener mUpdateListener;

    private final UpdateEngineCallback mUpdateEngineCallback = new UpdateEngineCallback() {
        @Override
        public void onStatusUpdate(int status, float percent) {
            int progress = Math.round(percent * 100);
            mUpdateListener.progressUpdate(progress);
            mUpdateListener.notifyUpdateStatusChange(status);
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
           sIsInstallingUpdate = false;
           mUpdateListener.progressUpdate(100);
           mUpdateListener.notifyUpdateComplete(errorCode);
        }
    };

    static synchronized boolean start(String zipPath, UpdaterListener listener) {
        if (sIsInstallingUpdate) {
            return false;
        }
        ABUpdate installer = new ABUpdate(zipPath, listener);
        sIsInstallingUpdate = installer.startUpdate();
        return sIsInstallingUpdate;
    }

    static synchronized boolean isInstallingUpdate() {
        return sIsInstallingUpdate;
    }

    private ABUpdate(String zipPath, UpdaterListener listener) {
        this.zipPath = zipPath;
        this.mUpdateListener = listener;
    }

    private boolean startUpdate() {
        File file = new File(zipPath);
        if (!file.exists()) {
            Log.e(TAG, "The given update doesn't exist");
            return false;
        }

        long offset;
        String[] headerKeyValuePairs;
        try {
            ZipFile zipFile = new ZipFile(file);
            offset = getZipEntryOffset(zipFile, PAYLOAD_BIN_PATH);
            ZipEntry payloadPropEntry = zipFile.getEntry(PAYLOAD_PROPERTIES_PATH);
            try (InputStream is = zipFile.getInputStream(payloadPropEntry);
                 InputStreamReader isr = new InputStreamReader(is);
                 BufferedReader br = new BufferedReader(isr)) {
                List<String> lines = new ArrayList<>();
                for (String line; (line = br.readLine()) != null;) {
                    lines.add(line);
                }
                headerKeyValuePairs = new String[lines.size()];
                headerKeyValuePairs = lines.toArray(headerKeyValuePairs);
            }
            zipFile.close();
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "Could not prepare " + file, e);
            return false;
        }

        UpdateEngine updateEngine = new UpdateEngine();
        updateEngine.bind(mUpdateEngineCallback);
        String zipFileUri = "file://" + file.getAbsolutePath();
        updateEngine.applyPayload(zipFileUri, offset, 0, headerKeyValuePairs);

        return true;
    }

    static boolean isABUpdate(ZipFile zipFile) {
        return zipFile.getEntry(PAYLOAD_BIN_PATH) != null &&
                zipFile.getEntry(PAYLOAD_PROPERTIES_PATH) != null;
    }

    /**
     * Get the offset to the compressed data of a file inside the given zip
     *
     * @param zipFile input zip file
     * @param entryPath full path of the entry
     * @return the offset of the compressed, or -1 if not found
     * @throws IOException
     * @throws IllegalArgumentException if the given entry is not found
     */
    public static long getZipEntryOffset(ZipFile zipFile, String entryPath)
            throws IOException {
        // Each entry has an header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        final int FIXED_HEADER_SIZE = 30;
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        long offset = 0;
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = zipEntries.nextElement();
            int n = entry.getName().length();
            int m = entry.getExtra() == null ? 0 : entry.getExtra().length;
            int headerSize = FIXED_HEADER_SIZE + n + m;
            offset += headerSize;
            if (entry.getName().equals(entryPath)) {
                return offset;
            }
            offset += entry.getCompressedSize();
        }
        Log.e(TAG, "Entry " + entryPath + " not found");
        throw new IllegalArgumentException("The given entry was not found");
    }
}
