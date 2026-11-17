package blbl.cat3399.core.io

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class DocumentExporterTest {
    @Test
    fun zipWriterFinishesArchiveWithoutClosingTheDestinationEarly() {
        val output = StrictOutput()

        val count = DocumentExporter.writeAndClose(output) { borrowed ->
            ZipOutputStream(borrowed).use { zip ->
                zip.putNextEntry(ZipEntry("logs/session.log"))
                zip.write("exported log".toByteArray())
                zip.closeEntry()
            }
            assertFalse("The exporter still owns the destination", output.closed)
            1
        }

        assertEquals(1, count)
        assertEquals(1, output.closeCount)
        ZipInputStream(output.bytes.toByteArray().inputStream()).use { zip ->
            assertEquals("logs/session.log", zip.nextEntry.name)
            assertEquals("exported log", zip.readBytes().toString(Charsets.UTF_8))
            assertNull(zip.nextEntry)
        }
        // A complete archive must contain its end-of-central-directory record.
        val archive = output.bytes.toByteArray()
        assertArrayEquals(byteArrayOf(0x50, 0x4b, 0x05, 0x06), archive.copyOfRange(archive.size - 22, archive.size - 18))
    }

    @Test
    fun plainConfigWriterIsFlushedAndClosedByExporter() {
        val output = StrictOutput()
        DocumentExporter.writeAndClose(output) { it.write("{\"kind\":\"blbl_config_backup\"}".toByteArray()) }
        assertEquals("{\"kind\":\"blbl_config_backup\"}", output.bytes.toString("UTF-8"))
        assertEquals(1, output.closeCount)
    }

    @Test
    fun destinationFlushFailureCannotBeReportedAsSuccess() {
        val failure = IOException("disk full")
        val output = StrictOutput(flushFailure = failure)

        val error = assertThrows(IOException::class.java) {
            DocumentExporter.writeAndClose(output) { it.write("config".toByteArray()) }
        }

        assertSame(failure, error)
        assertTrue(output.closed)
    }

    @Test
    fun writerFailureIsPreservedAndDestinationStillCloses() {
        val failure = IOException("log snapshot is unreadable")
        val output = StrictOutput()

        val error = assertThrows(IOException::class.java) {
            DocumentExporter.writeAndClose(output) { throw failure }
        }

        assertSame(failure, error)
        assertEquals(1, output.closeCount)
    }

    private class StrictOutput(private val flushFailure: IOException? = null) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var closed = false
        var closeCount = 0

        override fun write(value: Int) {
            checkOpen()
            bytes.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkOpen()
            bytes.write(buffer, offset, length)
        }

        override fun flush() {
            checkOpen()
            flushFailure?.let { throw it }
        }

        override fun close() {
            checkOpen()
            closeCount++
            closed = true
        }

        private fun checkOpen() {
            if (closed) throw IOException("Stream closed")
        }
    }
}
