package com.share64.app.core

import com.share64.core.Direction
import com.share64.core.ErrorCode
import com.share64.core.TransferSnapshot
import com.share64.core.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TransferPresenterTest {
    private val en = Locale.US

    private fun snap(
        id: Long = 1,
        dir: Direction = Direction.SEND,
        state: TransferState = TransferState.ACTIVE,
        total: Long = 100L * 1024 * 1024,
        done: Long = 0,
        verified: Long = 0,
        bps: Long = 0,
        error: ErrorCode = ErrorCode.OK,
        message: String = "",
        attempt: Int = 0,
        name: String = "movie.mkv",
    ) = TransferSnapshot(id, dir, name, total, done, verified, bps, state, error, message, attempt, null, null)

    @Test fun startingAndRunning() {
        val starting = TransferPresenter.present(snap(), false, en)
        assertEquals("Connecting…", starting.status)
        assertTrue(starting.indeterminate)
        assertTrue(starting.canCancel)

        val running = TransferPresenter.present(snap(done = 50L * 1024 * 1024, bps = 10L * 1024 * 1024), false, en)
        assertEquals("Sending · 10.0 MB/s · 5 s left", running.status)
        assertEquals("50.0 MB of 100 MB", running.detail)
        assertEquals(0.5f, running.progress, 0.001f)
        assertFalse(running.indeterminate)
    }

    @Test fun receiverWording() {
        val r = TransferPresenter.present(snap(dir = Direction.RECEIVE, done = 1024, bps = 1024), false, en)
        assertTrue(r.status.startsWith("Receiving"))
        assertFalse(r.isSend)
    }

    @Test fun retryingAndInterrupted() {
        val retry = TransferPresenter.present(snap(state = TransferState.RETRYING, verified = 4L * 1024 * 1024, attempt = 3), false, en)
        assertEquals("Connection lost — reconnecting", retry.status)
        assertEquals("Resuming from 4.00 MB", retry.detail)
        assertTrue(retry.indeterminate && retry.canCancel)
        val none = TransferPresenter.present(snap(state = TransferState.RETRYING, attempt = 3), false, en)
        assertEquals("Attempt 3", none.detail)
        val intr = TransferPresenter.present(snap(dir = Direction.RECEIVE, state = TransferState.INTERRUPTED, verified = 2L * 1024 * 1024), false, en)
        assertEquals("Connection lost — waiting for the sender", intr.status)
    }

    @Test fun offerCannotBeCancelledOnlyAnswered() {
        val o = TransferPresenter.present(snap(dir = Direction.RECEIVE, state = TransferState.OFFERED), false, en)
        assertFalse(o.canCancel)
        assertFalse(o.canDismiss)
    }

    @Test fun terminalStates() {
        val done = TransferPresenter.present(snap(dir = Direction.RECEIVE, state = TransferState.COMPLETED), true, en)
        assertEquals(1f, done.progress, 0f)
        assertTrue(done.canOpen && done.canDismiss && !done.canCancel)
        assertFalse(TransferPresenter.present(snap(dir = Direction.RECEIVE, state = TransferState.COMPLETED), false, en).canOpen)
        assertFalse(TransferPresenter.present(snap(dir = Direction.SEND, state = TransferState.COMPLETED), true, en).canOpen)

        val declined = TransferPresenter.present(snap(state = TransferState.FAILED, error = ErrorCode.REJECTED), false, en)
        assertEquals("The other device declined the file", declined.status)
        val full = TransferPresenter.present(snap(state = TransferState.FAILED, error = ErrorCode.DISK_FULL, verified = 3L * 1024 * 1024), false, en)
        assertEquals("Not enough free storage", full.status)
        assertEquals("Transferred 3.00 MB before stopping", full.detail)

        val cancelled = TransferPresenter.present(snap(state = TransferState.CANCELLED, message = "cancelled by receiver"), false, en)
        assertEquals("Cancelled", cancelled.status)
        assertEquals("Cancelled by receiver", cancelled.detail)
        assertEquals("", TransferPresenter.present(snap(state = TransferState.CANCELLED, message = "cancelled"), false, en).detail)
    }

    @Test fun completedReceiveShowsWhereItWasSaved() {
        val done = TransferPresenter.present(snap(dir = Direction.RECEIVE, state = TransferState.COMPLETED), true, en, savedTo = "Downloads/SHARE64")
        assertEquals("100 MB · Saved to Downloads/SHARE64", done.detail)
        val sent = TransferPresenter.present(snap(dir = Direction.SEND, state = TransferState.COMPLETED), false, en, savedTo = "Downloads/SHARE64")
        assertEquals("100 MB", sent.detail)
        val unknown = TransferPresenter.present(snap(dir = Direction.RECEIVE, state = TransferState.COMPLETED), false, en)
        assertEquals("100 MB", unknown.detail)
    }

    @Test fun orderingRunningFirstThenNewestFirst() {
        val list = listOf(
            snap(id = 1, state = TransferState.COMPLETED),
            snap(id = 2, state = TransferState.ACTIVE),
            snap(id = 3, state = TransferState.FAILED),
            snap(id = 4, state = TransferState.RETRYING),
        )
        assertEquals(listOf(4L, 2L, 3L, 1L), TransferPresenter.order(list).map { it.id })
    }

    @Test fun blankNameFallsBack() {
        assertEquals("File", TransferPresenter.present(snap(name = ""), false, en).title)
    }
}
