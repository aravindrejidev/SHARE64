package com.share64.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadLocationsTest {
    @Test fun internalStorage() {
        assertEquals("Internal storage", DownloadLocations.labelForDocumentId("primary:"))
        assertEquals("Internal storage/Download/SHARE64", DownloadLocations.labelForDocumentId("primary:Download/SHARE64"))
        assertEquals("Internal storage/Movies", DownloadLocations.labelForDocumentId("PRIMARY:Movies"))
    }

    @Test fun sdCardAndOtherVolumes() {
        assertEquals("SD card (1A2B-3C4D)", DownloadLocations.labelForDocumentId("1A2B-3C4D:"))
        assertEquals("SD card (1A2B-3C4D)/Backups/Phone", DownloadLocations.labelForDocumentId("1A2B-3C4D:Backups/Phone"))
    }

    @Test fun rawAndOddIds() {
        assertEquals("/storage/emulated/0/Download/x", DownloadLocations.labelForDocumentId("raw:/storage/emulated/0/Download/x"))
        assertEquals("msf-123", DownloadLocations.labelForDocumentId("msf-123"))
        assertEquals("Selected folder", DownloadLocations.labelForDocumentId(""))
    }

    @Test fun defaultLabel() {
        assertEquals("Downloads/SHARE64", DownloadLocations.DEFAULT_LABEL)
    }
}
