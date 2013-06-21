package org.eclipse.linuxtools.lttng2.kmem.standalone;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.eclipse.linuxtools.tmf.core.ctfadaptor.CtfTmfEvent;
import org.eclipse.linuxtools.tmf.core.ctfadaptor.CtfTmfTrace;
import org.eclipse.linuxtools.tmf.core.event.ITmfEvent;
import org.eclipse.linuxtools.tmf.core.processing.TmfProcessingUnitAdaptor;
import org.eclipse.linuxtools.tmf.core.request.TmfEventRequest;
import org.eclipse.linuxtools.tmf.core.trace.ITmfTrace;
import org.eclipse.linuxtools.tmf.core.trace.TmfExperiment;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;


public class MainMemview {

	public static final String MM_PAGE_ALLOC = "mm_page_alloc";
	public static final String MM_PAGE_FREE = "mm_page_free";

	public static final String LIBC_MALLOC = "ust_libc:malloc";
	public static final String LIBC_FREE = "ust_libc:free";

	/* Assume page size of 4 Kio */
	public static int PAGE_SIZE = 4096;

	public static class Point {
		private final long x, y;
		public Point(long x, long y) { this.x = x; this.y = y; }
		public long getX() { return x; }
		public long getY() { return y; }
		@Override
		public String toString() {
			return "(" + x + "," + y + ")";
		}
	}

	public static class HandleMemoryEvents extends TmfProcessingUnitAdaptor {
		public ArrayListMultimap<Long, Point> dataUser; // (tid, points)
		public ArrayListMultimap<Long, Point> dataKernel; // (tid, points)
		public Table<Long, Long, Long> malloc; // (tid, ptr, size)
		public HashMap<String, Long> count;
		public Table<String, Long, Long> countPerTid; // (tid, ptr, size)
		@Override
		public void handleEvent(ITmfEvent event) {
			if (dataUser == null || dataKernel == null || malloc == null) {
				dataUser = ArrayListMultimap.create();
				dataKernel = ArrayListMultimap.create();
				malloc = HashBasedTable.create();
				count = new HashMap<String, Long>();
				countPerTid = HashBasedTable.create();
			}
			if (event instanceof CtfTmfEvent) {
				CtfTmfEvent ev = (CtfTmfEvent) event;
				Long cnt = count.get(ev.getEventName());
				if (cnt == null)
					cnt = 0L;
				cnt++;
				count.put(ev.getEventName(), cnt);
				kernelEvent(ev);
				libcEvent(ev);
			}
		}
		private void kernelEvent(CtfTmfEvent event) {
			String evName = event.getEventName();
			if (evName.equals(MM_PAGE_ALLOC) || evName.equals(MM_PAGE_FREE)) {
				Long tid = (Long) event.getContent().getField("context._tid").getValue();
				if (tid == null) {
					return;
				}
				//if (tid == 3)
				//	System.out.println("fuck");
				incCnt(evName, tid);
				Long val = 0L;
				List<Point> data = dataKernel.get(tid);
				if (!data.isEmpty())
					val = data.get(data.size() - 1).getY();
				if (evName.equals(MM_PAGE_ALLOC))
					val += PAGE_SIZE;
				if (evName.equals(MM_PAGE_FREE))
					val -= PAGE_SIZE;
				dataKernel.put(tid, new Point(event.getTimestamp().getValue(), val));
			}
		}

		private void libcEvent(CtfTmfEvent event) {
 			String evName = event.getEventName();
			if (evName.equals(LIBC_MALLOC) || evName.equals(LIBC_FREE)) {
				Long tid = (Long) event.getContent().getField("context._vtid").getValue();
				if (tid == null) {
					return;
				}
				incCnt(evName, tid);
				Long val = 0L;
				List<Point> data = dataUser.get(tid);
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
				dataUser.put(tid, new Point(event.getTimestamp().getValue(), val));
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
	 * @throws ParseException
	 * @throws InterruptedException
	 * @throws IOException
	 */
	public static void main(String[] args) throws ParseException, InterruptedException, IOException {
		Options opts = new Options();
		CommandLineParser parser = new PosixParser();
		CommandLine cmd = parser.parse(opts, args);
		if (cmd.getArgs().length < 1) {
			usage(opts);
			System.exit(1);
		}

		String root = cmd.getArgs()[0];

		CtfTraceFinder finder = new CtfTraceFinder();
		List<CtfTmfTrace> traces = finder.find(root);
		ITmfTrace[] tracesArray = new ITmfTrace[traces.size()];
		tracesArray = traces.toArray(tracesArray);
		TmfExperiment exp = new TmfExperiment(CtfTmfEvent.class, "none", tracesArray);
		System.out.println("Traces loaded: " + exp.getTraces().length);

		final HandleMemoryEvents handler = new HandleMemoryEvents();
		final FileWriter w = new FileWriter(new File("out.events"));
		TmfEventRequest req = new TmfEventRequest(CtfTmfEvent.class) {
			int cnt = 0;
			@Override
			public void handleData(ITmfEvent event) {
				cnt++;
				if (cnt == 455)
					System.out.println("fuck");
				handler.handleEvent(event);
				CtfTmfEvent ev = (CtfTmfEvent) event;
				try {
				w.write(String.format("[%s] %s %s\n",
						ev.getTimestamp().toString(),
						ev.getEventName(),
						ev.getContent().toString()));
				} catch (Exception e) {}
			}
		};
		exp.sendRequest(req);
		req.waitForCompletion();
		w.close();
		for (Long col: handler.countPerTid.columnKeySet()) {
			System.out.println(col);
			for (String row: handler.countPerTid.rowKeySet()) {
				System.out.println("  " + row + " : " + handler.countPerTid.get(row, col));
			}
		}

		for (Long tid: handler.dataUser.keySet()) {
			saveData(tid + "_kmem.data", handler.dataKernel.get(tid));
			saveData(tid + "_libc.data", handler.dataUser.get(tid));
		}

		System.exit(0);
	}

	private static void saveData(String fname, List<Point> points) throws IOException {
		FileWriter out = new FileWriter(new File(fname));
		for (Point p: points) {
			out.write(String.format("%d %d\n", p.getX(), p.getY()));
		}
		out.close();
	}

	public static void usage(Options opts) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("kmem", opts);
	}

}
