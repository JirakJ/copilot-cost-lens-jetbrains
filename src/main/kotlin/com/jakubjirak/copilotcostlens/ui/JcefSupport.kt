/*
 * Copyright (c) 2026 Jakub Jirák. All rights reserved.
 */
package com.jakubjirak.copilotcostlens.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefApp

/**
 * The only place that touches JCEF classes without a net.
 *
 * JCEF is not guaranteed to be present at runtime:
 *
 * * the IDE may run on a JDK without the JetBrains Runtime browser bits;
 * * the user may have disabled the embedded browser;
 * * since IntelliJ 2026.2 (build 262) JCEF is no longer part of the core platform but lives in the
 *   separate bundled plugin `com.intellij.modules.jcef`, so a plugin that does not depend on it
 *   cannot resolve [JBCefApp] at all.
 *
 * The last case surfaces as [NoClassDefFoundError], which is an [Error] rather than an [Exception],
 * so guards that catch only [Exception] let it escape. Everything here catches [LinkageError] too;
 * [VirtualMachineError] and control-flow throwables are deliberately left alone.
 */
internal object JcefSupport {

    private val log = Logger.getInstance(JcefSupport::class.java)

    /**
     * Returns `true` when an embedded browser can be created.
     *
     * @param probe the availability check, overridable in tests
     */
    fun isAvailable(probe: () -> Boolean = { JBCefApp.isSupported() }): Boolean =
        try {
            probe()
        } catch (e: LinkageError) {
            log.warn("JCEF is unavailable in this IDE; the dashboard will fall back to a message", e)
            false
        } catch (e: Exception) {
            log.warn("JCEF availability check failed; the dashboard will fall back to a message", e)
            false
        }

    /**
     * Runs [block] and returns `false` instead of propagating a missing or broken JCEF runtime.
     *
     * @param block the JCEF-touching code to execute
     * @return `true` when [block] completed, `false` when JCEF let us down
     */
    fun runGuarded(block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: LinkageError) {
            log.warn("JCEF is unavailable in this IDE; the dashboard will fall back to a message", e)
            false
        } catch (e: Exception) {
            log.warn("Failed to initialize the JCEF browser; the dashboard will fall back to a message", e)
            false
        }
}
