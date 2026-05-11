package com.archivesaver.android

object ArchiveJobStore {
    data class Job(
        val id: String,
        val url: String,
        val collectionTitle: String,
        val status: String,
        val progress: Int,
        val isFinished: Boolean = false,
        val isFailed: Boolean = false
    )

    fun interface Listener {
        fun onJobsChanged(jobs: List<Job>)
    }

    private val jobs = linkedMapOf<String, Job>()
    private val listeners = mutableSetOf<Listener>()

    @Synchronized
    fun upsert(job: Job) {
        jobs[job.id] = job
        notifyListenersLocked()
    }

    @Synchronized
    fun update(id: String, status: String, progress: Int, isFinished: Boolean = false, isFailed: Boolean = false) {
        val current = jobs[id] ?: return
        jobs[id] = current.copy(
            status = status,
            progress = progress.coerceIn(0, 100),
            isFinished = isFinished,
            isFailed = isFailed
        )
        notifyListenersLocked()
    }

    @Synchronized
    fun snapshot(): List<Job> = jobs.values.toList()

    @Synchronized
    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onJobsChanged(jobs.values.toList())
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun clearFinished() {
        jobs.entries.removeAll { it.value.isFinished }
        notifyListenersLocked()
    }

    private fun notifyListenersLocked() {
        val snapshot = jobs.values.toList()
        listeners.forEach { it.onJobsChanged(snapshot) }
    }
}
