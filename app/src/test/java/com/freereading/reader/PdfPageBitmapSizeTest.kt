package com.freereading.reader

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageBitmapSizeTest {
    @Test fun keepsTheOriginalAspectRatio() {
        assertEquals(IntSize(750, 1500), pdfBitmapSize(500, 1000, IntSize(1000, 1000)))
        assertEquals(IntSize(1500, 750), pdfBitmapSize(1000, 500, IntSize(1000, 1000)))
    }

    @Test fun boundsLargePagesWithoutIntegerOverflow() {
        assertEquals(IntSize(2048, 2048),
            pdfBitmapSize(Int.MAX_VALUE, Int.MAX_VALUE, IntSize(10000, 10000)))
        assertEquals(IntSize(3072, 1),
            pdfBitmapSize(Int.MAX_VALUE, 1, IntSize(10000, 10000)))
    }
}
