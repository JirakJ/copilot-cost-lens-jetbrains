/*
 * Copyright (c) 2026 Jakub Jirák. All rights reserved.
 */
package com.jakubjirak.copilotcostlens.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("JcefSupport")
class JcefSupportTest {

    @Test
    fun `reports availability from the probe`() {
        assertTrue(JcefSupport.isAvailable { true })
        assertFalse(JcefSupport.isAvailable { false })
    }

    /**
     * Regression guard for the 2026.2 defect: JCEF moved out of the core platform into the separate
     * `com.intellij.modules.jcef` plugin, so `JBCefApp` may be absent from the plugin classloader.
     * That surfaces as [NoClassDefFoundError] — an [Error], not an [Exception] — which ordinary
     * `catch (e: Exception)` guards do not catch.
     */
    @Test
    fun `reports unavailable when the JCEF class is missing`() {
        assertFalse(JcefSupport.isAvailable { throw NoClassDefFoundError("com/intellij/ui/jcef/JBCefApp") })
    }

    @Test
    fun `reports unavailable when the JCEF native library fails to link`() {
        assertFalse(JcefSupport.isAvailable { throw UnsatisfiedLinkError("libjcef") })
    }

    @Test
    fun `reports unavailable when the probe throws a runtime exception`() {
        assertFalse(JcefSupport.isAvailable { throw IllegalStateException("no browser") })
    }

    @Test
    fun `virtual machine errors are not swallowed`() {
        assertThrows(OutOfMemoryError::class.java) {
            JcefSupport.isAvailable { throw OutOfMemoryError("heap") }
        }
    }

    @Test
    fun `runGuarded reports success when the block completes`() {
        var ran = false
        assertTrue(JcefSupport.runGuarded { ran = true })
        assertTrue(ran)
    }

    @Test
    fun `runGuarded reports failure when the browser class is missing`() {
        assertFalse(JcefSupport.runGuarded { throw NoClassDefFoundError("com/intellij/ui/jcef/JBCefBrowser") })
    }

    @Test
    fun `runGuarded reports failure when browser setup throws`() {
        assertFalse(JcefSupport.runGuarded { throw IllegalStateException("boom") })
    }

    @Test
    fun `runGuarded does not swallow virtual machine errors`() {
        assertThrows(StackOverflowError::class.java) {
            JcefSupport.runGuarded { throw StackOverflowError() }
        }
    }
}
