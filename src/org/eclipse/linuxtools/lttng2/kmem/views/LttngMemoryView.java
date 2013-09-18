package org.eclipse.linuxtools.lttng2.kmem.views;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.linuxtools.internal.lttng2.kernel.ui.views.controlflow.ControlFlowEntry;
import org.eclipse.linuxtools.lttng2.kmem.standalone.MainMemview.HandleMemoryEvents;
import org.eclipse.linuxtools.lttng2.kmem.standalone.MainMemview.Point;
import org.eclipse.linuxtools.tmf.core.ctfadaptor.CtfTmfEvent;
import org.eclipse.linuxtools.tmf.core.event.ITmfEvent;
import org.eclipse.linuxtools.tmf.core.request.TmfEventRequest;
import org.eclipse.linuxtools.tmf.core.signal.TmfRangeSynchSignal;
import org.eclipse.linuxtools.tmf.core.signal.TmfSignalHandler;
import org.eclipse.linuxtools.tmf.core.signal.TmfTimeSynchSignal;
import org.eclipse.linuxtools.tmf.core.signal.TmfTraceClosedSignal;
import org.eclipse.linuxtools.tmf.core.signal.TmfTraceSelectedSignal;
import org.eclipse.linuxtools.tmf.core.timestamp.ITmfTimestamp;
import org.eclipse.linuxtools.tmf.core.timestamp.TmfTimeRange;
import org.eclipse.linuxtools.tmf.core.trace.ITmfTrace;
import org.eclipse.linuxtools.tmf.ui.views.TmfView;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.swtchart.Chart;
import org.swtchart.ISeries;
import org.swtchart.ISeries.SeriesType;
import org.swtchart.ISeriesSet;

import com.google.common.collect.ArrayListMultimap;

public class LttngMemoryView extends TmfView {

	/**
	 * The ID of the view as specified by the extension.
	 */
	public static final String ID = "org.eclipse.linuxtools.lttng2.memview.views.LttngMemoryView";

	public static final String SERIES_KERNEL = "kernel";
	public static final String SERIES_LIBC   = "libc";

	private Composite fPane;

	private Chart fChart;

	HashMap<String, ArrayListMultimap<Long, Point>> data;

	protected ISelectionListener selListener = new ISelectionListener() {
		@Override
		public void selectionChanged(IWorkbenchPart part, ISelection selection) {
			if (part != LttngMemoryView.this
					&& selection instanceof IStructuredSelection) {
				Object element = ((IStructuredSelection) selection)
						.getFirstElement();
				if (element instanceof ControlFlowEntry) {
					ControlFlowEntry entry = (ControlFlowEntry) element;
					setCurrentTid(entry.getThreadId());
				}
			}
		}
	};

	private long fCurrentTid;

	private ITmfTrace fTrace;

	private long fCurrentTime;

	private TmfTimeRange fCurrentRange;

	protected Object fMemViewLock = new Object();

	private void setCurrentTid(int threadId) {
		fCurrentTid = threadId;
		System.out.println("setCurrentTid " + threadId);
		updateData();
	}

	/**
	 * The constructor.
	 */

	public LttngMemoryView() {
		this(ID);
	}

	public LttngMemoryView(String viewName) {
		super(viewName);
		data = new HashMap<String, ArrayListMultimap<Long, Point>>();
	}

	/**
	 * This is a callback that will allow us
	 * to create the viewer and initialize it.
	 */
	@Override
	public void createPartControl(Composite parent) {
		getSite().getWorkbenchWindow().getSelectionService()
				.addPostSelectionListener(selListener);

		fPane = new Composite(parent, SWT.NONE);
		fPane.setLayout(new FillLayout());
		fChart = new Chart(fPane, SWT.NONE);
		fChart.getSeriesSet().createSeries(SeriesType.LINE, SERIES_KERNEL);
		fChart.getSeriesSet().createSeries(SeriesType.LINE, SERIES_LIBC);

		// Create the help context id for the viewer's control
		PlatformUI.getWorkbench().getHelpSystem().setHelp(fPane, "org.eclipse.linuxtools.lttng2.memview.viewer");
		//makeActions();
		//hookContextMenu();
		//hookDoubleClickAction();
		//contributeToActionBars();
	}

	private void hookContextMenu() {
		/*
		MenuManager menuMgr = new MenuManager("#PopupMenu");
		menuMgr.setRemoveAllWhenShown(true);
		menuMgr.addMenuListener(new IMenuListener() {
			@Override
			public void menuAboutToShow(IMenuManager manager) {
				LttngMemoryView.this.fillContextMenu(manager);
			}
		});
		Menu menu = menuMgr.createContextMenu(viewer.getControl());
		viewer.getControl().setMenu(menu);
		getSite().registerContextMenu(menuMgr, viewer);
		*/
	}

	private void contributeToActionBars() {
		IActionBars bars = getViewSite().getActionBars();
		fillLocalPullDown(bars.getMenuManager());
		fillLocalToolBar(bars.getToolBarManager());
	}

	private void fillLocalPullDown(IMenuManager manager) {
		//manager.add(action1);
		//manager.add(new Separator());
		//manager.add(action2);
	}

	private void fillContextMenu(IMenuManager manager) {
		//manager.add(action1);
		//manager.add(action2);
		// Other plug-ins can contribute there actions here
		//manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
	}

	private void fillLocalToolBar(IToolBarManager manager) {
		//manager.add(action1);
		//manager.add(action2);
	}

	private void makeActions() {
		/*
		action1 = new Action() {
			@Override
			public void run() {
				showMessage("Action 1 executed");
			}
		};
		action1.setText("Action 1");
		action1.setToolTipText("Action 1 tooltip");
		action1.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().
			getImageDescriptor(ISharedImages.IMG_OBJS_INFO_TSK));

		action2 = new Action() {
			@Override
			public void run() {
				showMessage("Action 2 executed");
			}
		};
		action2.setText("Action 2");
		action2.setToolTipText("Action 2 tooltip");
		action2.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().
				getImageDescriptor(ISharedImages.IMG_OBJS_INFO_TSK));
		doubleClickAction = new Action() {
			@Override
			public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection)selection).getFirstElement();
				showMessage("Double-click detected on "+obj.toString());
			}
		};
		*/
	}

	/**
	 * Passing the focus request to the viewer's control.
	 */
	@Override
	public void setFocus() {
		fPane.setFocus();
	}

	@TmfSignalHandler
	public void traceSelected(final TmfTraceSelectedSignal signal) {
		if (signal.getTrace() == fTrace) {
			return;
		}
		fCurrentTid = 8343;
		fTrace = signal.getTrace();

		Job job = new Job("test") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				System.out.println("Running thread");
				final HandleMemoryEvents handler = new HandleMemoryEvents();
				TmfEventRequest req = new TmfEventRequest(CtfTmfEvent.class) {
					@Override
					public void handleData(ITmfEvent event) {
						handler.handleEvent(event);
					}
				};

				fTrace.sendRequest(req);
				System.out.println("request sent");
				try {
					req.waitForCompletion();
					System.out.println("completed");
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				synchronized (fMemViewLock) {
					data.put(SERIES_KERNEL, handler.physical);
					data.put(SERIES_LIBC, handler.heap);
				}
				updateDataAsync();
				return Status.OK_STATUS;
			}
		};
		job.schedule();
	}

	@TmfSignalHandler
	public void traceClosed(final TmfTraceClosedSignal signal) {
		updateData();
	}

	@TmfSignalHandler
	public void synchToTime(final TmfTimeSynchSignal signal) {
		fCurrentTime = signal.getCurrentTime()
				.normalize(0, ITmfTimestamp.NANOSECOND_SCALE).getValue();
		// updateData();
	}

	@TmfSignalHandler
	public void synchToRange(final TmfRangeSynchSignal signal) {
		fCurrentRange = signal.getCurrentRange();
		// updateData();
	}

	public void updateDataAsync() {
		Display.getDefault().asyncExec(new Runnable() {
			@Override
			public void run() {
				updateData();
			}
		});
	}

	public void updateData() {
		// Runnable runnable = new Runnable() {
		// public void run() {
		// fillSeries(SERIES_KERNEL);
		// fillSeries(SERIES_LIBC);
		//
		// // adjust the axis range
		// fChart.getAxisSet().adjustRange();
		// // fChart.getAxisSet().getYAxis(0).setRange(new Range(0, 4.0));
		// // fChart.getAxisSet().getXAxis(0).setRange(new Range(0, 4.0));
		// fChart.redraw();
		// }
		// };
		// Display.getDefault().asyncExec(runnable);
		//
		fillSeries(SERIES_KERNEL);
		fillSeries(SERIES_LIBC);

		// adjust the axis range
		fChart.getAxisSet().adjustRange();
		// fChart.getAxisSet().getYAxis(0).setRange(new Range(0, 4.0));
		// fChart.getAxisSet().getXAxis(0).setRange(new Range(0, 4.0));
		fChart.redraw();
	}

	public void fillSeries(String name) {

		// clear the chart
		ISeriesSet set = fChart.getSeriesSet();
		ISeries series = set.getSeries(name);


		List<Point> list = new LinkedList<Point>();
		synchronized (fMemViewLock) {
			if (data.containsKey(name)) {
				list = data.get(name).get(fCurrentTid);
			}
		}

		double[] x = new double[list.size()];
		double[] y = new double[list.size()];
		for (int i = 0; i < list.size(); i++) {
			x[i] = list.get(i).getX();
			y[i] = list.get(i).getY();
		}
		series.setXSeries(x);
		series.setYSeries(y);
	}

}
