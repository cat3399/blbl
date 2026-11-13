package blbl.cat3399.core.log

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CrashFileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun store(directory: File, version: String = "1.0", maxFiles: Int = 10) = CrashFileStore(
        directory, version, 1, "release", { _, e -> throw AssertionError(e) }, maxFiles = maxFiles,
    )

    @Test fun repeatedExportsKeepEventIdentityAndOriginalVersionAfterUpgrade() {
        val logs = temp.newFolder("logs")
        val original = store(logs).record("main", IllegalStateException("example"), 100)
        val upgraded = store(logs, "2.0")
        val first = upgraded.copyTo(temp.newFolder("first")).single().record!!
        val second = upgraded.copyTo(temp.newFolder("second")).single().record!!
        assertEquals(original.id, first.id)
        assertEquals(first.id, second.id)
        assertEquals("1.0", second.versionName)
        assertTrue(second.stacktrace.contains("IllegalStateException: example"))
    }

    @Test fun legacyCrashDoesNotInheritExportingVersion() {
        val logs = temp.newFolder("logs")
        File(logs, "last_crash.txt").writeText("crashAtMs=100\nthread=main\nthrowable=java.lang.RuntimeException: old\n\nold-stack\n")
        val latest = store(logs, "new-version").latest()!!
        assertNull(latest.id)
        assertNull(latest.versionName)
        assertNull(latest.versionCode)
        assertEquals("old-stack", latest.stacktrace)
    }

    @Test fun promptMarkerStillAppliesOnlyToTheMostRecentCrashTime() {
        val store = store(temp.newFolder("logs"))
        store.record("main", RuntimeException("one"), 100)
        assertFalse(store.wasPrompted(100))
        store.markPrompted(100)
        assertTrue(store.wasPrompted(100))
        store.record("main", RuntimeException("two"), 200)
        assertEquals(200, store.latest()!!.crashAtMs)
        assertFalse(store.wasPrompted(200))
    }

    @Test fun boundedCrashRetentionDoesNotRemoveRuntimeLogsOrSnapshots() {
        val logs = temp.newFolder("logs")
        File(logs, "runtime.log").writeText("keep")
        val store = store(logs, maxFiles = 2)
        store.record("main", RuntimeException("first"), 100)
        val first = store.copyTo(temp.newFolder("snapshot")).single().file
        repeat(5) { store.record("main", RuntimeException("later-$it"), 200L + it) }
        assertEquals(2, store.copyTo(temp.newFolder("latest")).size)
        assertEquals("keep", File(logs, "runtime.log").readText())
        assertTrue(first.readText().contains("RuntimeException: first"))
    }

    @Test fun consecutiveCrashesHaveDifferentIdsEvenAtTheSameTimestamp() {
        val store = store(temp.newFolder("logs"))
        val first = store.record("main", RuntimeException("first"), 100)
        val second = store.record("worker", RuntimeException("second"), 100)
        assertNotEquals(first.id, second.id)
        assertEquals(2, store.copyTo(temp.newFolder("export")).size)
    }

    @Test fun oversizedStackIsExplicitlyMarked() {
        val logs = temp.newFolder("logs")
        val store = CrashFileStore(logs, "1.0", 1, "debug", { _, e -> throw AssertionError(e) }, maxTotalBytes = 8192, maxFileBytes = 2048)
        store.record("main", RuntimeException("超长错误".repeat(5000)), 100)
        val snapshot = store.copyTo(temp.newFolder("export")).single()
        assertTrue(snapshot.record!!.truncated)
        assertTrue(snapshot.record.stacktrace.contains("crash stack truncated"))
        assertTrue(snapshot.file.length() <= 2048)
    }

    @Test fun invalidRecordIsIncludedWithParseFailureInsteadOfInventedMetadata() {
        val errors = mutableListOf<String>()
        val logs = temp.newFolder("logs")
        File(logs, "last_crash.txt").writeText("broken")
        val store = CrashFileStore(logs, "2.0", 2, "release", { message, _ -> errors += message })
        val entry = store.copyTo(temp.newFolder("export")).single()
        assertNull(entry.record)
        assertEquals("broken", entry.file.readText())
        assertTrue(errors.contains("invalid crash record"))
    }

    @Test fun failedWritePropagatesToCrashTrackerFallback() {
        val invalidDirectory = temp.newFile("not-a-directory")
        try {
            store(invalidDirectory).record("main", RuntimeException("failure"), 100)
            fail("expected write failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("crash directory"))
        }
    }
}
