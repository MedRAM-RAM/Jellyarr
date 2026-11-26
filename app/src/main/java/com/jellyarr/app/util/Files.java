package com.jellyarr.app.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** File helpers shared by the whole app (instead of a private copy per class). */
public final class Files {
    private Files() {}

    /** True when {@code f} itself is a symbolic link (pure java.io, works on every API level). */
    public static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile();
            File inCanonicalDir = parent == null ? f : new File(parent.getCanonicalFile(), f.getName());
            return !inCanonicalDir.getCanonicalFile().equals(inCanonicalDir.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Deletes a file or folder tree. A symbolic link is removed as a link and never followed, so a link that points
     * to shared storage can not make the delete walk into (and wipe) the target folder. Returns true when the path is
     * gone afterwards.
     */
    public static boolean deleteRecursively(File f) {
        if (f == null || !f.exists() && !isSymlink(f)) return true;
        try {
            f.setWritable(true, false);
        } catch (Throwable ignored) {
            // best effort
        }
        if (f.isDirectory() && !isSymlink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        return f.delete();
    }

    /** Copies {@code src} to {@code dst}, creating the parent folder. */
    public static void copy(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** The whole file as UTF-8 text with "\n" line ends, or null when it can not be read. */
    public static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Writes UTF-8 text; returns false on failure. */
    public static boolean writeText(File f, String content) {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
