package com.example.sync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Byte-level rules shared by the SAF importer and the offline contract tests. */
final class TransferFolderBytes {
    private static final String MARKER_PREFIX = "SoundsGood-Transfer-v1\n";
    static final int MARKER_BYTES = MARKER_PREFIX.length() + 64 + 1;

    private TransferFolderBytes() { }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        if (limit < 0) throw new IllegalArgumentException("Negative byte limit");
        ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        while (true) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, (long) limit - result.size() + 1));
            if (count < 0) return result.toByteArray();
            if (count == 0 || count > limit - result.size()) throw new IOException("Invalid or oversized input");
            result.write(buffer, 0, count);
        }
    }

    static boolean matchesManifest(byte[] marker, byte[] manifest) {
        String expected = MARKER_PREFIX + hex(sha256().digest(manifest)) + "\n";
        return Arrays.equals(marker, expected.getBytes(StandardCharsets.US_ASCII));
    }

    enum Failure { SOURCE_READ, DESTINATION_WRITE, EARLY_EOF, TOO_LONG, HASH_MISMATCH }

    static final class CopyFailure extends IOException {
        final Failure kind;
        final long received;
        CopyFailure(Failure kind, long received, IOException cause) {
            super(kind.name(), cause);
            this.kind = kind;
            this.received = received;
        }
    }

    interface Progress { void observed(long bytes); }

    static long copyVerified(InputStream input, OutputStream staging, long size, String expectedHash,
                             Runnable checkActive, Progress progress) throws CopyFailure {
        if (size < 0 || size > 2L * 1024 * 1024 * 1024) throw new IllegalArgumentException("Invalid audio size");
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        long received = 0;
        while (true) {
            checkActive.run();
            int count;
            try {
                // Probe one extra byte even when the expected size is zero.
                count = input.read(buffer, 0, (int) Math.min(buffer.length, size - received + 1));
            } catch (IOException error) {
                throw new CopyFailure(Failure.SOURCE_READ, received, error);
            }
            checkActive.run();
            if (count < 0) break;
            if (count == 0) throw new CopyFailure(Failure.SOURCE_READ, received, null);
            received += count;
            progress.observed(received);
            if (received > size) throw new CopyFailure(Failure.TOO_LONG, received, null);
            try {
                staging.write(buffer, 0, count);
            } catch (IOException error) {
                throw new CopyFailure(Failure.DESTINATION_WRITE, received, error);
            }
            digest.update(buffer, 0, count);
        }
        if (received != size) throw new CopyFailure(Failure.EARLY_EOF, received, null);
        if (!hex(digest.digest()).equals(expectedHash)) throw new CopyFailure(Failure.HASH_MISMATCH, received, null);
        return received;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static String hex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = digits[(bytes[i] & 0xff) >>> 4];
            chars[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(chars);
    }
}
