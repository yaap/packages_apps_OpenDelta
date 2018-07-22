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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class DeltaInfo {
    public interface ProgressListener {
        public void onProgress(float progress, long current, long total);
        public void setStatus(String status);
    }

    public class FileSizeMD5 {
        private final long size;
        private final String MD5;

        public FileSizeMD5(JSONObject object, String suffix) throws JSONException {
            size = object.getLong("size" + (suffix != null ? "_" + suffix : ""));
            MD5 = object.getString("md5" + (suffix != null ? "_" + suffix : ""));
        }

        public long getSize() {
            return size;
        }

        public String getMD5() {
            return MD5;
        }
    }

    public class FileBase {
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

        public FileSizeMD5 match(File f, boolean checkMD5, ProgressListener progressListener) {
            return null;
        }
    }

    public class FileUpdate extends FileBase {
        private FileSizeMD5 update;
        private FileSizeMD5 applied;

        public FileUpdate(JSONObject object) throws JSONException {
            super(object);
            update = new FileSizeMD5(object, null);
            applied = new FileSizeMD5(object, "applied");
        }

        public FileSizeMD5 getUpdate() {
            return update;
        }

        public FileSizeMD5 getApplied() {
            return applied;
        }

        public FileSizeMD5 match(File f, boolean checkMD5, ProgressListener progressListener) {
            if (f.exists()) {
                if (f.length() == getUpdate().getSize())
                    if (!checkMD5 || getUpdate().getMD5().equals(getFileMD5(f, progressListener)))
                        return getUpdate();
                if (f.length() == getApplied().getSize())
                    if (!checkMD5 || getApplied().getMD5().equals(getFileMD5(f, progressListener)))
                        return getApplied();
            }
            return null;
        }
    }

    public class FileFull extends FileBase {
        private FileSizeMD5 official;
        private FileSizeMD5 store;
        private FileSizeMD5 storeSigned;

        public FileFull(JSONObject object) throws JSONException {
            super(object);
            official = new FileSizeMD5(object, "official");
            store = new FileSizeMD5(object, "store");
            storeSigned = new FileSizeMD5(object, "store_signed");
        }

        public FileSizeMD5 getOfficial() {
            return official;
        }

        public FileSizeMD5 getStore() {
            return store;
        }

        public FileSizeMD5 getStoreSigned() {
            return storeSigned;
        }

        public FileSizeMD5 match(File f, boolean checkMD5, ProgressListener progressListener) {
            if (f.exists()) {
                if (f.length() == getOfficial().getSize())
                    if (!checkMD5 || getOfficial().getMD5().equals(getFileMD5(f, progressListener)))
                        return getOfficial();
                if (f.length() == getStore().getSize())
                    if (!checkMD5 || getStore().getMD5().equals(getFileMD5(f, progressListener)))
                        return getStore();
                if (f.length() == getStoreSigned().getSize())
                    if (!checkMD5
                            || getStoreSigned().getMD5().equals(getFileMD5(f, progressListener)))
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
        JSONObject object = null;
        try {
            object = new JSONObject(new String(raw, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            // Doesn't happen, UTF-8 is guaranteed to be available on Android
        }
        
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

    private String getFileMD5(File file, ProgressListener progressListener) {
        String ret = null;

        long current = 0;
        long total = file.length();
        if (progressListener != null)
            progressListener.onProgress(getProgress(current, total), current, total);

        try {
            FileInputStream is = new FileInputStream(file);
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] buffer = new byte[256 * 1024];
                int r;

                while ((r = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, r);
                    current += (long) r;
                    if (progressListener != null)
                        progressListener.onProgress(getProgress(current, total), current, total);
                }

                String MD5 = new BigInteger(1, digest.digest()).
                        toString(16).toLowerCase(Locale.ENGLISH);
                while (MD5.length() < 32)
                    MD5 = "0" + MD5;
                ret = MD5;
            } finally {
                is.close();
            }
        } catch (NoSuchAlgorithmException e) {
            // No MD5 support (returns null)
            Logger.ex(e);
        } catch (FileNotFoundException e) {
            // The MD5 of a non-existing file is null
            Logger.ex(e);
        } catch (IOException e) {
            // Read or close error (returns null)
            Logger.ex(e);
        }

        if (progressListener != null)
            progressListener.onProgress(getProgress(total, total), total, total);

        return ret;
    }
}
