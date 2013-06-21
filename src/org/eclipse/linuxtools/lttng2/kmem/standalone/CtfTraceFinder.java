package org.eclipse.linuxtools.lttng2.kmem.standalone;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.eclipse.linuxtools.tmf.core.ctfadaptor.CtfTmfEvent;
import org.eclipse.linuxtools.tmf.core.ctfadaptor.CtfTmfTrace;
import org.eclipse.linuxtools.tmf.core.exceptions.TmfTraceException;


public class CtfTraceFinder {

	public List<CtfTmfTrace> find(String path) {
		ArrayList<CtfTmfTrace> traces = new ArrayList<CtfTmfTrace>();
		Stack<File> stack  = new Stack<File>();
		stack.push(new File(path));
		while(!stack.isEmpty()) {
			File file = stack.pop();
			CtfTmfTrace ctf = new CtfTmfTrace();
	        try {
				ctf.initTrace(null, file.getAbsolutePath(), CtfTmfEvent.class);
				traces.add(ctf);
			} catch (TmfTraceException e) {
			}
			for (File child: file.listFiles()) {
				if (child.isDirectory())
					stack.add(child);
			}
		}
		return traces;
	}

}
