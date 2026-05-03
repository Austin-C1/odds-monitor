package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinnacleBrowserPageReadinessTest {
    @Test
    fun `detects loaded odds page only after loading marker is gone and team names exist`() {
        val readiness = PinnaclePageReadiness()

        assertFalse(readiness.hasLoadedOdds("""<div id="oddspage" class="is-loading"><div class="OddsPageNormal"></div></div>"""))
        assertTrue(
            readiness.hasLoadedOdds(
                """
                <div id="oddspage" class="enhanced-odd-selection">
                  <div class="OddsPageNormal">
                    <span>中国 - 超级联赛</span>
                    <span>上海申花</span>
                    <span>成都蓉城</span>
                    <button>0-0.5 1.934</button>
                    <button>2.5 1.757</button>
                  </div>
                </div>
                """.trimIndent()
            )
        )
    }
}
