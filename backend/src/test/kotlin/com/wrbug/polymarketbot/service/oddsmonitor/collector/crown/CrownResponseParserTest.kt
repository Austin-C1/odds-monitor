package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class CrownResponseParserTest {
    private val parser = CrownResponseParser()

    @Test
    fun `parses successful login response`() {
        val response = """
            <serverresponse>
              <status>200</status>
              <uid>abc123</uid>
            </serverresponse>
        """.trimIndent()

        val login = parser.parseLogin(response)

        assertEquals("200", login.status)
        assertEquals("abc123", login.uid)
    }

    @Test
    fun `parses login failure message from crown`() {
        val response = """
            <serverresponse>
              <status>error</status>
              <msg>101</msg>
              <code_message>password attempts exceeded</code_message>
              <uid></uid>
            </serverresponse>
        """.trimIndent()

        val login = parser.parseLogin(response)

        assertEquals("error", login.status)
        assertEquals("101", login.messageCode)
        assertEquals("password attempts exceeded", login.message)
    }

    @Test
    fun `parses crown football detail games into normalized matches`() {
        val response = """
            <serverresponse>
              <game>
                <gid>41001</gid>
                <league>England Premier League</league>
                <datetime>05-01 19:30</datetime>
                <team_h>Arsenal</team_h>
                <team_c>Chelsea</team_c>
                <ior_rh>1.93</ior_rh>
                <ratio>0 / 0.5</ratio>
                <ior_rc>1.97</ior_rc>
                <ratio_r_alt>0.5</ratio_r_alt>
                <ior_rh_alt>2.05</ior_rh_alt>
                <ior_rc_alt>1.85</ior_rc_alt>
                <ratio_o>2.5</ratio_o>
                <ior_ouc>1.88</ior_ouc>
                <ior_ouh>2.02</ior_ouh>
                <ratio_ouo_alt>2.75</ratio_ouo_alt>
                <ior_ouc_alt>2.20</ior_ouc_alt>
                <ior_ouh_alt>1.70</ior_ouh_alt>
                <ior_mh>2.11</ior_mh>
                <ior_mn>3.30</ior_mn>
                <ior_mc>3.20</ior_mc>
              </game>
            </serverresponse>
        """.trimIndent()

        val matches = parser.parseDetailGames(response, isLive = false)

        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals("41001", match.sourceMatchId)
        assertEquals("England Premier League", match.leagueName)
        assertEquals("Arsenal", match.homeTeam)
        assertEquals("Chelsea", match.awayTeam)
        assertEquals(false, match.isLive)
        assertEquals(listOf("0 / 0.5", "0.5"), match.handicaps.map { it.line })
        assertEquals(listOf(BigDecimal("1.93"), BigDecimal("2.05")), match.handicaps.map { it.homeOdds })
        assertEquals(listOf(BigDecimal("1.97"), BigDecimal("1.85")), match.handicaps.map { it.awayOdds })
        assertEquals(listOf("2.5", "2.75"), match.totals.map { it.line })
        assertEquals(listOf(BigDecimal("1.88"), BigDecimal("2.20")), match.totals.map { it.overOdds })
        assertEquals(listOf(BigDecimal("2.02"), BigDecimal("1.70")), match.totals.map { it.underOdds })
        assertEquals(BigDecimal("2.11"), match.moneyline?.homeOdds)
        assertEquals(BigDecimal("3.30"), match.moneyline?.drawOdds)
        assertEquals(BigDecimal("3.20"), match.moneyline?.awayOdds)
    }

    @Test
    fun `parses direct uppercase crown game list response into normalized matches`() {
        val response = """
            <serverresponse>
              <game>
                <GID>8733261</GID>
                <LEAGUE>Japan J1 League</LEAGUE>
                <DATETIME>05-01 11:55p</DATETIME>
                <TEAM_H>Okayama</TEAM_H>
                <TEAM_C>Hiroshima</TEAM_C>
                <RATIO_R>0.5 / 1</RATIO_R>
                <IOR_RH>0.880</IOR_RH>
                <IOR_RC>1.000</IOR_RC>
                <RATIO_OUO>2.5</RATIO_OUO>
                <RATIO_OUU>2.5</RATIO_OUU>
                <IOR_OUH>0.930</IOR_OUH>
                <IOR_OUC>0.940</IOR_OUC>
                <IOR_MH>4.95</IOR_MH>
                <IOR_MN>3.60</IOR_MN>
                <IOR_MC>1.75</IOR_MC>
              </game>
            </serverresponse>
        """.trimIndent()

        val matches = parser.parseDetailGames(response, isLive = false)

        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals("8733261", match.sourceMatchId)
        assertEquals("Japan J1 League", match.leagueName)
        assertEquals("Okayama", match.homeTeam)
        assertEquals("Hiroshima", match.awayTeam)
        val startTime = Instant.ofEpochMilli(match.startTime ?: 0).atZone(ZoneId.systemDefault())
        assertEquals(5, startTime.monthValue)
        assertEquals(1, startTime.dayOfMonth)
        assertEquals(23, startTime.hour)
        assertEquals(55, startTime.minute)
        assertEquals("0.5 / 1", match.handicaps.single().line)
        assertEquals(BigDecimal("0.880"), match.handicaps.single().homeOdds)
        assertEquals(BigDecimal("1.000"), match.handicaps.single().awayOdds)
        assertEquals("2.5", match.totals.single().line)
        assertEquals(BigDecimal("0.940"), match.totals.single().overOdds)
        assertEquals(BigDecimal("0.930"), match.totals.single().underOdds)
        assertEquals(BigDecimal("4.95"), match.moneyline?.homeOdds)
        assertEquals(BigDecimal("3.60"), match.moneyline?.drawOdds)
        assertEquals(BigDecimal("1.75"), match.moneyline?.awayOdds)
    }

    @Test
    fun `detects live detail fields even when caller passes prematch flag`() {
        val response = """
            <serverresponse>
              <game>
                <gid>8735721</gid>
                <showtype>rb</showtype>
                <league>England Premier League</league>
                <datetime>05-04 03:00p</datetime>
                <team_h>Arsenal</team_h>
                <team_c>Chelsea</team_c>
                <ratio_re>0 / 0.5</ratio_re>
                <ior_reh>0.810</ior_reh>
                <ior_rec>1.070</ior_rec>
                <ratio_rouo>5.5</ratio_rouo>
                <ratio_rouu>5.5</ratio_rouu>
                <ior_rouh>0.990</ior_rouh>
                <ior_rouc>0.880</ior_rouc>
              </game>
            </serverresponse>
        """.trimIndent()

        val match = parser.parseDetailGames(response, isLive = false).single()

        assertEquals(true, match.isLive)
        assertEquals(listOf("0 / 0.5"), match.handicaps.map { it.line })
        assertEquals(listOf(BigDecimal("0.810")), match.handicaps.map { it.homeOdds })
        assertEquals(listOf(BigDecimal("1.070")), match.handicaps.map { it.awayOdds })
        assertEquals(listOf("5.5"), match.totals.map { it.line })
        assertEquals(listOf(BigDecimal("0.880")), match.totals.map { it.overOdds })
        assertEquals(listOf(BigDecimal("0.990")), match.totals.map { it.underOdds })
    }

    @Test
    fun `ignores esports football detail games`() {
        val response = """
            <serverresponse>
              <game>
                <GID>90001</GID>
                <LEAGUE>电竞足球-H2H GG联赛(2 x 4分钟)</LEAGUE>
                <DATETIME>05-01 11:55p</DATETIME>
                <TEAM_H>Portugal (Hollywood) Esports</TEAM_H>
                <TEAM_C>France (Emperor) Esports</TEAM_C>
                <RATIO_R>-0.5</RATIO_R>
                <IOR_RH>1.280</IOR_RH>
                <IOR_RC>0.680</IOR_RC>
              </game>
            </serverresponse>
        """.trimIndent()

        val matches = parser.parseDetailGames(response, isLive = false)

        assertEquals(emptyList<CrownFootballMatch>(), matches)
    }

    @Test
    fun `parses crown game list items needed to load match details`() {
        val response = """
            <serverresponse>
              <ec>
                <item>
                  <gidm>GIDM-1</gidm>
                  <gid>41001</gid>
                  <lid>900</lid>
                  <ecid>EC-1</ecid>
                  <retimeset>0</retimeset>
                  <is_rb>N</is_rb>
                </item>
                <item>
                  <gidm>GIDM-2</gidm>
                  <gid>41002</gid>
                  <lid>901</lid>
                  <retimeset>1</retimeset>
                  <is_rb>Y</is_rb>
                </item>
              </ec>
            </serverresponse>
        """.trimIndent()

        val items = parser.parseGameList(response)

        assertEquals(2, items.size)
        assertEquals("900", items[0].lid)
        assertEquals("EC-1", items[0].ecid)
        assertEquals(false, items[0].isLive)
        assertEquals("901", items[1].lid)
        assertEquals("GIDM-2", items[1].detailId)
        assertEquals(true, items[1].isLive)
    }
}
