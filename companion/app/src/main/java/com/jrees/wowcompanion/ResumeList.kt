package com.jrees.wowcompanion

import java.io.File

/**
 * The parts of an art step finished for one game build, kept in [file] across a restart (issue #11, 1.0.9: "stuck on 68% EPL,
 * then reopened stuck on 8% EPL": a reopened app made every world map again). One part per line, appended as each finishes; the
 * harvest deletes the file when the step is stamped done. Pure file work: the tests drive it.
 */
class ResumeList(private val file: File) {
    private val parts: MutableSet<String> by lazy {
        runCatching { file.readLines().filter { it.isNotBlank() }.toMutableSet() }.getOrDefault(HashSet())
    }

    @Synchronized fun finished(part: String): Boolean = part in parts

    @Synchronized fun markFinished(part: String) {
        if (!parts.add(part)) return
        runCatching { file.parentFile?.mkdirs(); file.appendText(part + "\n") }
    }
}
