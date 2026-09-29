package com.meonggo.backend.photo.storage;

import java.util.UUID;

public final class PhotoStoragePaths {
    public static final String ROOT = "/data/user/images";
    public static final String STAGING = ROOT + "/.staging";

    private PhotoStoragePaths() {}

    public static boolean file(String path) {
        if (path == null || !path.endsWith(".jpg")) return false;
        int slash = path.lastIndexOf('/');
        return slash > 0
                && leafDirectory(path.substring(0, slash))
                && positive(path.substring(slash + 1, path.length() - 4));
    }

    public static boolean directory(String path) {
        return ROOT.equals(path) || STAGING.equals(path) || leafDirectory(path);
    }

    public static boolean leafDirectory(String path) {
        if (path == null) return false;
        if (path.startsWith(STAGING + "/")) {
            String value = path.substring(STAGING.length() + 1);
            try {
                return UUID.fromString(value).toString().equals(value);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return path.startsWith(ROOT + "/") && positive(path.substring(ROOT.length() + 1));
    }

    private static boolean positive(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
