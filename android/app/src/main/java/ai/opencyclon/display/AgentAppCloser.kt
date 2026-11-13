package ai.opencyclon.display

/**
 * Closes the apps on the hidden display before it is released. Releasing a display moves its
 * tasks to the main screen, so Reject, Stop and expiry close them first (task removal through
 * the privileged backend) instead of moving them.
 */
object AgentAppCloser {
    const val MAX_TASKS = 5

    /**
     * Removes the top non-OpenCyclon task of the hidden display until none is left.
     * Returns the id of a task that could not be removed, or null when the display holds none.
     */
    fun closeAll(topTask: () -> Int?, remove: (Int) -> Boolean): Int? {
        var previous: Int? = null
        repeat(MAX_TASKS) {
            val id = topTask() ?: return null
            if (id == previous || !remove(id)) return id
            previous = id
        }
        return topTask()
    }
}
