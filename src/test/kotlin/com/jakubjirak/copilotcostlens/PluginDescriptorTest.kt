/*
 * Copyright (c) 2026 Jakub Jirák. All rights reserved.
 */
package com.jakubjirak.copilotcostlens

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

@DisplayName("plugin.xml descriptor")
class PluginDescriptorTest {

    private fun parse(resource: String): Element {
        val stream = javaClass.classLoader.getResourceAsStream(resource)
        assertNotNull(stream, "$resource not found on classpath")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return stream!!.use { factory.newDocumentBuilder().parse(it).documentElement }
    }

    private fun dependsElements(root: Element): List<Element> =
        root.getElementsByTagName("depends").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }

    private fun jcefDependency(): Element =
        dependsElements(parse("META-INF/plugin.xml"))
            .single { it.textContent.trim() == "com.intellij.modules.jcef" }

    /**
     * Regression guard: IntelliJ 2026.2 moved JCEF out of the core platform into the bundled
     * `com.intellij.modules.jcef` plugin. Without this dependency the plugin classloader cannot
     * resolve `JBCefApp` and the dashboard fails with `NoClassDefFoundError`.
     */
    @Test
    fun `declares the JCEF module`() {
        assertEquals("com.intellij.modules.jcef", jcefDependency().textContent.trim())
    }

    /**
     * The id does not exist before 2026.2 and this plugin's since-build is 243, so a mandatory
     * dependency would make it unloadable on every supported IDE below 2026.2.
     */
    @Test
    fun `declares the JCEF module as optional`() {
        assertEquals("true", jcefDependency().getAttribute("optional"))
    }

    @Test
    fun `the optional JCEF config file exists and is a descriptor`() {
        val configFile = jcefDependency().getAttribute("config-file")
        assertTrue(configFile.isNotBlank(), "optional dependency needs a config-file attribute")
        assertEquals("idea-plugin", parse("META-INF/$configFile").tagName)
    }

    @Test
    fun `still depends on the platform module`() {
        val platform = dependsElements(parse("META-INF/plugin.xml"))
            .single { it.textContent.trim() == "com.intellij.modules.platform" }
        assertEquals("", platform.getAttribute("optional"), "platform dependency is mandatory")
    }
}
