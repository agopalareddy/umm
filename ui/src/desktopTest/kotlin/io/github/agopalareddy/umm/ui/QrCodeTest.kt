package io.github.agopalareddy.umm.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test
    fun matrixHasFinderPatterns() {
        val matrix = qrMatrix("test-payload")
        // Top-left 7x7 finder pattern:
        // Border dark: row 0, row 6, col 0, col 6 in 0..6
        for (i in 0..6) {
            assertTrue("Border dark at (0, $i)", matrix[0][i])
            assertTrue("Border dark at (6, $i)", matrix[6][i])
            assertTrue("Border dark at ($i, 0)", matrix[i][0])
            assertTrue("Border dark at ($i, 6)", matrix[i][6])
        }
        // Ring light: row 1, row 5, col 1, col 5 for 1..5
        for (i in 1..5) {
            assertFalse("Ring light at (1, $i)", matrix[1][i])
            assertFalse("Ring light at (5, $i)", matrix[5][i])
            assertFalse("Ring light at ($i, 1)", matrix[i][1])
            assertFalse("Ring light at ($i, 5)", matrix[i][5])
        }
        // Core dark: 3x3 core in 2..4, 2..4
        for (r in 2..4) {
            for (c in 2..4) {
                assertTrue("Core dark at ($r, $c)", matrix[r][c])
            }
        }
    }

    @Test
    fun pairingCodeFits() {
        val code = "a".repeat(400)
        val matrix = qrMatrix(code)
        assertTrue(matrix.isNotEmpty())
        assertTrue(matrix[0].isNotEmpty())
    }
}
