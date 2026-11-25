package com.fishbowl.app.util;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

public class FilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void deleteRemovesTree() throws Exception {
        File d = tmp.newFolder("a");
        File f = new File(d, "b/c.txt");
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(1); }
        Assert.assertTrue(Files.deleteRecursively(d));
        Assert.assertFalse(d.exists());
    }

    @Test
    public void deleteDoesNotFollowSymlinks() throws Exception {
        File target = tmp.newFolder("target");
        File keep = new File(target, "keep.txt");
        try (FileOutputStream o = new FileOutputStream(keep)) { o.write(1); }
        File tree = tmp.newFolder("tree");
        File link = new File(tree, "link");
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (Exception e) {
            Assume.assumeNoException("symlinks not supported", e);
        }
        Assert.assertTrue(Files.isSymlink(link));
        Assert.assertTrue(Files.deleteRecursively(tree));
        Assert.assertFalse(tree.exists());
        Assert.assertTrue("target content must survive", keep.exists());
    }

    @Test
    public void copyAndReadText() throws Exception {
        File src = tmp.newFile("s.txt");
        Assert.assertTrue(Files.writeText(src, "hello\nworld"));
        File dst = new File(tmp.getRoot(), "x/y/d.txt");
        Files.copy(src, dst);
        Assert.assertEquals("hello\nworld", Files.readText(dst));
        Assert.assertNull(Files.readText(new File(tmp.getRoot(), "missing")));
    }

    @Test
    public void fmtBytes() {
        Assert.assertEquals("0 B", Fmt.bytes(0));
        Assert.assertEquals("512 B", Fmt.bytes(512));
        Assert.assertEquals("1.50 KB", Fmt.bytes(1536));
        Assert.assertEquals("150 MB", Fmt.bytes(150L * 1024 * 1024));
    }
}
