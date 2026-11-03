package com.nisovin.shopkeepers.util.bukkit;

/**
 * A handle to a task that has been scheduled via {@link SchedulerUtils}.
 * <p>
 * Depending on the server, the task is either run by the Bukkit scheduler, or by one of Paper's
 * region-aware schedulers.
 */
public interface TaskHandle {

	/**
	 * Cancels the task.
	 * <p>
	 * If the task is currently running, it finishes its current execution.
	 */
	public void cancel();

	/**
	 * Checks if the task has been cancelled.
	 *
	 * @return <code>true</code> if cancelled
	 */
	public boolean isCancelled();
}
