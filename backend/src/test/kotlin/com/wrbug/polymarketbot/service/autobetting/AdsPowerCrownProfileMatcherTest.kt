package com.wrbug.polymarketbot.service.autobetting

import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionCandidateDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AdsPowerCrownProfileMatcherTest {
    @Test
    fun `matches login name to an opened logged-in crown profile by username`() {
        val result = AdsPowerCrownProfileMatcher.match(
            loginName = " crown_user ",
            candidates = listOf(
                candidate(profileId = "profile-a", profileUsername = "other_user"),
                candidate(profileId = "profile-b", profileUsername = "crown_user")
            )
        )

        assertEquals("profile-b", result?.profileId)
    }

    @Test
    fun `does not match closed or logged-out crown profiles`() {
        val result = AdsPowerCrownProfileMatcher.match(
            loginName = "crown_user",
            candidates = listOf(
                candidate(profileId = "closed", opened = false, loggedIn = true, profileUsername = "crown_user"),
                candidate(profileId = "login-required", opened = true, loggedIn = false, profileUsername = "crown_user")
            )
        )

        assertNull(result)
    }

    @Test
    fun `uses the only opened logged-in crown profile when no exact metadata match exists`() {
        val result = AdsPowerCrownProfileMatcher.match(
            loginName = "crown_user",
            candidates = listOf(
                candidate(profileId = "profile-a", profileUsername = "profile_user")
            )
        )

        assertEquals("profile-a", result?.profileId)
    }

    @Test
    fun `refuses ambiguous logged-in profiles without an exact login match`() {
        val result = AdsPowerCrownProfileMatcher.match(
            loginName = "crown_user",
            candidates = listOf(
                candidate(profileId = "profile-a", profileUsername = "first"),
                candidate(profileId = "profile-b", profileUsername = "second")
            )
        )

        assertNull(result)
    }

    private fun candidate(
        profileId: String,
        opened: Boolean = true,
        loggedIn: Boolean = true,
        profileName: String? = null,
        profileUsername: String? = null,
        remark: String? = null
    ) = AdsPowerCrownSessionCandidateDto(
        profileId = profileId,
        profileName = profileName,
        profileUsername = profileUsername,
        remark = remark,
        opened = opened,
        loggedIn = loggedIn,
        accountStatus = if (loggedIn) "online" else "login_required",
        balance = BigDecimal("100.00"),
        currency = "CNY",
        pageUrl = "https://m407.mos077.com/",
        message = if (loggedIn) "online" else "login_required",
        debugPort = "39555",
        checkedAt = 1234
    )
}
