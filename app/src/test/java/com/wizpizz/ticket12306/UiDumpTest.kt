package com.wizpizz.ticket12306

import com.wizpizz.ticket12306.auto.UiDump
import org.junit.Assert.assertEquals
import org.junit.Test

class UiDumpTest {
    private val xml = """<?xml version='1.0'?><hierarchy>
<node index="0" text="武汉 &lt;&gt; 全州南" class="android.widget.TextView" content-desc="" bounds="[398,141][874,309]" />
<node index="1" text="" class="android.widget.TextView" content-desc="出发站: 武汉" bounds="[116,748][278,913]" />
<node index="2" text="空" class="android.view.View" content-desc="" bounds="[0,0][0,0]" />
</hierarchy>"""

    @Test
    fun parsesTextDescAndBoundsAndSkipsEmptyBounds() {
        val nodes = UiDump.parse(xml)
        assertEquals(2, nodes.size)
        assertEquals("武汉 <> 全州南", nodes[0].text)
        assertEquals("出发站: 武汉", nodes[1].desc)
        assertEquals("TextView", nodes[1].cls)
        assertEquals(197 to 830, nodes[1].cx to nodes[1].cy)
    }

    @Test
    fun labelFallsBackFromTextToDesc() {
        val nodes = UiDump.parse(xml)
        assertEquals("武汉 <> 全州南", nodes[0].label)
        assertEquals("出发站: 武汉", nodes[1].label)
    }
}
