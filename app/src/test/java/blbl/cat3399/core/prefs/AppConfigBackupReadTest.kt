package blbl.cat3399.core.prefs

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppConfigBackupReadTest {
    @Test
    fun stopsReadingOversizedFileBeforeParsingIt() {
        val input = ByteArrayInputStream(ByteArray(8 * 1024 * 1024))

        try {
            AppConfigBackup.read(input)
            fail("An oversized backup must be rejected")
        } catch (error: IllegalArgumentException) {
            assertEquals("配置文件过大，最大支持 4 MiB", error.message)
            assertTrue("The reader must not consume the entire file", input.available() > 0)
        }
    }

    @Test
    fun permitsExactSizeLimitBeforeValidatingContent() {
        val input = ByteArrayInputStream(ByteArray(4 * 1024 * 1024) { ' '.code.toByte() })

        try {
            AppConfigBackup.read(input)
            fail("Whitespace is not a valid backup")
        } catch (error: IllegalStateException) {
            assertEquals("配置文件为空", error.message)
        }
    }

    @Test
    fun removesUtf8BomBeforeEmptyFileValidation() {
        val input = ByteArrayInputStream("\uFEFF \n\t".toByteArray(Charsets.UTF_8))

        try {
            AppConfigBackup.read(input)
            fail("An empty backup must be rejected")
        } catch (error: IllegalStateException) {
            assertEquals("配置文件为空", error.message)
        }
    }

    @Test
    fun preservesReadFailureInsteadOfParsingPartialContent() {
        val failure = IOException("File permission was revoked")
        val input =
            object : InputStream() {
                override fun read(): Int = throw failure
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
            }

        try {
            AppConfigBackup.read(input)
            fail("The original read failure must reach the caller")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
    }
}
