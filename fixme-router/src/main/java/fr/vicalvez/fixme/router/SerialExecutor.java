package fr.vicalvez.fixme.router;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/** Executes tasks from one connection in submission order on a shared pool. */
final class SerialExecutor implements Executor {

	private final Executor delegate;
	private final Queue<Runnable> tasks = new ArrayDeque<>();
	private Runnable active;

	SerialExecutor(Executor delegate) {
		this.delegate = delegate;
	}

	@Override
	public synchronized void execute(Runnable command) {
		tasks.offer(() -> {
			try {
				command.run();
			} finally {
				scheduleNext();
			}
		});
		if (active == null) {
			scheduleNext();
		}
	}

	private synchronized void scheduleNext() {
		active = tasks.poll();
		if (active != null) {
			delegate.execute(active);
		}
	}
}
