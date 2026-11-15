package blbl.cat3399.core.log

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class FileLogWriterTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rotatesWithinBudgetAndKeepsWritingLatestEvents() = runTest {
        val errors = mutableListOf<String>()
        val logs = temp.newFolder("logs")
        val writer = FileLogWriter(logs, "session", backgroundScope, { message, _ -> errors += message }, maxFileBytes = 512, maxFiles = 3, maxTotalBytes = 1200)
        repeat(40) { writer.append('I', "test", "event-$it " + "x".repeat(65)) }
        writer.snapshot(temp.root).use { snapshot ->
            assertTrue(snapshot.files.size <= 3)
            assertTrue(snapshot.files.sumOf { it.length() } <= 1200)
            val text = snapshot.files.joinToString("\n") { it.readText() }
            assertTrue(text.contains("event-39 "))
            assertFalse(text.contains("event-0 "))
            assertTrue(snapshot.health.active)
            assertEquals(0, snapshot.health.droppedLines)
        }
        writer.close()
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun immediateExportIncludesQueuedLinesAndSnapshotSurvivesLaterRotation() = runTest {
        val writer = FileLogWriter(temp.newFolder("logs"), "session", backgroundScope, { _, e -> throw AssertionError(e) }, maxFileBytes = 512, maxFiles = 2, maxTotalBytes = 1024)
        writer.append('E', "test", "failure-before-export")
        val first = writer.snapshot(temp.root)
        assertTrue(first.files.joinToString { it.readText() }.contains("failure-before-export"))
        repeat(30) { writer.append('I', "test", "later-$it " + "x".repeat(60)) }
        writer.snapshot(temp.root).use { second ->
            assertTrue(second.files.joinToString { it.readText() }.contains("later-29"))
            assertFalse(second.files.joinToString { it.readText() }.contains("failure-before-export"))
        }
        assertTrue(first.files.joinToString { it.readText() }.contains("failure-before-export"))
        first.close()
        assertFalse(first.directory.exists())
        writer.close()
    }

    @Test fun queueOverflowCountsDroppedLinesWithoutDroppingExportBarrier() = runTest {
        val writer = FileLogWriter(temp.newFolder("logs"), "session", backgroundScope, { _, e -> throw AssertionError(e) }, queueCapacity = 2)
        writer.append('I', "test", "before")
        val capture = async(start = CoroutineStart.UNDISPATCHED) { writer.snapshot(temp.root) }
        repeat(10) { writer.append('I', "test", "after-$it") }
        capture.await().use { assertEquals(9, it.health.droppedLines) }
        writer.snapshot(temp.root).use { snapshot ->
            val text = snapshot.files.joinToString { it.readText() }
            assertTrue(text.contains("after-9"))
            assertTrue(text.contains("after-8"))
            assertFalse(text.contains("before"))
        }
        writer.close()
    }

    @Test fun cancellationBeforeCaptureDoesNotLeaveTemporaryFiles() = runTest {
        val writer = FileLogWriter(temp.newFolder("logs"), "session", backgroundScope, { _, e -> throw AssertionError(e) })
        val capture = async(start = CoroutineStart.UNDISPATCHED) { writer.snapshot(temp.root) }
        capture.cancel()
        runCurrent()
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("log_snapshot_") })
        writer.close()
    }

    @Test fun stalledWriterTimesOutWithoutLeakingSnapshotAndCanServeNextExport() = runTest {
        val writerScheduler = TestCoroutineScheduler()
        val writerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(writerScheduler))
        val writer = FileLogWriter(temp.newFolder("logs"), "session", writerScope, { _, e -> throw AssertionError(e) })
        try {
            // Hold the IO worker while the caller's deadline advances independently.
            val stalled = async(start = CoroutineStart.UNDISPATCHED) { writer.snapshot(temp.root, timeoutMs = 25) }
            advanceTimeBy(26)
            val failure = runCatching { stalled.await() }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            writerScheduler.runCurrent()
            assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("log_snapshot_") })

            writer.append('E', "test", "after-timeout")
            val next = async(start = CoroutineStart.UNDISPATCHED) { writer.snapshot(temp.root) }
            writerScheduler.runCurrent()
            next.await().use { assertTrue(it.files.joinToString { f -> f.readText() }.contains("after-timeout")) }
        } finally {
            writerScope.cancel()
            writerScheduler.runCurrent()
        }
    }

    @Test fun writeFailureIsReportedAndExportStillExposesHealth() = runTest {
        val errors = mutableListOf<String>()
        val writer = FileLogWriter(temp.newFolder("logs"), "session", backgroundScope, { message, _ -> errors += message }, openOutput = {
            object : OutputStream() {
                override fun write(value: Int) { throw IOException("disk full") }
            }
        })
        writer.append('E', "test", "failure")
        writer.append('I', "test", "later")
        writer.snapshot(temp.root).use { snapshot ->
            assertFalse(snapshot.health.active)
            assertEquals(2, snapshot.health.failedLines)
            assertTrue(snapshot.health.lastError!!.contains("disk full"))
        }
        assertTrue(errors.contains("write file log failed"))
        writer.close()
    }

    @Test fun flushFailureIsNotReportedAsHealthy() = runTest {
        val writer = FileLogWriter(temp.newFolder("logs"), "session", backgroundScope, { _, _ -> }, openOutput = {
            object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun flush() { throw IOException("flush failed") }
            }
        })
        writer.append('I', "test", "buffered")
        writer.snapshot(temp.root).use {
            assertFalse(it.health.active)
            assertTrue(it.health.lastError!!.contains("flush failed"))
        }
        writer.close()
    }

    @Test fun snapshotFailureDoesNotStopRecording() = runTest {
        val errors = mutableListOf<String>()
        val writer = FileLogWriter(temp.newFolder("logs"), "session", backgroundScope, { message, _ -> errors += message })
        val invalidParent = temp.newFile("not-a-directory")
        try {
            writer.snapshot(invalidParent)
            fail("expected snapshot failure")
        } catch (_: IOException) {
            assertTrue(errors.contains("capture file logs failed"))
        }
        writer.append('E', "test", "still-recording")
        writer.snapshot(temp.root).use { assertTrue(it.files.joinToString { f -> f.readText() }.contains("still-recording")) }
        writer.close()
    }

    @Test fun oversizedUnicodeLineIsMarkedAndLaterLinesContinue() = runTest {
        val writer = FileLogWriter(temp.newFolder("logs"), "session", backgroundScope, { _, e -> throw AssertionError(e) }, maxFileBytes = 512, maxFiles = 3, maxTotalBytes = 1536)
        writer.append('E', "test", "错误详情".repeat(1000))
        writer.append('I', "test", "next-event")
        writer.snapshot(temp.root).use {
            assertEquals(1, it.health.truncatedLines)
            assertTrue(it.files.all { f -> f.length() <= 512 })
            val text = it.files.joinToString { f -> f.readText() }
            assertTrue(text.contains("log line truncated"))
            assertTrue(text.contains("next-event"))
        }
        writer.close()
    }

    @Test fun stoppedWriterRejectsExportImmediately() = runTest {
        val writer = FileLogWriter(temp.newFolder("logs"), "session", backgroundScope, { _, e -> throw AssertionError(e) })
        writer.close()
        try {
            writer.snapshot(temp.root)
            fail("expected stopped writer")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("stopped"))
        }
    }
}
