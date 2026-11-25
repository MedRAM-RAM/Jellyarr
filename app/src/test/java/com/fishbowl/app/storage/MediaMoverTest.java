package com.fishbowl.app.storage;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MediaMoverTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static File write(File f, int size) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(new byte[size]);
        }
        return f;
    }

    @Test
    public void movesTreeIntoEmptyDestination() throws Exception {
        File src = tmp.newFolder("src");
        File dst = new File(tmp.getRoot(), "dst");
        write(new File(src, "Movie A (2001)/Movie A (2001).mkv"), 100);
        write(new File(src, "Movie B (2002)/Movie B (2002).mp4"), 50);

        MediaMover.Result r = MediaMover.move(src, dst, null, null);

        Assert.assertTrue(r.errors.toString(), r.ok());
        Assert.assertEquals(2, r.filesMoved);
        Assert.assertEquals(150, r.bytesMoved);
        Assert.assertTrue(new File(dst, "Movie A (2001)/Movie A (2001).mkv").isFile());
        Assert.assertTrue(new File(dst, "Movie B (2002)/Movie B (2002).mp4").isFile());
        Assert.assertTrue(src.isDirectory());
        String[] left = src.list();
        Assert.assertEquals(0, left == null ? -1 : left.length);
    }

    @Test
    public void mergesFoldersSkipsIdenticalAndRenamesConflicts() throws Exception {
        File src = tmp.newFolder("src");
        File dst = tmp.newFolder("dst");
        write(new File(src, "Show/S01/e1.mkv"), 10);
        write(new File(src, "Show/S01/e2.mkv"), 20);
        write(new File(dst, "Show/S01/e1.mkv"), 10);   // same size: already moved
        write(new File(dst, "Show/S01/e2.mkv"), 99);   // different file with the same name

        MediaMover.Result r = MediaMover.move(src, dst, null, null);

        Assert.assertTrue(r.errors.toString(), r.ok());
        Assert.assertEquals(1, r.filesSkippedIdentical);
        Assert.assertEquals(1, r.filesRenamed);
        Assert.assertEquals(99, new File(dst, "Show/S01/e2.mkv").length());
        Assert.assertEquals(20, new File(dst, "Show/S01/e2 (2).mkv").length());
        Assert.assertFalse(new File(src, "Show").exists());
    }

    @Test
    public void refusesOverlappingFolders() throws Exception {
        File src = tmp.newFolder("lib");
        File inside = new File(src, "sub");
        MediaMover.Result r = MediaMover.move(src, inside, null, null);
        Assert.assertFalse(r.ok());
        r = MediaMover.move(inside.getParentFile(), src, null, null);
        Assert.assertFalse(r.ok());
    }

    @Test
    public void cancelStopsBeforeTheNextFile() throws Exception {
        File src = tmp.newFolder("src");
        File dst = tmp.newFolder("dst");
        write(new File(src, "a.mkv"), 1);
        write(new File(src, "b.mkv"), 1);
        AtomicBoolean cancel = new AtomicBoolean(true);

        MediaMover.Result r = MediaMover.move(src, dst, null, cancel);

        Assert.assertTrue(r.cancelled);
        Assert.assertEquals(0, r.filesMoved);
        Assert.assertTrue(new File(src, "a.mkv").exists());
    }

    @Test
    public void measureCountsFilesAndBytes() throws Exception {
        File d = tmp.newFolder("m");
        write(new File(d, "x/1.mkv"), 7);
        write(new File(d, "y/z/2.mkv"), 5);
        MediaMover.Stats s = MediaMover.measure(d, 1000);
        Assert.assertEquals(2, s.files);
        Assert.assertEquals(12, s.bytes);
        Assert.assertFalse(s.truncated);
    }

    @Test
    public void uniqueNameKeepsExtension() throws Exception {
        File d = tmp.newFolder("u");
        write(new File(d, "a.mkv"), 1);
        write(new File(d, "a (2).mkv"), 1);
        Assert.assertEquals("a (3).mkv", MediaMover.uniqueName(d, "a.mkv").getName());
    }
}
