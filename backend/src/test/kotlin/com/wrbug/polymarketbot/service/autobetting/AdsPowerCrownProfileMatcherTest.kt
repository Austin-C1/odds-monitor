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
    fun `matches login name from the crown page before adspower metadata`() {
        val result = AdsPowerCrownProfileMatcher.match(
            loginName = "skjd447",
            candidates = listOf(
                candidate(profileId = "profile-a", profileUsername = "other_user", pageLoginName = "skjd447"),
                candidate(profileId = "profile-b", profileUsername = "skjd447", pageLoginName = "other_user")
            )
        )

        assertEquals("profile-a", result?.profileId)
    }

    @Test
    fun `does not use a single opened profile when crown page login name is different`() {
        val result = AdsPowerCrownProfileMatcher.match(
            loginName = "skjd447",
            candidates = listOf(
                candidate(profileId = "profile-a", profileUsername = "skjd447", pageLoginName = "other_user")
            )
        )

        assertNull(result)
    }

    @Test
    fun `uses preferred serial number when page login name is not available`() {
        val result = AdsPowerCrownProfileMatcher.match(
            loginName = "skjd447",
            preferredProfileId = "27",
            candidates = listOf(
                candidate(profileId = "profile-a", profileSerialNumber = "18", profileUsername = "profile-a"),
                candidate(profileId = "profile-b", profileSerialNumber = "27", profileUsername = "profile-b")
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
    fun `does not use the only opened logged-in crown profile without exact account evidence`() {
        val result = AdsPowerCrownProfileMatcher.match(
            loginName = "crown_user",
            candidates = listOf(
                candidate(profileId = "profile-a", profileUsername = "profile_user")
            )
        )

        assertNull(result)
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
        remark: String? = null,
        profileSerialNumber: String? = null,
        pageLoginName: String? = null
    ) = AdsPowerCrownSessionCandidateDto(
        profileId = profileId,
        profileSerialNumber = profileSerialNumber,
        profileName = profileName,
        profileUsername = profileUsername,
        remark = remark,
        pageLoginName = pageLoginName,
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
