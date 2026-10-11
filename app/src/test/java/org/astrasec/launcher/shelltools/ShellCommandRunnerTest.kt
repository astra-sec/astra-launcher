package org.astrasec.launcher.shelltools

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandRunnerTest {
    private val directory = File(checkNotNull(System.getProperty("java.io.tmpdir")))

    @Test
    fun `preserves quoting merges stderr and reports nonzero exit`() {
        val listener = RecordingListener()
        val runner = ShellCommandRunner("/bin/sh", emptyList()) { }
        runner.start("printf '%s' 'two words; literal'; printf 'problem' >&2; exit 7", directory, listener)
        assertTrue(listener.finished.await(5, TimeUnit.SECONDS))
        assertEquals("two words; literalproblem", listener.output.toString())
        assertEquals(7, listener.result?.exitCode)
        assertEquals(false, listener.result?.cancelled)
        assertNull(listener.result?.error)
    }

    @Test
    fun `streams output before a long command completes and cancellation finishes`() {
        val listener = RecordingListener()
        val runner = ShellCommandRunner("/bin/sh", emptyList()) { }
        val command = runner.start("printf 'ready'; exec sleep 30", directory, listener)
        try {
            assertTrue(listener.firstOutput.await(5, TimeUnit.SECONDS))
            assertFalse(listener.finished.await(50, TimeUnit.MILLISECONDS))
        } finally {
            command.cancel()
        }
        assertTrue(listener.finished.await(5, TimeUnit.SECONDS))
        assertEquals(true, listener.result?.cancelled)
    }

    @Test
    fun `immediate cancellation always finishes`() {
        val listener = RecordingListener()
        val runner = ShellCommandRunner("/bin/sh", emptyList()) { }
        val command = runner.start("exec sleep 30", directory, listener)
        command.cancel()
        assertTrue(listener.finished.await(5, TimeUnit.SECONDS))
        assertEquals(true, listener.result?.cancelled)
    }

    @Test
    fun `reports a missing shell executable instead of hanging`() {
        val listener = RecordingListener()
        ShellCommandRunner("/missing/astra-test-shell", emptyList()) { }
            .start("id", directory, listener)
        assertTrue(listener.finished.await(5, TimeUnit.SECONDS))
        assertNull(listener.result?.exitCode)
        assertTrue(listener.result?.error?.isNotEmpty() == true)
    }

    @Test
    fun `bounded output keeps the latest characters across oversized chunks`() {
        val output = BoundedShellOutput(8)
        output.append("12345")
        assertEquals("12345" to false, output.snapshot())
        output.append("67890")
        assertEquals("34567890" to true, output.snapshot())
        output.append("abcdefghijkl")
        assertEquals("efghijkl" to true, output.snapshot())
    }

    private class RecordingListener : ShellCommandRunner.Listener {
        val output = StringBuffer()
        val firstOutput = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var result: ShellCommandRunner.Result? = null

        override fun onOutput(text: String) {
            output.append(text)
            firstOutput.countDown()
        }

        override fun onFinished(result: ShellCommandRunner.Result) {
            this.result = result
            finished.countDown()
        }
    }
}
