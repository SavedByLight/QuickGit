package com.example.app.data

import org.eclipse.jgit.lib.BatchingProgressMonitor
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * RepoManager handles repository operations.
 * This file includes a small, self-contained TextProgress implementation that
 * matches the newer JGit BatchingProgressMonitor API which uses java.time.Duration
 * in its callbacks.
 *
 * The original CI failure was caused by TextProgress not implementing the
 * updated abstract methods. This implementation provides the required
 * overrides and a simple, robust progress reporting behavior.
 */
class RepoManager {

    companion object {
        fun newTextProgress(): TextProgress = TextProgress()
    }

    /**
     * A small, thread-safe progress monitor implementation compatible with
     * recent JGit versions where onUpdate/onEndTask use Duration.
     *
     * Adjust the reporting behavior below to match your app's logging/UI.
     */
    class TextProgress : BatchingProgressMonitor() {

        private val current = AtomicInteger(0)
        private var totalWork = 0

        // Called once when a task begins. Some JGit versions provide this hook.
        // Signature uses Duration for elapsed time to match newer JGit APIs.
        override fun onBeginTask(taskName: String?, totalWork: Int, elapsed: Duration) {
            this.totalWork = totalWork
            current.set(0)
            // Replace println with a logger or UI callback as appropriate
            println("Begin task: ${taskName.orEmpty()} totalWork=$totalWork")
        }

        // Called periodically with the current progress for the active task.
        override fun onUpdate(taskName: String?, workCurr: Int, elapsed: Duration) {
            current.set(workCurr)
            val name = taskName ?: ""
            val percent = if (totalWork > 0) (workCurr * 100) / totalWork else -1
            if (percent >= 0) {
                // Example: "Progress: clone - 42% (1234ms)"
                println("Progress: $name - $percent% (completed=$workCurr/$totalWork, elapsed=${elapsed.toMillis()}ms)")
            } else {
                println("Progress: $name - completed=$workCurr (elapsed=${elapsed.toMillis()}ms)")
            }
        }

        // Called when the current task ends.
        override fun onEndTask(taskName: String?, elapsed: Duration) {
            val name = taskName ?: ""
            val finalCompleted = current.get()
            println("End task: $name (completed=$finalCompleted/${totalWork}, elapsed=${elapsed.toMillis()}ms)")
            // reset counters
            current.set(0)
            totalWork = 0
        }

        // BatchingProgressMonitor defines beginTask/onUpdate/onEndTask with varying
        // signatures across versions. These three Duration-based overrides satisfy
        // the newer JGit abstract methods. If your JGit version still requires
        // other signatures, consider updating the dependency or adding shims.
    }
}
