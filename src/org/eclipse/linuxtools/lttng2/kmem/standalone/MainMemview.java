package org.eclipse.linuxtools.lttng2.kmem.standalone;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.eclipse.linuxtools.tmf.core.ctfadaptor.CtfTmfEvent;
import org.eclipse.linuxtools.tmf.core.ctfadaptor.CtfTmfTrace;
import org.eclipse.linuxtools.tmf.core.event.ITmfEvent;
import org.eclipse.linuxtools.tmf.core.request.TmfEventRequest;
import org.eclipse.linuxtools.tmf.core.trace.ITmfTrace;
import org.eclipse.linuxtools.tmf.core.trace.TmfExperiment;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;


public class MainMemview {

	static String gnuplotTemplate = ""
			+ "set term png medium\n"
			+ "set output \"output.png\"\n"
			+ "set key top left\n"
			+ "set autoscale\n"
			+ "set title \"%s\"\n"
			+ "set xlabel \"Time (seconds)\"\n"
			+ "set ylabel \"Bytes\"\n"
			+ "plot \"%s\" using 1:2 with lines lw 2 title \"heap\", "
			+ "\"%s\" using 1:2 with lines lw 2 title \"stack\", "
			+ "\"%s\" using 1:2 with lines lw 2 title \"os\"\n";

	// kernel memory events
	public static final String MM_PAGE_ALLOC = "mm_page_alloc";
	public static final String MM_PAGE_FREE = "mm_page_free";

	// libc-wrapper events
	public static final String LIBC_MALLOC = "ust_libc:malloc";
	public static final String LIBC_FREE = "ust_libc:free";

	// stack pointer register
	public static final String UST_REGS = "stack:entry";

	/* Assume page size of 4 Kio */
	public static int PAGE_SIZE = 4096;

	boolean verbose = false;

	File outputDir;

	public static class Point {
		private final double x;
		private final long y;
		public Point(double x, long y) { this.x = x; this.y = y; }
		public double getX() { return x; }
		public long getY() { return y; }
		@Override
		public String toString() {
			return "(" + x + "," + y + ")";
		}
	}

	public static interface ITmfProcessingUnit {
		public void handleEvent(ITmfEvent event);
	}

	public static class HandleMemoryEvents implements ITmfProcessingUnit {
		public ArrayListMultimap<Long, Point> heap; // (tid, points)
		public ArrayListMultimap<Long, Point> stack; // (tid, points)
		public ArrayListMultimap<Long, Point> physical; // (tid, points)
		public Table<Long, Long, Long> malloc; // (tid, ptr, size)
		public HashMap<Long, Long> tos; // (tid, top_of_stack)
		public HashMap<String, Long> count;
		public Table<String, Long, Long> countPerTid; // (tid, ptr, size)
		long start;

		public HandleMemoryEvents() {
			stack = ArrayListMultimap.create();
			heap = ArrayListMultimap.create();
			physical = ArrayListMultimap.create();
			malloc = HashBasedTable.create();
			count = new HashMap<String, Long>();
			tos = new HashMap<Long, Long>();
			countPerTid = HashBasedTable.create();
			start = -1;
		}

		public double getRelSec(CtfTmfEvent ev) {
			return (double)(ev.getTimestamp().getValue() - start) / 1000000000.0f;
		}

		@Override
		public void handleEvent(ITmfEvent event) {
			if (start == -1)
				start = event.getTimestamp().getValue();
			if (event instanceof CtfTmfEvent) {
				CtfTmfEvent ev = (CtfTmfEvent) event;
				Long cnt = count.get(ev.getEventName());
				if (cnt == null)
					cnt = 0L;
				cnt++;
				count.put(ev.getEventName(), cnt);
				kernelEvent(ev);
				userEvent(ev);
			}
		}

		private void kernelEvent(CtfTmfEvent event) {
			String evName = event.getEventName();
			if (evName.equals(MM_PAGE_ALLOC) || evName.equals(MM_PAGE_FREE)) {
				Long tid = (Long) event.getContent().getField("context._tid").getValue();
				if (tid == null) {
					return;
				}
				incCnt(evName, tid);
				Long val = 0L;
				List<Point> data = physical.get(tid);
				if (!data.isEmpty())
					val = data.get(data.size() - 1).getY();
				if (evName.equals(MM_PAGE_ALLOC))
					val += PAGE_SIZE;
				if (evName.equals(MM_PAGE_FREE))
					val -= PAGE_SIZE;
				physical.put(tid, new Point(getRelSec(event), val));
			}
		}

		private void userEvent(CtfTmfEvent event) {
			String evName = event.getEventName();
			if (evName.equals(LIBC_MALLOC) || evName.equals(LIBC_FREE)) {
				Long tid = (Long) event.getContent().getField("context._vtid").getValue();
				if (tid == null) {
					return;
				}
				incCnt(evName, tid);
				Long val = 0L;
				List<Point> data = heap.get(tid);
				if (!data.isEmpty())
					val = data.get(data.size() - 1).getY();
				Long ptr = (Long) event.getContent().getField("ptr").getValue();
				if (ptr != 0) {
					if (evName.equals(LIBC_MALLOC)) {
						Long size = (Long) event.getContent().getField("size").getValue();
						val += size;
						malloc.put(tid, ptr, size);
					}
					if (evName.equals(LIBC_FREE)) {
						Long size = malloc.remove(tid, ptr);
						if (size != null)
							val -= size;
					}
				}
				heap.put(tid, new Point(getRelSec(event), val));
			} else if (evName.equals(UST_REGS)) {
				Long tid = (Long) event.getContent().getField("context._vtid").getValue();
				if (tid == null) {
					return;
				}
				incCnt(evName, tid);
				Long rsp = (Long) event.getContent().getField("rsp").getValue();
				if (!tos.containsKey(tid)) {
					tos.put(tid, rsp);
				}
				Long val = tos.get(tid) - rsp;
				stack.put(tid,  new Point(getRelSec(event), val));
			}
		}

		private void incCnt(String evName, Long tid) {
			Long cnt = countPerTid.get(evName, tid);
			if (cnt == null)
				cnt = 0L;
			cnt++;
			countPerTid.put(evName, tid, cnt);
		}
	}

	/**
	 * Display memory usage, relates memory allocation in user space with
	 * physical page allocation.
	 *
	 * @param args
	 */
	public static void main(String[] args) {
		MainMemview memview = new MainMemview();
		int ret = 0;
		try {
			memview.execute(args);
		} catch (Exception e) {
			e.printStackTrace();
			ret = 1;
		}
		System.exit(ret);
	}

	private void execute(String[] args) throws ParseException, IOException, InterruptedException {
		/*
		 * Options
		 */
		Options opts = new Options();
		opts.addOption("help", false, "display help");
		opts.addOption("verbose", false, "verbose display");
		opts.addOption("output", true, "output directory");

		CommandLineParser parser = new PosixParser();
		CommandLine cmd = parser.parse(opts, args);

		if (cmd.getArgs().length < 1) {
			usage(opts);
			throw new RuntimeException("Missing the path of traces to analyze");
		}
		String outputOption = new File("./").getAbsolutePath();
		verbose = cmd.hasOption("verbose");
		if (cmd.hasOption("output")) {
			outputOption = cmd.getOptionValue("output");
		}
		outputDir = new File(outputOption);
		if (!outputDir.exists()) {
			outputDir.mkdirs();
		}
		System.out.println("Results will be saved to " + outputDir.getAbsolutePath());

		String root = cmd.getArgs()[0];
		File rootDir = new File(root);
		if (!rootDir.isDirectory()) {
			throw new RuntimeException("Error: specified path is not a directory");
		}

		/*
		 * Perform analysis
		 */
		System.out.println("Trace analysis starting, please wait...");
		CtfTraceFinder finder = new CtfTraceFinder();
		List<CtfTmfTrace> traces = finder.find(root);
		ITmfTrace[] tracesArray = new ITmfTrace[traces.size()];
		tracesArray = traces.toArray(tracesArray);
		TmfExperiment exp = new TmfExperiment(CtfTmfEvent.class, "none", tracesArray);
		System.out.println("Traces loaded: " + exp.getTraces().length);

		// FIXME: Verify that required events and context are present

		final HandleMemoryEvents handler = new HandleMemoryEvents();
		final FileWriter w = new FileWriter(new File(outputDir, "out.events"));
		TmfEventRequest req = new TmfEventRequest(CtfTmfEvent.class) {
			@Override
			public void handleData(ITmfEvent event) {
				handler.handleEvent(event);
				CtfTmfEvent ev = (CtfTmfEvent) event;
				writeEvent(w, ev);
			}

			private void writeEvent(FileWriter w, CtfTmfEvent ev) {
				if (verbose) {
					try {
						w.write(String.format("[%s] %s %s\n",
								ev.getTimestamp().toString(),
								ev.getEventName(),
								ev.getContent().toString()));
						} catch (Exception e) {}
				}

			}
		};
		exp.sendRequest(req);
		req.waitForCompletion();
		w.close();

		/*
		 * Write results
		 */
		if (verbose) {
			for (Long col: handler.countPerTid.columnKeySet()) {
							for (String row: handler.countPerTid.rowKeySet()) {
					System.out.println("  " + row + " : " + handler.countPerTid.get(row, col));
				}
			}
		}

		for (Long tid: handler.heap.keySet()) {
			String physicalData = tid + "_physical.data";
			String heapData = tid + "_heap.data";
			String stackData = tid + "_stack.data";
			saveData(physicalData, handler.physical.get(tid));
			saveData(heapData, handler.heap.get(tid));
			saveData(stackData, handler.stack.get(tid));
			generateGnuplot("data.gnuplot", new String[] { heapData, stackData, physicalData });
		}
		System.out.println("Trace analysis completed successfully");
	}

	private void generateGnuplot(String outFile, String[] params) {
		FileWriter w;
		String title = "Experiment " + outputDir.getName();
		try {
			w = new FileWriter(new File(outputDir, outFile));
			w.write(String.format(gnuplotTemplate, title, params[0], params[1], params[2]));
			w.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void saveData(String fname, List<Point> points) throws IOException {
		FileWriter out = new FileWriter(new File(outputDir, fname));
		for (Point p: points) {
			out.write(String.format(Locale.ENGLISH, "%f %d\n", p.getX(), p.getY()));
		}
		out.close();
	}

	public void usage(Options opts) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("kmem", opts);
	}

}
