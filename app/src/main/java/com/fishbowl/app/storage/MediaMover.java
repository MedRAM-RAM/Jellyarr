package com.fishbowl.app.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moves the content of one media folder into another (merge), safely and resumably.
 *
 * <ul>
 *   <li>On the same volume each entry is renamed (instant, no extra space).</li>
 *   <li>Across volumes files are copied to a temporary name, the size is verified, the temp file is renamed into
 *       place and only then the source file is deleted. An interrupted move never loses a file.</li>
 *   <li>Folders are merged. A file that already exists at the destination with the same size is treated as already
 *       moved (the source copy is removed), so a cancelled move can simply be started again. A different file with
 *       the same name is kept as "name (2).ext".</li>
 * </ul>
 *
 * Plain java.io, no Android dependencies, so it is unit tested on the JVM.
 */
public final class MediaMover {

    public interface Listener {
        /** Called after every file; {@code current} is the file just handled (may be null). */
        void onProgress(long filesDone, long filesTotal, long bytesDone, long bytesTotal, String current);
    }

    public static final class Stats {
        public long files;
        public long bytes;
        public boolean truncated;
    }

    public static final class Result {
        public long filesMoved;
        public long bytesMoved;
        public long filesSkippedIdentical;
        public long filesRenamed;
        public boolean cancelled;
        public final List<String> errors = new ArrayList<>();

        public boolean ok() {
            return !cancelled && errors.isEmpty();
        }
    }

    private static final int BUF = 256 * 1024;
    private static final String TMP_SUFFIX = ".jellyarr-part";

    private MediaMover() {}

    /** Size and file count of a folder (canonical paths stop symlink loops). Stops counting after {@code maxFiles}. */
    public static Stats measure(File dir, long maxFiles) {
        Stats s = new Stats();
        if (dir != null && dir.isDirectory()) walk(dir, s, maxFiles, 0, new HashSet<String>());
        return s;
    }

    private static void walk(File dir, Stats s, long maxFiles, int depth, Set<String> seen) {
        if (depth > 16 || s.files >= maxFiles) {
            s.truncated = true;
            return;
        }
        try {
            if (!seen.add(dir.getCanonicalPath())) return;
        } catch (IOException e) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (s.files >= maxFiles) {
                s.truncated = true;
                return;
            }
            if (k.isDirectory()) {
                walk(k, s, maxFiles, depth + 1, seen);
            } else if (k.isFile()) {
                s.files++;
                s.bytes += k.length();
            }
        }
    }

    /** True when {@code child} is {@code parent} or lies inside it. */
    public static boolean isInside(File child, File parent) {
        try {
            String c = child.getCanonicalPath();
            String p = parent.getCanonicalPath();
            return c.equals(p) || c.startsWith(p.endsWith(File.separator) ? p : p + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Moves everything inside {@code src} into {@code dst} (created when missing). {@code src} itself stays, empty.
     * {@code cancel} is checked between files.
     */
    public static Result move(File src, File dst, Listener listener, AtomicBoolean cancel) {
        Result r = new Result();
        if (src == null || !src.isDirectory()) {
            r.errors.add("Source folder does not exist");
            return r;
        }
        if (dst == null) {
            r.errors.add("No destination");
            return r;
        }
        if (isInside(dst, src) || isInside(src, dst)) {
            r.errors.add("Source and destination overlap");
            return r;
        }
        if (!dst.isDirectory() && !dst.mkdirs()) {
            r.errors.add("Cannot create " + dst.getAbsolutePath());
            return r;
        }
        Stats total = measure(src, Long.MAX_VALUE);
        Progress p = new Progress(listener, total.files, total.bytes);
        moveChildren(src, dst, r, p, cancel == null ? new AtomicBoolean(false) : cancel);
        return r;
    }

    private static final class Progress {
        final Listener l;
        final long filesTotal;
        final long bytesTotal;
        long files;
        long bytes;

        Progress(Listener l, long filesTotal, long bytesTotal) {
            this.l = l;
            this.filesTotal = filesTotal;
            this.bytesTotal = bytesTotal;
        }

        void fileDone(long size, String name) {
            files++;
            bytes += size;
            if (l != null) l.onProgress(files, filesTotal, bytes, bytesTotal, name);
        }
    }

    private static void moveChildren(File srcDir, File dstDir, Result r, Progress p, AtomicBoolean cancel) {
        File[] kids = srcDir.listFiles();
        if (kids == null) {
            r.errors.add("Cannot read " + srcDir.getAbsolutePath());
            return;
        }
        for (File k : kids) {
            if (cancel.get()) {
                r.cancelled = true;
                return;
            }
            if (k.getName().endsWith(TMP_SUFFIX)) continue; // leftover of an interrupted copy
            File target = new File(dstDir, k.getName());
            if (k.isDirectory()) {
                if (!target.exists()) {
                    Stats st = measure(k, Long.MAX_VALUE);
                    if (k.renameTo(target)) {
                        // whole folder moved in one step (same volume)
                        r.filesMoved += st.files;
                        r.bytesMoved += st.bytes;
                        p.files += st.files - 1;
                        p.bytes += st.bytes;
                        p.fileDone(0, k.getName());
                        continue;
                    }
                    if (!target.mkdirs()) {
                        r.errors.add("Cannot create " + target.getAbsolutePath());
                        continue;
                    }
                } else if (!target.isDirectory()) {
                    target = uniqueName(dstDir, k.getName());
                    if (!target.mkdirs()) {
                        r.errors.add("Cannot create " + target.getAbsolutePath());
                        continue;
                    }
                    r.filesRenamed++;
                }
                moveChildren(k, target, r, p, cancel);
                if (r.cancelled) return;
                String[] left = k.list();
                if (left != null && left.length == 0) k.delete();
            } else if (k.isFile()) {
                moveFile(k, dstDir, target, r, p);
            }
        }
    }

    private static void moveFile(File f, File dstDir, File target, Result r, Progress p) {
        long size = f.length();
        try {
            if (target.exists()) {
                if (target.isFile() && target.length() == size) {
                    // already there (an earlier, interrupted run): drop the source copy
                    if (!f.delete()) r.errors.add("Cannot delete " + f.getAbsolutePath());
                    r.filesSkippedIdentical++;
                    p.fileDone(size, f.getName());
                    return;
                }
                target = uniqueName(dstDir, f.getName());
                r.filesRenamed++;
            }
            if (!f.renameTo(target)) {
                copyVerified(f, target);
                if (!f.delete()) r.errors.add("Copied but could not delete " + f.getAbsolutePath());
            }
            r.filesMoved++;
            r.bytesMoved += size;
        } catch (IOException e) {
            r.errors.add(f.getName() + ": " + e.getMessage());
        }
        p.fileDone(size, f.getName());
    }

    private static void copyVerified(File src, File dst) throws IOException {
        File tmp = new File(dst.getParentFile(), dst.getName() + TMP_SUFFIX);
        byte[] buf = new byte[BUF];
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (tmp.length() != src.length()) {
            tmp.delete();
            throw new IOException("size check failed");
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("cannot finish copy");
        }
        dst.setLastModified(src.lastModified());
    }

    /** "name (2).ext", "name (3).ext"... the first that does not exist. */
    static File uniqueName(File dir, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            File f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }
}
