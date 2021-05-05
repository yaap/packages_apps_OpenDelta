/* 
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2015 The OmniROM Project
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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class DeltaInfo {
    public interface ProgressListener {
        void onProgress(float progress, long current, long total);
        void setStatus(String status);
    }

    public static class FileSizeSHA256 {
        private final long size;
        private final String SHA256;

        public FileSizeSHA256(JSONObject object, String suffix) throws JSONException {
            size = object.getLong("size" + (suffix != null ? "_" + suffix : ""));
            SHA256 = object.getString("sha256" + (suffix != null ? "_" + suffix : ""));
        }

        public long getSize() {
            return size;
        }

        public String getSHA256() {
            return SHA256;
        }
    }

    public static class FileBase {
        private final String name;
        private Object tag = null;

        public FileBase(JSONObject object) throws JSONException {
            name = object.getString("name");
        }

        public String getName() {
            return name;
        }

        public Object getTag() {
            return this.tag;
        }

        public void setTag(Object tag) {
            this.tag = tag;
        }

        public FileSizeSHA256 match(File f, boolean checkSUM, ProgressListener progressListener) {
            return null;
        }
    }

    public class FileUpdate extends FileBase {
        private final FileSizeSHA256 update;
        private final FileSizeSHA256 applied;

        public FileUpdate(JSONObject object) throws JSONException {
            super(object);
            update = new FileSizeSHA256(object, null);
            applied = new FileSizeSHA256(object, "applied");
        }

        public FileSizeSHA256 getUpdate() {
            return update;
        }

        public FileSizeSHA256 getApplied() {
            return applied;
        }

        public FileSizeSHA256 match(File f, boolean checkSUM, ProgressListener progressListener) {
            if (f.exists()) {
                if (f.length() == getUpdate().getSize())
                    if (!checkSUM || getUpdate().getSHA256().equals(getFileSHA256(f, progressListener)))
                        return getUpdate();
                if (f.length() == getApplied().getSize())
                    if (!checkSUM || getApplied().getSHA256().equals(getFileSHA256(f, progressListener)))
                        return getApplied();
            }
            return null;
        }
    }

    public class FileFull extends FileBase {
        private final FileSizeSHA256 official;
        private final FileSizeSHA256 store;
        private final FileSizeSHA256 storeSigned;

        public FileFull(JSONObject object) throws JSONException {
            super(object);
            official = new FileSizeSHA256(object, "official");
            store = new FileSizeSHA256(object, "store");
            storeSigned = new FileSizeSHA256(object, "store_signed");
        }

        public FileSizeSHA256 getOfficial() {
            return official;
        }

        public FileSizeSHA256 getStore() {
            return store;
        }

        public FileSizeSHA256 getStoreSigned() {
            return storeSigned;
        }

        public FileSizeSHA256 match(File f, boolean checkSUM, ProgressListener progressListener) {
            if (f.exists()) {
                if (f.length() == getOfficial().getSize())
                    if (!checkSUM || getOfficial().getSHA256().equals(getFileSHA256(f, progressListener)))
                        return getOfficial();
                if (f.length() == getStore().getSize())
                    if (!checkSUM || getStore().getSHA256().equals(getFileSHA256(f, progressListener)))
                        return getStore();
                if (f.length() == getStoreSigned().getSize())
                    if (!checkSUM
                            || getStoreSigned().getSHA256().equals(getFileSHA256(f, progressListener)))
                        return getStoreSigned();
            }
            return null;
        }

        public boolean isOfficialFile(File f) {
            if (f.exists()) {
                return f.length() == getOfficial().getSize();
            }
            return false;
        }

        public boolean isSignedFile(File f) {
            if (f.exists()) {
                return f.length() == getStoreSigned().getSize();
            }
            return false;
        }
    }

    private final int version;
    private final FileFull in;
    private final FileUpdate update;
    private final FileUpdate signature;
    private final FileFull out;
    private final boolean revoked;

    public DeltaInfo(byte[] raw, boolean revoked) throws JSONException,
            NullPointerException {        
        JSONObject object;
        object = new JSONObject(new String(raw, StandardCharsets.UTF_8));

        version = object.getInt("version");
        in = new FileFull(object.getJSONObject("in"));
        update = new FileUpdate(object.getJSONObject("update"));
        signature = new FileUpdate(object.getJSONObject("signature"));
        out = new FileFull(object.getJSONObject("out"));
        this.revoked = revoked;
    }

    public int getVersion() {
        return version;
    }

    public FileFull getIn() {
        return in;
    }

    public FileUpdate getUpdate() {
        return update;
    }

    public FileUpdate getSignature() {
        return signature;
    }

    public FileFull getOut() {
        return out;
    }

    public boolean isRevoked() {
        return revoked;
    }

    private float getProgress(long current, long total) {
        if (total == 0)
            return 0f;
        return ((float) current / (float) total) * 100f;
    }

    private String getFileSHA256(File file, ProgressListener progressListener) {
        String ret = null;

        long current = 0;
        long total = file.length();
        if (progressListener != null)
            progressListener.onProgress(getProgress(current, total), current, total);

        try {
            try (FileInputStream is = new FileInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA256");
                byte[] buffer = new byte[256 * 1024];
                int r;

                while ((r = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, r);
                    current += r;
                    if (progressListener != null)
                        progressListener.onProgress(getProgress(current, total), current, total);
                }

                StringBuilder SUM = new StringBuilder(new BigInteger(1, digest.digest()).
                        toString(16).toLowerCase(Locale.ENGLISH));
                while (SUM.length() < 32)
                    SUM.insert(0, "0");
                ret = SUM.toString();
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            // No SHA256 support (returns null)
            // The SHA256 of a non-existing file is null
            // Read or close error (returns null)
            Logger.ex(e);
        }

        if (progressListener != null)
            progressListener.onProgress(getProgress(total, total), total, total);

        return ret;
    }
}
