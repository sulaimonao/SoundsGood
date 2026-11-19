package com.example.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Relative managed filenames and local ExternalStorageProvider IDs. No absolute locators in receipts. */
final class DurableFolderNames {
    static final String MANAGED = "SoundsGood Library";
    private static final Pattern VOLUME = Pattern.compile("[a-fA-F0-9]+(?:-[a-fA-F0-9]+)+");
    private static final Pattern BLOB = Pattern.compile("^[^/\\\\\\p{Cntrl}]{1,48}--([a-f0-9]{64})-([a-f0-9]{12})\\.(mp3|m4a|aac|flac|ogg|opus|wav|aif|aiff)$");
    private static final Pattern RECORD = Pattern.compile("^([tp]-[a-f0-9]{64})-([0-9]{13})-([a-f0-9]{64})\\.json$");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private DurableFolderNames() {}

    static boolean allowedTreeId(String value) {
        if (value == null || value.length() > 4096) return false;
        int colon = value.indexOf(':');
        if (colon <= 0) return false;
        String volume = value.substring(0, colon);
        if (!volume.equals("primary") && !VOLUME.matcher(volume).matches()) return false;
        String[] parts = value.substring(colon + 1).split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.trim().isEmpty() || p.startsWith(".") || p.indexOf(':') >= 0 || p.indexOf('\\') >= 0 ||
                    (i < parts.length - 1 && p.equalsIgnoreCase(MANAGED))) return false;
            for (int n = 0; n < p.length(); n++) if (Character.isISOControl(p.charAt(n))) return false;
        }
        return !parts[0].equalsIgnoreCase("Android") &&
                !(parts.length == 1 && parts[0].equalsIgnoreCase("Download"));
    }

    static String sha256(byte[] bytes) {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        char[] chars = "0123456789abcdef".toCharArray();
        byte[] result = digest.digest(bytes);
        char[] hex = new char[result.length * 2];
        for (int i = 0; i < result.length; i++) {
            hex[i*2] = chars[(result[i] & 255) >>> 4];
            hex[i*2+1] = chars[result[i] & 15];
        }
        return new String(hex);
    }

    static String audioName(String title, String digest, String extension) {
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < title.length() && label.length() < 40; i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') label.append(c);
            else if (label.length() != 0 && label.charAt(label.length()-1) != ' ') label.append(' ');
        }
        String name = label.toString().trim();
        if (name.isEmpty()) name = "Track";
        String file = name + "--" + digest + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "." + extension;
        if (blobKey(file) == null) throw new IllegalArgumentException("Invalid audio identity");
        return file;
    }

    static String blobKey(String name) {
        Matcher m = BLOB.matcher(name);
        return m.matches() ? m.group(1) + "." + m.group(3) : null;
    }

    static String recordKey(boolean playlist, String libraryId, String id) {
        if (!ID.matcher(libraryId).matches() || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid identity");
        return (playlist ? "p-" : "t-") + sha256((libraryId + "\u0000" + id).getBytes(StandardCharsets.UTF_8));
    }

    static String recordName(String key, long generation, String digest) {
        String name = key + "-" + generation + "-" + digest + ".json";
        if (parseRecord(name) == null) throw new IllegalArgumentException("Invalid record identity");
        return name;
    }

    static final class Record {
        final String key, digest;
        final long generation;
        Record(String key, long generation, String digest) {
            this.key = key; this.generation = generation; this.digest = digest;
        }
    }

    static Record parseRecord(String name) {
        Matcher m = RECORD.matcher(name);
        return m.matches() ? new Record(m.group(1), Long.parseLong(m.group(2)), m.group(3)) : null;
    }
}
