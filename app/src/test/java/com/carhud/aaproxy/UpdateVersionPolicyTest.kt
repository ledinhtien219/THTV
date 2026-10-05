package com.carhud.aaproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionPolicyTest {
    @Test fun onlyNewerBuildCanNotify() {
        assertFalse(UpdateVersionPolicy.shouldNotify(201, 200, emptySet(), true))
        assertFalse(UpdateVersionPolicy.shouldNotify(201, 201, emptySet(), true))
        assertTrue(UpdateVersionPolicy.shouldNotify(201, 202, emptySet(), false))
    }

    @Test fun seenBuildIsSilentButManualCheckStillShowsIt() {
        assertFalse(UpdateVersionPolicy.shouldNotify(201, 202, setOf("202"), false))
        assertTrue(UpdateVersionPolicy.shouldNotify(201, 202, setOf("202"), true))
        assertTrue(UpdateVersionPolicy.shouldNotify(201, 203, setOf("202"), false))
    }
}
