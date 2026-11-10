package com.androidharness.app.ui.settings

import com.androidharness.app.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Resolve the shipped resources without depending on Android's stubbed JVM Context. */
internal fun settingsTestStrings(locale: String = "values"): (Int) -> String {
    val byName = buildMap {
        // Android resolves resources from every XML file, with English fallback.
        listOf("values", locale).distinct().forEach { directory ->
            File("src/main/res/$directory").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val strings = document.getElementsByTagName("string")
                for (index in 0 until strings.length) {
                    val element = strings.item(index) as org.w3c.dom.Element
                    put(element.getAttribute("name"), element.textContent.replace("\\'", "'"))
                }
            }
        }
    }
    val byId = R.string::class.java.fields.mapNotNull { field ->
        byName[field.name]?.let { field.getInt(null) to it }
    }.toMap()
    return { id -> requireNotNull(byId[id]) { "Missing string resource $id in $locale" } }
}
