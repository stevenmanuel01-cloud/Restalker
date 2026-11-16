package com.restalker.mobile.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTestsJUnit {
    @Test fun allCoreRules() {
        val r = CoreTests.run()
        val bad = r.filterValues { !it }.keys
        assertTrue("failed: $bad", bad.isEmpty())
    }
}
