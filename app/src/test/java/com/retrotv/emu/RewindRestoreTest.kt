package com.retrotv.emu

import org.junit.Assert.*
import org.junit.Test

class RewindRestoreTest {
    private val target = byteArrayOf(3)
    private val origin = byteArrayOf(9)

    @Test fun successfulRewindAppliesOnlyTheChosenState() {
        var active = 9
        assertEquals(RewindRestore.Result.APPLIED, RewindRestore.apply(target, origin,
            { active = it[0].toInt(); true }, { fail("must retain older history") }, { fail("unexpected OOM") }))
        assertEquals(3, active)
    }
    @Test fun rejectedPartlyAppliedStateRollsBackAfterReleasingHistory() {
        var active = 9; var cleared = false; var calls = 0
        val result = RewindRestore.apply(target, origin, {
            calls++; active = it[0].toInt()
            if (it === origin) { assertTrue(cleared); true } else false
        }, { cleared = true }, { fail("unexpected OOM") })
        assertEquals(RewindRestore.Result.ROLLED_BACK, result)
        assertEquals(9, active); assertEquals(2, calls)
    }
    @Test fun outOfMemoryDisablesRewindAndCanStillRecoverTheOrigin() {
        var limited = false; var cleared = false
        val result = RewindRestore.apply(target, origin, {
            if (it === target) throw OutOfMemoryError("injected JNI copy failure")
            assertTrue(limited && cleared); true
        }, { cleared = true }, { limited = true })
        assertEquals(RewindRestore.Result.ROLLED_BACK, result)
    }
    @Test fun unrecoverableStateReportsFailureInsteadOfResumingOrSaving() {
        var attempts = 0; var pressure = 0
        val result = RewindRestore.apply(target, origin, {
            attempts++; if (attempts == 1) throw IllegalStateException("core rejected")
            throw OutOfMemoryError("rollback copy failed")
        }, {}, { pressure++ })
        assertEquals(RewindRestore.Result.FAILED, result)
        assertEquals(2, attempts); assertEquals(1, pressure)
    }
    @Test fun missingOriginCannotBeReportedAsRecovered() {
        assertEquals(RewindRestore.Result.FAILED,
            RewindRestore.apply(target, null, { false }, {}, {}))
    }
}
