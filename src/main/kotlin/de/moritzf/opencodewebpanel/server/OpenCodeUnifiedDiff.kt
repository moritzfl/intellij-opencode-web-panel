package de.moritzf.opencodewebpanel.server

/**
 * Reconstructs the "before" and "after" text of a single file from its unified-diff `patch`
 * string, so the changed regions can be shown in IntelliJ's two-pane diff viewer without a VCS
 * plugin dependency (the platform ships `TextFilePatch` but not a `PatchReader`/`PatchDiffRequest`).
 *
 * Only the hunk bodies are reconstructed — a unified diff carries no content for the unchanged
 * regions between hunks, so the result is a faithful "patch preview" of the changes, not a full
 * copy of the file. Context lines anchor each hunk on both sides, so re-diffing the two texts
 * reproduces the original additions and deletions.
 */
object OpenCodeUnifiedDiff {

    data class Sides(val before: String, val after: String)

    /** Lines that precede the first hunk and describe the file, not its content. */
    private val HEADER_PREFIXES = listOf(
        "diff ", "index ", "--- ", "+++ ", "old mode", "new mode", "similarity ",
        "rename ", "copy ", "deleted file", "new file", "Binary files", "GIT binary patch",
    )

    /**
     * Splits [patch] into the reconstructed before/after hunk text. Returns null when the patch
     * has no textual hunks to show (empty, header-only, or binary).
     */
    fun sides(patch: String?): Sides? {
        if (patch.isNullOrEmpty()) return null
        val before = StringBuilder()
        val after = StringBuilder()
        var inHunk = false
        var sawContent = false
        for (line in patch.split('\n')) {
            when {
                line.startsWith("@@") -> inHunk = true
                !inHunk -> Unit // skip file-header noise before the first hunk
                line.startsWith("\\") -> Unit // "\ No newline at end of file"
                line.startsWith("+") -> {
                    after.appendDiffLine(line.substring(1))
                    sawContent = true
                }
                line.startsWith("-") -> {
                    before.appendDiffLine(line.substring(1))
                    sawContent = true
                }
                line.startsWith(" ") -> {
                    val text = line.substring(1)
                    before.appendDiffLine(text)
                    after.appendDiffLine(text)
                    sawContent = true
                }
                line.isEmpty() -> {
                    // Some producers drop the leading space on an empty context line.
                    before.appendDiffLine("")
                    after.appendDiffLine("")
                    sawContent = true
                }
                // A non-empty line without a diff prefix (e.g. the next file's "diff --git")
                // ends the current file's hunks.
                HEADER_PREFIXES.any { line.startsWith(it) } -> inHunk = false
                else -> inHunk = false
            }
        }
        if (!sawContent) return null
        return Sides(before.toString(), after.toString())
    }

    private fun StringBuilder.appendDiffLine(text: String) {
        if (isNotEmpty()) append('\n')
        append(text)
    }
}
