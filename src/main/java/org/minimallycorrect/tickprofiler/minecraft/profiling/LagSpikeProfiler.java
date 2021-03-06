package org.minimallycorrect.tickprofiler.minecraft.profiling;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.val;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.minimallycorrect.tickprofiler.Log;
import org.minimallycorrect.tickprofiler.minecraft.commands.Command;
import org.minimallycorrect.tickprofiler.util.CollectionsUtil;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;

public class LagSpikeProfiler {
	private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];
	private static final int lagSpikeMillis = 200;
	private static final long lagSpikeNanoSeconds = TimeUnit.MILLISECONDS.toNanos(lagSpikeMillis);
	private static final boolean ALL_THREADS = Boolean.parseBoolean(System.getProperty("TickProfiler.allThreads", "false"));
	private static boolean inProgress;
	private static volatile long lastTickTime = 0;
	private final ICommandSender commandSender;
	private final long stopTime;
	private boolean detected;

	private LagSpikeProfiler(ICommandSender commandSender, int time_) {
		this.commandSender = commandSender;
		stopTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(time_);
	}

	public static void profile(ICommandSender commandSender, int time_) {
		synchronized (LagSpikeProfiler.class) {
			if (inProgress) {
				Command.sendChat(commandSender, "Lag spike profiling is already in progress");
				return;
			}
			inProgress = true;
		}
		Command.sendChat(commandSender, "Started lag spike detection for " + time_ + " seconds.");
		new LagSpikeProfiler(commandSender, time_).start();
	}

	public static void tick(long nanoTime) {
		lastTickTime = nanoTime;
	}

	private static void printThreadDump(StringBuilder sb) {
		ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
		long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
		if (deadlockedThreads == null) {
			TreeMap<String, String> sortedThreads = sortedThreads(threadMXBean);
			sb.append(CollectionsUtil.join(sortedThreads.values(), "\n"));
		} else {
			ThreadInfo[] infos = threadMXBean.getThreadInfo(deadlockedThreads, true, true);
			sb.append("Definitely deadlocked: \n");
			for (ThreadInfo threadInfo : infos) {
				sb.append(toString(threadInfo, true)).append('\n');
			}
		}
	}

	private static TreeMap<String, String> sortedThreads(ThreadMXBean threadMXBean) {
		LoadingCache<String, List<ThreadInfo>> threads = CacheBuilder.newBuilder().build(new CacheLoader<String, List<ThreadInfo>>() {
			@Override
			public List<ThreadInfo> load(final String key) throws Exception {
				return new ArrayList<>();
			}
		});

		boolean allThreads = ALL_THREADS;

		ThreadInfo[] t = threadMXBean.dumpAllThreads(allThreads, allThreads);
		for (ThreadInfo thread : t) {
			if (!allThreads && !includeThread(thread))
				continue;

			String info = toString(thread, false);
			if (info != null) {
				threads.getUnchecked(info).add(thread);
			}
		}

		TreeMap<String, String> sortedThreads = new TreeMap<>();
		for (Map.Entry<String, List<ThreadInfo>> entry : threads.asMap().entrySet()) {
			List<ThreadInfo> threadInfoList = entry.getValue();
			ThreadInfo lowest = null;
			for (ThreadInfo threadInfo : threadInfoList) {
				if (lowest == null || threadInfo.getThreadName().toLowerCase().compareTo(lowest.getThreadName().toLowerCase()) < 0) {
					lowest = threadInfo;
				}
			}
			val threadNameList = CollectionsUtil.newList(threadInfoList, ThreadInfo::getThreadName);
			Collections.sort(threadNameList);
			if (lowest != null) {
				sortedThreads.put(lowest.getThreadName(), '"' + CollectionsUtil.join(threadNameList, "\", \"") + "\" " + entry.getKey());
			}
		}

		return sortedThreads;
	}

	private static boolean includeThread(ThreadInfo thread) {
		return thread.getThreadName().toLowerCase().startsWith("server thread");
	}

	private static void trySleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ignored) {
		}
	}

	private static String toString(ThreadInfo threadInfo, boolean name) {
		if (threadInfo == null) {
			return null;
		}
		StackTraceElement[] stackTrace = threadInfo.getStackTrace();
		if (stackTrace == null) {
			stackTrace = EMPTY_STACK_TRACE;
		}
		StringBuilder sb = new StringBuilder();
		if (name) {
			sb.append('"').append(threadInfo.getThreadName()).append('"').append(" Id=").append(threadInfo.getThreadId()).append(' ');
		}
		sb.append(threadInfo.getThreadState());
		if (threadInfo.getLockName() != null) {
			sb.append(" on ").append(threadInfo.getLockName());
		}
		if (threadInfo.getLockOwnerName() != null) {
			sb.append(" owned by \"").append(threadInfo.getLockOwnerName()).append("\" Id=").append(threadInfo.getLockOwnerId());
		}
		if (threadInfo.isSuspended()) {
			sb.append(" (suspended)");
		}
		if (threadInfo.isInNative()) {
			sb.append(" (in native)");
		}
		int run = 0;
		sb.append('\n');
		for (int i = 0; i < stackTrace.length; i++) {
			String steString = stackTrace[i].toString();
			if (steString.contains(".run(")) {
				run++;
			}
			sb.append("\tat ").append(steString);
			sb.append('\n');
			if (i == 0 && threadInfo.getLockInfo() != null) {
				Thread.State ts = threadInfo.getThreadState();
				switch (ts) {
					case BLOCKED:
						sb.append("\t-  blocked on ").append(threadInfo.getLockInfo());
						sb.append('\n');
						break;
					case WAITING:
						sb.append("\t-  waiting on ").append(threadInfo.getLockInfo());
						sb.append('\n');
						break;
					case TIMED_WAITING:
						sb.append("\t-  waiting on ").append(threadInfo.getLockInfo());
						sb.append('\n');
						break;
					default:
				}
			}

			for (MonitorInfo mi : threadInfo.getLockedMonitors()) {
				if (mi.getLockedStackDepth() == i) {
					sb.append("\t-  locked ").append(mi);
					sb.append('\n');
				}
			}
		}

		LockInfo[] locks = threadInfo.getLockedSynchronizers();
		if (locks.length > 0) {
			sb.append("\n\tNumber of locked synchronizers = ").append(locks.length);
			sb.append('\n');
			for (LockInfo li : locks) {
				sb.append("\t- ").append(li);
				sb.append('\n');
			}
		}
		sb.append('\n');
		return (run <= 2 && sb.indexOf("at java.util.concurrent.LinkedBlockingQueue.take(") != -1) ? null : sb.toString();
	}

	private void start() {
		final int sleepTime = 1 + Math.min(1000, lagSpikeMillis / 6);
		Thread detectorThread = new Thread(() -> {
			try {
				while (checkForLagSpikes()) {
					trySleep(sleepTime);
				}
				synchronized (LagSpikeProfiler.class) {
					inProgress = false;
					if (commandSender != null)
						Command.sendChat(commandSender, "Lag spike profiling finished.");
				}
			} catch (Throwable t) {
				Log.error("Error detecting lag spikes", t);
			}
		});
		detectorThread.setName("Lag Spike Detector");
		detectorThread.start();
	}

	private boolean checkForLagSpikes() {
		long time = System.nanoTime();

		if (time > stopTime)
			return false;

		long deadTime = time - lastTickTime;
		if (deadTime < lagSpikeNanoSeconds) {
			detected = false;
			return true;
		}

		if (detected)
			return true;

		final MinecraftServer minecraftServer = FMLCommonHandler.instance().getMinecraftServerInstance();
		if (!minecraftServer.isServerRunning() || minecraftServer.isServerStopped()) {
			return false;
		}

		detected = true;
		handleLagSpike(deadTime);
		return true;
	}

	private void handleLagSpike(long deadNanoSeconds) {
		StringBuilder sb = new StringBuilder();
		sb
			.append("The server appears to have ").append("lag spiked.")
			.append("\nLast tick ").append(deadNanoSeconds / 1000000000f).append("s ago.");

		printThreadDump(sb);

		Log.error(sb.toString());

		if (commandSender != null && !(commandSender instanceof DedicatedServer))
			Command.sendChat(commandSender, "Lag spike detected. See console/log for more information.");
		trySleep(15000);
	}
}
