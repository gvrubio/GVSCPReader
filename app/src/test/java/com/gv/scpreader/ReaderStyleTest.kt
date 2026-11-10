package com.gv.scpreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderStyleTest {
    @Test
    fun slugFromInput() {
        assertEquals("scp-173", ScpRepository.slugFromInput("173"))
        assertEquals("scp-049", ScpRepository.slugFromInput("SCP-049"))
        assertEquals("scp-002", ScpRepository.slugFromInput("2"))
        assertEquals("scp-1000", ScpRepository.slugFromInput("scp 1000"))
        assertEquals("scp-173-j", ScpRepository.slugFromInput("173-J"))
        assertNull(ScpRepository.slugFromInput("the sculpture"))
    }

    @Test
    fun seriesFor() {
        assertEquals("1", ScpRepository.seriesFor("scp-173")?.id)
        assertEquals("4", ScpRepository.seriesFor("scp-3008")?.id)
        assertEquals("10", ScpRepository.seriesFor("scp-9000")?.id)
        assertEquals("j", ScpRepository.seriesFor("scp-001-j")?.id)
        assertNull(ScpRepository.seriesFor("main"))
    }

    @Test
    fun objectClass() {
        assertEquals("Keter", ReaderStyle.objectClass(listOf("scp", "keter", "hostile"))?.first)
        assertEquals("Esoteric", ReaderStyle.objectClass(listOf("esoteric-class"))?.first)
        assertNull(ReaderStyle.objectClass(listOf("tale")))
    }

    @Test
    fun adHosts() {
        assertTrue(ReaderStyle.isAdHost("s.nitropay.com"))
        assertTrue(!ReaderStyle.isAdHost("scp-wiki.wikidot.com"))
    }

    /** Dumps the injected scripts so they can be exercised in a real browser (see scripts/preview.py). */
    @Test
    fun dumpScripts() {
        val out = System.getProperty("gv.dumpDir") ?: return
        File(out).mkdirs()
        ReaderStyle.PALETTES.indices.forEach { t ->
            File(out, "start_$t.js").writeText(ReaderStyle.documentStartJs(ReaderStyle.css(t, 0, 1.6f, true, true)))
        }
        listOf(150, 200).forEach { z ->
            File(out, "start_z$z.js").writeText(ReaderStyle.documentStartJs(ReaderStyle.css(0, 0, 1.6f, true, true, z)))
        }
        File(out, "start_tap25.js").writeText(ReaderStyle.documentStartJs(ReaderStyle.css(0, 0, 1.6f, true, true), 25))
        File(out, "start_red140.js").writeText(ReaderStyle.documentStartJs(ReaderStyle.css(3, 0, 1.6f, true, true, 140)))
        File(out, "after.js").writeText(ReaderStyle.afterLoadJs(false, "scp-174", "SCP-174", "Ventriloquist's Dummy"))
    }
}
