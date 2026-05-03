package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinnacleFootballParserTest {
    private val html = """
        <html>
          <body>
            <div class="league" id="lg100">
              <span>您已达到可添加最爱的上限。</span>
              <span>England - Premier League</span>
            </div>
            <table class="events" id="e9001">
              <tr data-eid="9001" data-score="">
                <td rowspan="3">
                  <span class="sel">Arsenal</span>
                  <span class="sel">Chelsea</span>
                </td>
                <td class="col-hdp" data-period="0">
                  <a class="odds" data-date="1893456000000"><p class="prefix-hdp">-0.5</p><span>1.93</span></a>
                  <a class="odds"><span>1.97</span></a>
                </td>
                <td class="col-ou" data-period="0">
                  <a class="odds"><p class="prefix-ou">2.5</p><span>1.88</span></a>
                  <a class="odds"><span>2.02</span></a>
                </td>
                <td class="col-ml" data-period="0">
                  <a class="odds"><span>2.11</span></a>
                  <a class="odds"><span>3.30</span></a>
                  <a class="odds"><span>3.20</span></a>
                </td>
              </tr>
              <tr>
                <td class="col-hdp" data-period="0">
                  <a class="odds"><p class="prefix-hdp">-0.25</p><span>1.83</span></a>
                  <a class="odds"><span>2.07</span></a>
                </td>
                <td class="col-ou" data-period="0">
                  <a class="odds"><p class="prefix-ou">2.25</p><span>1.80</span></a>
                  <a class="odds"><span>2.10</span></a>
                </td>
              </tr>
            </table>
            <div class="league" id="lg101">
              <span>Spain - La Liga</span>
            </div>
            <table class="events" id="e9002">
              <tr data-eid="9002" data-score="1-0">
                <td>
                  <span class="sel">Real Madrid</span>
                  <span class="sel">Barcelona</span>
                </td>
                <td class="col-hdp" data-period="0">
                  <a class="odds"><p class="prefix-hdp">0</p><span>1.91</span></a>
                  <a class="odds"><span>1.99</span></a>
                </td>
              </tr>
            </table>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun `parses prematch full time all handicap total and moneyline markets`() {
        val matches = PinnacleFootballParser().parse(html)

        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals("9001", match.sourceMatchId)
        assertEquals("England - Premier League", match.leagueName)
        assertEquals("Arsenal", match.homeTeam)
        assertEquals("Chelsea", match.awayTeam)
        assertEquals(1893456000000L, match.startTime)
        assertFalse(match.isLive)

        assertEquals(listOf("-0.5", "-0.25"), match.handicaps.map { it.line })
        assertEquals(listOf("1.93", "1.83"), match.handicaps.map { it.homeOdds.toPlainString() })
        assertEquals(listOf("1.97", "2.07"), match.handicaps.map { it.awayOdds.toPlainString() })

        assertEquals(listOf("2.5", "2.25"), match.totals.map { it.line })
        assertEquals(listOf("1.88", "1.80"), match.totals.map { it.overOdds.toPlainString() })
        assertEquals(listOf("2.02", "2.10"), match.totals.map { it.underOdds.toPlainString() })

        assertEquals("2.11", match.moneyline?.homeOdds?.toPlainString())
        assertEquals("3.30", match.moneyline?.drawOdds?.toPlainString())
        assertEquals("3.20", match.moneyline?.awayOdds?.toPlainString())
    }

    @Test
    fun `returns no matches when html has no football events`() {
        val matches = PinnacleFootballParser().parse("<html><body>No events</body></html>")

        assertTrue(matches.isEmpty())
    }
}
