package dev.apkpatcher

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSpaceTest {
    @Test
    fun patchReserveCoversWorkingCopiesAndSigning() {
        assertEquals(4 * 100_000_000L + 64 * 1024 * 1024, StorageSpace.requiredPatchBytes(100_000_000L))
    }

    @Test
    fun knownDownloadIncludesWritingOverhead() {
        assertEquals(100_000_000L + 8 * 1024 * 1024, StorageSpace.requiredDownloadBytes(100_000_000L))
    }

    @Test
    fun unknownDownloadKeepsMinimumReserve() {
        assertEquals(64 * 1024 * 1024, StorageSpace.requiredDownloadBytes(-1))
    }
}
