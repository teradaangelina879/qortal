package org.qortal.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class ExecuteProduceConsume implements Runnable {

	private final String className;
	private final Logger logger;

	private ExecutorService executor;

	// These are volatile to prevent thread-local caching of values
	// but all are updated inside synchronized blocks
	// so we don't need AtomicInteger/AtomicBoolean

	private volatile int activeThreadCount = 0;
	private volatile int greatestActiveThreadCount = 0;
	private volatile int consumerCount = 0;
	private volatile int tasksProduced = 0;
	private volatile int tasksConsumed = 0;

	private volatile boolean hasThreadPending = false;

	public ExecuteProduceConsume(ExecutorService executor) {
		this.className = this.getClass().getSimpleName();
		this.logger = LogManager.getLogger(this.getClass());

		this.executor = executor;
	}

	public ExecuteProduceConsume() {
		this(Executors.newCachedThreadPool());
	}

	public void start() {
		this.executor.execute(this);
	}

	public void shutdown() {
		this.executor.shutdownNow();
	}

	public boolean shutdown(long timeout) throws InterruptedException {
		this.executor.shutdownNow();
		return this.executor.awaitTermination(timeout, TimeUnit.MILLISECONDS);
	}

	public int getActiveThreadCount() {
		synchronized (this) {
			return this.activeThreadCount;
		}
	}

	public int getGreatestActiveThreadCount() {
		synchronized (this) {
			return this.greatestActiveThreadCount;
		}
	}

	public int getTasksProduced() {
		synchronized (this) {
			return this.tasksProduced;
		}
	}

	public int getTasksConsumed() {
		synchronized (this) {
			return this.tasksConsumed;
		}
	}

	/**
	 * Returns a Task to be performed, possibly blocking.
	 * 
	 * @param canBlock
	 * @return task to be performed, or null if no task pending.
	 * @throws InterruptedException
	 */
	protected abstract Task produceTask(boolean canBlock) throws InterruptedException;

	@FunctionalInterface
	public interface Task {
		public abstract void perform() throws InterruptedException;
	}

	@Override
	public void run() {
		Thread.currentThread().setName(this.className + "-" + Thread.currentThread().getId());

		boolean wasThreadPending;
		synchronized (this) {
			++this.activeThreadCount;
			if (this.activeThreadCount > this.greatestActiveThreadCount)
				this.greatestActiveThreadCount = this.activeThreadCount;

			this.logger.trace(() -> String.format("[%d] started, hasThreadPending was: %b, activeThreadCount now: %d",
					Thread.currentThread().getId(), this.hasThreadPending, this.activeThreadCount));

			// Defer clearing hasThreadPending to prevent unnecessary threads waiting to produce...
			wasThreadPending = this.hasThreadPending;
		}

		try {
			// It's possible this might need to become a class instance private volatile
			boolean canBlock = false;

			while (true) {
				final Task task;

				this.logger.trace(() -> String.format("[%d] waiting to produce...", Thread.currentThread().getId()));

				synchronized (this) {
					if (wasThreadPending) {
						// Clear thread-pending flag now that we about to produce.
						this.hasThreadPending = false;
						wasThreadPending = false;
					}

					final boolean lambdaCanIdle = canBlock;
					this.logger.trace(() -> String.format("[%d] producing, activeThreadCount: %d, consumerCount: %d, canBlock is %b...",
							Thread.currentThread().getId(), this.activeThreadCount, this.consumerCount, lambdaCanIdle));

					final long now = System.currentTimeMillis();
					task = produceTask(canBlock);
					final long delay = System.currentTimeMillis() - now;
					this.logger.trace(() -> String.format("[%d] producing took %dms", Thread.currentThread().getId(), delay));
				}

				if (task == null)
					synchronized (this) {
						this.logger.trace(() -> String.format("[%d] no task, activeThreadCount: %d, consumerCount: %d",
								Thread.currentThread().getId(), this.activeThreadCount, this.consumerCount));

						if (this.activeThreadCount > this.consumerCount + 1) {
							--this.activeThreadCount;
							this.logger.trace(() -> String.format("[%d] ending, activeThreadCount now: %d",
									Thread.currentThread().getId(), this.activeThreadCount));
							break;
						}

						// We're the last surviving thread - producer can afford to block next round
						canBlock = true;

						continue;
					}

				// We have a task

				synchronized (this) {
					++this.tasksProduced;
					++this.consumerCount;

					this.logger.trace(() -> String.format("[%d] hasThreadPending: %b, activeThreadCount: %d, consumerCount now: %d",
							Thread.currentThread().getId(), this.hasThreadPending, this.activeThreadCount, this.consumerCount));

					// If we have no thread pending and no excess of threads then we should spawn a fresh thread
					if (!this.hasThreadPending && this.activeThreadCount <= this.consumerCount + 1) {
						this.logger.trace(() -> String.format("[%d] spawning another thread", Thread.currentThread().getId()));
						this.hasThreadPending = true;

						try {
							this.executor.execute(this); // Same object, different thread
						} catch (RejectedExecutionException e) {
							this.hasThreadPending = false;
							this.logger.trace(() -> String.format("[%d] failed to spawn another thread", Thread.currentThread().getId()));
						}
					} else {
						this.logger.trace(() -> String.format("[%d] NOT spawning another thread", Thread.currentThread().getId()));
					}
				}

				this.logger.trace(() -> String.format("[%d] performing task...", Thread.currentThread().getId()));
				task.perform(); // This can block for a while
				this.logger.trace(() -> String.format("[%d] finished task", Thread.currentThread().getId()));

				synchronized (this) {
					++this.tasksConsumed;
					--this.consumerCount;

					this.logger.trace(() -> String.format("[%d] consumerCount now: %d",
							Thread.currentThread().getId(), this.consumerCount));

					// Quicker, non-blocking produce next round
					canBlock = false;
				}
			}
		} catch (InterruptedException e) {
			// We're in shutdown situation so exit
		} finally {
			Thread.currentThread().setName(this.className + "-dormant");
		}
	}

}
