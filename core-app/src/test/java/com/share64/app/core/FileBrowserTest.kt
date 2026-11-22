package com.share64.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileBrowserTest {
    private lateinit var base: File
    private lateinit var internal: File
    private lateinit var usb: File

    @Before fun setUp() {
        base = Files.createTempDirectory("share64-browser").toFile().canonicalFile
        internal = File(base, "internal").apply { mkdirs() }
        usb = File(base, "usb").apply { mkdirs() }
        File(internal, "Movies/Trips").mkdirs()
        File(internal, "Download").mkdirs()
        File(internal, "zeta.txt").writeText("zz")
        File(internal, "Alpha.txt").writeText("a")
        File(internal, "beta.txt").writeText("bbbb")
        File(internal, ".hidden").writeText("h")
    }

    @After fun tearDown() {
        base.deleteRecursively()
    }

    private fun roots() = listOf(
        BrowserRoot("Internal storage", internal),
        BrowserRoot("USB drive", usb, removable = true),
        BrowserRoot("Downloads", File(internal, "Download"), shortcut = true),
    )

    @Test fun listsFoldersFirstThenFilesIgnoringCase() {
        val listing = FileBrowser.list(internal) as FolderListing.Ok
        assertEquals(listOf("Download", "Movies", "Alpha.txt", "beta.txt", "zeta.txt"), listing.entries.map { it.name })
        assertEquals(listOf(true, true, false, false, false), listing.entries.map { it.isDirectory })
    }

    @Test fun hidesDotFilesUnlessAsked() {
        val shown = FileBrowser.list(internal, showHidden = true) as FolderListing.Ok
        assertTrue(shown.entries.any { it.name == ".hidden" })
        val hidden = FileBrowser.list(internal) as FolderListing.Ok
        assertFalse(hidden.entries.any { it.name == ".hidden" })
    }

    @Test fun reportsFileSizesAndZeroForFolders() {
        val entries = (FileBrowser.list(internal) as FolderListing.Ok).entries.associateBy { it.name }
        assertEquals(4L, entries.getValue("beta.txt").size)
        assertEquals(0L, entries.getValue("Movies").size)
        assertTrue(entries.getValue("beta.txt").readable)
    }

    @Test fun missingFolderIsUnreadable() {
        assertEquals(FolderListing.Unreadable, FileBrowser.list(File(base, "nope")))
        assertEquals(FolderListing.Unreadable, FileBrowser.list(File(internal, "beta.txt")))
    }

    @Test fun emptyFolderIsOkAndEmpty() {
        val empty = File(internal, "Download")
        assertEquals(emptyList<BrowserEntry>(), (FileBrowser.list(empty) as FolderListing.Ok).entries)
    }

    @Test fun parentWalksUpAndStopsAtTheStorageRoot() {
        val trips = File(internal, "Movies/Trips")
        assertEquals(File(internal, "Movies"), FileBrowser.parent(trips, roots()))
        assertEquals(internal, FileBrowser.parent(File(internal, "Movies"), roots()))
        assertNull(FileBrowser.parent(internal, roots()))
        assertNull(FileBrowser.parent(usb, roots()))
    }

    @Test fun goingUpFromAShortcutContinuesThroughItsStorage() {
        val downloads = File(internal, "Download")
        assertEquals(internal, FileBrowser.parent(downloads, roots()))
    }

    @Test fun unknownLocationsHaveNoParent() {
        assertNull(FileBrowser.parent(File(base, "elsewhere"), roots()))
    }

    @Test fun labelsReadLikeBreadcrumbs() {
        assertEquals("Internal storage", FileBrowser.label(internal, roots()))
        assertEquals("Internal storage \u203A Movies \u203A Trips", FileBrowser.label(File(internal, "Movies/Trips"), roots()))
        assertEquals("USB drive", FileBrowser.label(usb, roots()))
        assertEquals("Internal storage \u203A Download", FileBrowser.label(File(internal, "Download"), roots()))
    }

    @Test fun isInsideDoesNotConfusePrefixSiblings() {
        assertTrue(FileBrowser.isInside(File("/a/b/c"), File("/a/b")))
        assertTrue(FileBrowser.isInside(File("/a/b"), File("/a/b")))
        assertFalse(FileBrowser.isInside(File("/a/bc"), File("/a/b")))
        assertFalse(FileBrowser.isInside(File("/a"), File("/a/b")))
    }
}
