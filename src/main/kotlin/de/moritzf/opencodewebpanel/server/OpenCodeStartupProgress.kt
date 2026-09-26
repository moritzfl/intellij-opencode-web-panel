package de.moritzf.opencodewebpanel.server

import java.util.concurrent.TimeUnit

/** Immutable, in-memory progress; reading it on the EDT never touches a file or process. */
data class OpenCodeStartupProgress(
    val stage: String,
    val explanation: String,
    val elapsedMillis: Long,
    val stageElapsedMillis: Long,
    val quietMillis: Long,
    val expectedQuietMillis: Long,
    val recentOutput: List<String>,
) {
    val takingLonger: Boolean get() = stageElapsedMillis > expectedQuietMillis && quietMillis > 30_000L
}

/** Separate from server-generation time, which interrupted-session recovery relies on. */
internal class OpenCodeStartupProgressTracker(private val nanoTime: () -> Long = System::nanoTime) {
    private var attempt = -1L
    private var started = 0L
    private var stageStarted = 0L
    private var lastActivity = 0L
    private var stopped: Long? = null
    private var stage = "Preparing OpenCode…"
    private var explanation = "Waiting for previous server work to finish."
    private var expectedQuietMillis = 60_000L
    private val output = ArrayDeque<String>()

    @Synchronized
    fun begin(id: Long) {
        attempt = id
        started = nanoTime()
        stageStarted = started
        lastActivity = started
        stopped = null
        stage = "Preparing OpenCode…"
        explanation = "Waiting for previous server work to finish."
        expectedQuietMillis = 60_000L
        output.clear()
    }

    @Synchronized
    fun step(id: Long, label: String, detail: String, quietBudgetMillis: Long = 60_000L) {
        if (id != attempt || stopped != null) return
        if (stage != label) {
            stageStarted = nanoTime()
            lastActivity = stageStarted
            append("› $label")
        }
        stage = label
        explanation = detail
        expectedQuietMillis = quietBudgetMillis
    }

    @Synchronized
    fun output(id: Long, line: String) {
        if (id != attempt || stopped != null || line.isBlank()) return
        lastActivity = nanoTime()
        val clean = sanitizeCliOutputLine(line).take(500)
        if (clean.isNotBlank()) append(clean)
    }

    @Synchronized
    fun finish(id: Long) {
        if (id == attempt && stopped == null) stopped = nanoTime()
    }

    @Synchronized
    fun snapshot(): OpenCodeStartupProgress? {
        if (attempt < 0) return null
        val now = stopped ?: nanoTime()
        fun elapsed(since: Long) = TimeUnit.NANOSECONDS.toMillis(now - since).coerceAtLeast(0)
        return OpenCodeStartupProgress(stage, explanation, elapsed(started), elapsed(stageStarted),
            elapsed(lastActivity), expectedQuietMillis, output.toList())
    }

    private fun append(line: String) {
        if (output.lastOrNull() == line) return
        output.addLast(line)
        while (output.size > 40) output.removeFirst()
    }
}

/** Explanations describe actual work, without claiming that a silent process is healthy. */
internal fun startupStageExplanation(stage: String): String = when {
    stage.contains("Creating sandbox", true) || stage.contains("Create sandbox", true) ->
        "Docker may download images and install your kits. First-time setup can take several minutes."
    stage.contains("kit", true) ->
        "Docker is applying the configured tools and environment. Image preparation and package downloads can take several minutes."
    stage.contains("Installing", true) || stage.contains("Upgrading", true) || stage.contains("Downloading", true) ->
        "Downloading and installing OpenCode in the sandbox. Download speed and package setup determine how long this takes."
    stage.contains("mount", true) ->
        "Preparing workspace and session folders. Docker may need to start the virtual machine first."
    stage.contains("MCP", true) ->
        "Waiting for IntelliJ's MCP tools to become available before starting OpenCode."
    stage.contains("daemon", true) || stage.contains("Checking Docker", true) ->
        "Checking the Docker Sandboxes service and virtualization support. This step may produce no output."
    stage.contains("List", true) || stage.contains("Verify sandbox", true) ->
        "Reading sandbox state and verifying this project's sandbox."
    stage.contains("Waiting for OpenCode", true) || stage.contains("port", true) ->
        "Waiting for OpenCode to answer on its local port. The page opens automatically when it is ready."
    stage.contains("Checking OpenCode", true) ->
        "Checking that the configured OpenCode binary is available and can run."
    stage.contains("Stop", true) || stage.contains("Remove", true) ->
        "Finishing the previous process before starting again."
    else -> "OpenCode is loading its configuration and plugins. The page opens automatically when it is ready."
}
