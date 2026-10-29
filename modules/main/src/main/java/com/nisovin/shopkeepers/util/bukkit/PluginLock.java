package com.nisovin.shopkeepers.util.bukkit;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The lock that serializes all synchronous plugin logic.
 * <p>
 * The plugin's state (shopkeeper registry, chunk activation, ticking, spawning, UI sessions,
 * storage, ...) was designed to only ever be accessed from the server's main thread. On servers
 * with regionized multithreading (Folia), event handlers and scheduled tasks run on several region
 * threads concurrently. Every synchronous entry point into the plugin (event handlers, commands
 * and scheduled tasks) therefore holds this lock while it runs, so that the plugin state is still
 * only accessed by one thread at a time. On servers with a single main thread, this lock is never
 * contended.
 * <p>
 * The lock is reentrant. Code holding this lock must never block on another region thread.
 * Asynchronous tasks do not acquire this lock.
 */
public final class PluginLock {

	private static final ReentrantLock LOCK = new ReentrantLock();

	public static void lock() {
		LOCK.lock();
	}

	public static void unlock() {
		LOCK.unlock();
	}

	public static boolean isHeldByCurrentThread() {
		return LOCK.isHeldByCurrentThread();
	}

	/**
	 * Runs the given task while holding the lock.
	 *
	 * @param task
	 *            the task
	 */
	public static void run(Runnable task) {
		LOCK.lock();
		try {
			task.run();
		} finally {
			LOCK.unlock();
		}
	}

	/**
	 * Calls the given supplier while holding the lock.
	 *
	 * @param <T>
	 *            the result type
	 * @param supplier
	 *            the supplier
	 * @return the result
	 */
	public static <T> T call(Supplier<T> supplier) {
		LOCK.lock();
		try {
			return supplier.get();
		} finally {
			LOCK.unlock();
		}
	}

	private PluginLock() {
	}
}
