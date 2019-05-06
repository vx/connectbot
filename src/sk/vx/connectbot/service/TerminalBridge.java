/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sk.vx.connectbot.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sk.vx.connectbot.R;
import sk.vx.connectbot.TerminalView;
import sk.vx.connectbot.bean.HostBean;
import sk.vx.connectbot.bean.PortForwardBean;
import sk.vx.connectbot.bean.SelectionArea;
import sk.vx.connectbot.transport.AbsTransport;
import sk.vx.connectbot.transport.TransportFactory;
import sk.vx.connectbot.util.HostDatabase;
import sk.vx.connectbot.util.PreferenceConstants;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.FontMetrics;
import android.graphics.Typeface;
import android.os.Environment;
import android.text.ClipboardManager;
import android.text.Editable;
import android.text.method.CharacterPickerDialog;
import android.util.FloatMath;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import de.mud.terminal.VDUBuffer;
import de.mud.terminal.VDUDisplay;
import de.mud.terminal.vt320;


/**
 * Provides a bridge between a MUD terminal buffer and a possible TerminalView.
 * This separation allows us to keep the TerminalBridge running in a background
 * service. A TerminalView shares down a bitmap that we can use for rendering
 * when available.
 *
 * This class also provides SSH hostkey verification prompting, and password
 * prompting.
 */
@SuppressWarnings("deprecation") // for ClipboardManager
public class TerminalBridge implements VDUDisplay {
	public final static String TAG = "ConnectBot.TerminalBridge";

	public final static int DEFAULT_FONT_SIZE = 10;
	private final static int FONT_SIZE_STEP = 2;

	public Integer[] color;

	public int defaultFg = HostDatabase.DEFAULT_FG_COLOR;
	public int defaultBg = HostDatabase.DEFAULT_BG_COLOR;

	protected final TerminalManager manager;

	public HostBean host;

	/* package */ AbsTransport transport;

	final Paint defaultPaint;

	private Relay relay;

	private final String emulation;
	private final int scrollback;

	public Bitmap bitmap = null;
	public VDUBuffer buffer = null;

	private TerminalView parent = null;
	private final Canvas canvas = new Canvas();

	private boolean disconnected = false;
	private boolean awaitingClose = false;

	private boolean forcedSize = false;
	private int columns;
	private int rows;

	/* package */ final TerminalKeyListener keyListener;

	private boolean selectingForCopy = false;
	private final SelectionArea selectionArea;

	// TODO add support for the new clipboard API
	private ClipboardManager clipboard;

	public int charWidth = -1;
	public int charHeight = -1;
	private int charTop = -1;

	private float fontSize = -1;

	private final List<FontSizeChangedListener> fontSizeChangedListeners;

	private final List<String> localOutput;

	/**
	 * Flag indicating if we should perform a full-screen redraw during our next
	 * rendering pass.
	 */
	private boolean fullRedraw = false;

	public PromptHelper promptHelper;

	protected BridgeDisconnectedListener disconnectListener = null;

	/**
	 * Create a new terminal bridge suitable for unit testing.
	 */
	public TerminalBridge() {
		buffer = new vt320() {
			@Override
			public void write(byte[] b) {}
			@Override
			public void write(int b) {}
			@Override
			public void sendTelnetCommand(byte cmd) {}
			@Override
			public void setWindowSize(int c, int r) {}
			@Override
			public void debug(String s) {}
		};

		emulation = null;
		manager = null;

		defaultPaint = new Paint();

		selectionArea = new SelectionArea();
		scrollback = 1;

		localOutput = new LinkedList<String>();

		fontSizeChangedListeners = new LinkedList<FontSizeChangedListener>();

		transport = null;

		keyListener = new TerminalKeyListener(manager, this, buffer, null);

	}

	/**
	 * Create new terminal bridge with following parameters. We will immediately
	 * launch thread to start SSH connection and handle any hostkey verification
	 * and password authentication.
	 */
	public TerminalBridge(final TerminalManager manager, final HostBean host) throws IOException {
		float hostFontSize;
		this.manager = manager;
		this.host = host;

		emulation = manager.getEmulation();
		scrollback = manager.getScrollback();

		// create prompt helper to relay password and hostkey requests up to gui
		promptHelper = new PromptHelper(this);

		// create our default paint
		defaultPaint = new Paint();
		defaultPaint.setAntiAlias(true);
		defaultPaint.setTypeface(Typeface.MONOSPACE);
		defaultPaint.setFakeBoldText(true); // more readable?

		localOutput = new LinkedList<String>();

		fontSizeChangedListeners = new LinkedList<FontSizeChangedListener>();

		Integer defaultFontSize = Integer.parseInt(manager.prefs.getString(PreferenceConstants.DEFAULT_FONT_SIZE, "-1"));
		Log.d(TAG, "fontSize: " + this.fontSize + ", defaultFontSize: " + defaultFontSize);
		if (this.fontSize == -1) {
			if (defaultFontSize > 0 && host.getFontSize() == -1)
				hostFontSize = defaultFontSize;
			else
				hostFontSize = host.getFontSize();
		} else
			hostFontSize = this.fontSize;

		if (hostFontSize <= 0)
			hostFontSize = DEFAULT_FONT_SIZE;
		setFontSize(hostFontSize);

		// create terminal buffer and handle outgoing data
		// this is probably status reply information
		buffer = new vt320() {
			@Override
			public void debug(String s) {
				Log.d(TAG, s);
			}

			@Override
			public void write(byte[] b) {
				try {
					if (b != null && transport != null)
						transport.write(b);
				} catch (IOException e) {
					Log.e(TAG, "Problem writing outgoing data in vt320() thread", e);
				}
			}

			@Override
			public void write(int b) {
				try {
					if (transport != null)
						transport.write(b);
				} catch (IOException e) {
					Log.e(TAG, "Problem writing outgoing data in vt320() thread", e);
				}
			}

			// We don't use telnet sequences.
			@Override
			public void sendTelnetCommand(byte cmd) {
			}

			// We don't want remote to resize our window.
			@Override
			public void setWindowSize(int c, int r) {
			}

			@Override
			public void beep() {
				if (parent.isShown())
					manager.playBeep();
				else
					manager.sendActivityNotification(host);
			}
		};

		// Don't keep any scrollback if a session is not being opened.
		if (host.getWantSession())
			buffer.setBufferSize(scrollback);
		else
			buffer.setBufferSize(0);

		resetColors();
		buffer.setDisplay(this);

		selectionArea = new SelectionArea();

		keyListener = new TerminalKeyListener(manager, this, buffer, host.getEncoding());
	}

	public PromptHelper getPromptHelper() {
		return promptHelper;
	}

	/**
	 * Spawn thread to open connection and start login process.
	 */
	protected void startConnection() {
		transport = TransportFactory.getTransport(host.getProtocol());
		transport.setBridge(this);
		transport.setManager(manager);
		transport.setHost(host);

		// TODO make this more abstract so we don't litter on AbsTransport
		transport.setCompression(host.getCompression());
		transport.setHttpproxy(host.getHttpproxy());
		transport.setUseAuthAgent(host.getUseAuthAgent());
		transport.setEmulation(emulation);

		if (transport.canForwardPorts()) {
			for (PortForwardBean portForward : manager.hostdb.getPortForwardsForHost(host))
				transport.addPortForward(portForward);
		}

		outputLine(manager.res.getString(R.string.terminal_connecting, host.getHostname(), host.getPort(), host.getProtocol()));

		Thread connectionThread = new Thread(new Runnable() {
			public void run() {
				transport.connect();
			}
		});
		connectionThread.setName("Connection");
		connectionThread.setDaemon(true);
		connectionThread.start();
	}

	/**
	 * Handle challenges from keyboard-interactive authentication mode.
	 */
	public String[] replyToChallenge(String name, String instruction, int numPrompts, String[] prompt, boolean[] echo) {
		String[] responses = new String[numPrompts];
		for(int i = 0; i < numPrompts; i++) {
			// request response from user for each prompt
			responses[i] = promptHelper.requestPasswordPrompt(instruction, prompt[i]);
		}
		return responses;
	}

	/**
	 * @return charset in use by bridge
	 */
	public Charset getCharset() {
		return relay.getCharset();
	}

	/**
	 * Sets the encoding used by the terminal. If the connection is live,
	 * then the character set is changed for the next read.
	 * @param encoding the canonical name of the character encoding
	 */
	public void setCharset(String encoding) {
		if (relay != null)
			relay.setCharset(encoding);
		keyListener.setCharset(encoding);
	}

	/**
	 * Convenience method for writing a line into the underlying MUD buffer.
	 * Should never be called once the session is established.
	 */
	public final void outputLine(String line) {
		if (transport != null && transport.isSessionOpen())
			Log.e(TAG, "Session established, cannot use outputLine!", new IOException("outputLine call traceback"));

		synchronized (localOutput) {
			final String s = line + "\r\n";

			localOutput.add(s);

			((vt320) buffer).putString(s);

			// For accessibility
			final char[] charArray = s.toCharArray();
			propagateConsoleText(charArray, charArray.length);
		}
	}

	/**
	 * Inject a specific string into this terminal. Used for post-login strings
	 * and pasting clipboard.
	 */
	public void injectString(final String string) {
		if (string == null || string.length() == 0)
			return;

		Thread injectStringThread = new Thread(new Runnable() {
			public void run() {
				try {
					transport.write(string.getBytes(host.getEncoding()));
				} catch (Exception e) {
					Log.e(TAG, "Couldn't inject string to remote host: ", e);
				}
			}
		});
		injectStringThread.setName("InjectString");
		injectStringThread.start();
	}

	/**
	 * Internal method to request actual PTY terminal once we've finished
	 * authentication. If called before authenticated, it will just fail.
	 */
	public void onConnected() {
		disconnected = false;

		((vt320) buffer).reset();

		// We no longer need our local output.
		localOutput.clear();

		// previously tried vt100 and xterm for emulation modes
		// "screen" works the best for color and escape codes
		((vt320) buffer).setAnswerBack(emulation);

		if (HostDatabase.DELKEY_BACKSPACE.equals(host.getDelKey()))
			((vt320) buffer).setBackspace(vt320.DELETE_IS_BACKSPACE);
		else
			((vt320) buffer).setBackspace(vt320.DELETE_IS_DEL);

		// create thread to relay incoming connection data to buffer
		relay = new Relay(this, transport, (vt320) buffer, host.getEncoding());
		Thread relayThread = new Thread(relay);
		relayThread.setDaemon(true);
		relayThread.setName("Relay");
		relayThread.start();

		// force font-size to make sure we resizePTY as needed
		setFontSize(fontSize);

		// finally send any post-login string, if requested
		injectString(host.getPostLogin());
	}

	/**
	 * @return whether a session is open or not
	 */
	public boolean isSessionOpen() {
		if (transport != null)
			return transport.isSessionOpen();
		return false;
	}

	public void setOnDisconnectedListener(BridgeDisconnectedListener disconnectListener) {
		this.disconnectListener = disconnectListener;
	}

	/**
	 * Force disconnection of this terminal bridge.
	 */
	public void dispatchDisconnect(boolean immediate) {
		// We don't need to do this multiple times.
		synchronized (this) {
			if (disconnected && !immediate)
				return;

			disconnected = true;
		}

		// Cancel any pending prompts.
		promptHelper.cancelPrompt();

		// disconnection request hangs if we havent really connected to a host yet
		// temporary fix is to just spawn disconnection into a thread
		Thread disconnectThread = new Thread(new Runnable() {
			public void run() {
				if (transport != null && transport.isConnected())
					transport.close();
			}
		});
		disconnectThread.setName("Disconnect");
		disconnectThread.start();

        if (immediate || (host.getQuickDisconnect() && !host.getStayConnected())) {
			awaitingClose = true;
			if (disconnectListener != null)
				disconnectListener.onDisconnected(TerminalBridge.this);
		} else {
			{
				final String line = manager.res.getString(R.string.alert_disconnect_msg);
				((vt320) buffer).putString("\r\n" + line + "\r\n");
			}
			if (host.getStayConnected()) {
				manager.requestReconnect(this);
				return;
			}
			Thread disconnectPromptThread = new Thread(new Runnable() {
				public void run() {
					Boolean result = promptHelper.requestBooleanPrompt(null,
							manager.res.getString(R.string.prompt_host_disconnected));
					if (result == null || result.booleanValue()) {
						awaitingClose = true;

						// Tell the TerminalManager that we can be destroyed now.
						if (disconnectListener != null)
							disconnectListener.onDisconnected(TerminalBridge.this);
					}
				}
			});
			disconnectPromptThread.setName("DisconnectPrompt");
			disconnectPromptThread.setDaemon(true);
			disconnectPromptThread.start();
		}
	}

	public void setSelectingForCopy(boolean selectingForCopy) {
		this.selectingForCopy = selectingForCopy;
	}

	public boolean isSelectingForCopy() {
		return selectingForCopy;
	}

	public SelectionArea getSelectionArea() {
		return selectionArea;
	}

	public synchronized void tryKeyVibrate() {
		manager.tryKeyVibrate();
	}

	/**
	 * Request a different font size. Will make call to parentChanged() to make
	 * sure we resize PTY if needed.
	 */
	/* package */ final void setFontSize(float size) {
		if (size <= 0.0)
			return;

		defaultPaint.setTextSize(size);
		fontSize = size;

		// read new metrics to get exact pixel dimensions
		FontMetrics fm = defaultPaint.getFontMetrics();
		charTop = (int)FloatMath.ceil(fm.top);

		float[] widths = new float[1];
		defaultPaint.getTextWidths("X", widths);
		charWidth = (int)FloatMath.ceil(widths[0]);
		charHeight = (int)FloatMath.ceil(fm.descent - fm.top);

		// refresh any bitmap with new font size
		if(parent != null)
			parentChanged(parent);

		for (FontSizeChangedListener ofscl : fontSizeChangedListeners)
			ofscl.onFontSizeChanged(size);

		host.setFontSize((int) fontSize);
		manager.hostdb.updateFontSize(host);
	}

	/**
	 * Add an {@link FontSizeChangedListener} to the list of listeners for this
	 * bridge.
	 *
	 * @param listener
	 *            listener to add
	 */
	public void addFontSizeChangedListener(FontSizeChangedListener listener) {
		fontSizeChangedListeners.add(listener);
	}

	/**
	 * Remove an {@link FontSizeChangedListener} from the list of listeners for
	 * this bridge.
	 *
	 * @param listener
	 */
	public void removeFontSizeChangedListener(FontSizeChangedListener listener) {
		fontSizeChangedListeners.remove(listener);
	}

	/**
	 * Something changed in our parent {@link TerminalView}, maybe it's a new
	 * parent, or maybe it's an updated font size. We should recalculate
	 * terminal size information and request a PTY resize.
	 */
	public final synchronized void parentChanged(TerminalView parent) {
		if (manager != null && !manager.isResizeAllowed()) {
			Log.d(TAG, "Resize is not allowed now");
			return;
		}

		this.parent = parent;
		final int width = parent.getWidth();
		final int height = parent.getHeight();

		// Something has gone wrong with our layout; we're 0 width or height!
		if (width <= 0 || height <= 0)
			return;

		clipboard = (ClipboardManager) parent.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
		keyListener.setClipboardManager(clipboard);

		if (!forcedSize) {
			// recalculate buffer size
			int newColumns, newRows;

			newColumns = width / charWidth;
			newRows = height / charHeight;

			// If nothing has changed in the terminal dimensions and not an intial
			// draw then don't blow away scroll regions and such.
			if (newColumns == columns && newRows == rows)
				return;

			columns = newColumns;
			rows = newRows;
		}

		// reallocate new bitmap if needed
		boolean newBitmap = (bitmap == null);
		if(bitmap != null)
			newBitmap = (bitmap.getWidth() != width || bitmap.getHeight() != height);

		if (newBitmap) {
			discardBitmap();
			bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
			canvas.setBitmap(bitmap);
		}

		// clear out any old buffer information
		defaultPaint.setColor(Color.BLACK);
		canvas.drawPaint(defaultPaint);

		// Stroke the border of the terminal if the size is being forced;
		if (forcedSize) {
			int borderX = (columns * charWidth) + 1;
			int borderY = (rows * charHeight) + 1;

			defaultPaint.setColor(Color.GRAY);
			defaultPaint.setStrokeWidth(0.0f);
			if (width >= borderX)
				canvas.drawLine(borderX, 0, borderX, borderY + 1, defaultPaint);
			if (height >= borderY)
				canvas.drawLine(0, borderY, borderX + 1, borderY, defaultPaint);
		}

		try {
			// request a terminal pty resize
			synchronized (buffer) {
				buffer.setScreenSize(columns, rows, true);
			}

			if(transport != null)
				transport.setDimensions(columns, rows, width, height);
		} catch(Exception e) {
			Log.e(TAG, "Problem while trying to resize screen or PTY", e);
		}

		// redraw local output if we don't have a sesson to receive our resize request
		if (transport == null) {
			synchronized (localOutput) {
				((vt320) buffer).reset();

				for (String line : localOutput)
					((vt320) buffer).putString(line);
			}
		}

		// force full redraw with new buffer size
		fullRedraw = true;
		redraw();

		parent.notifyUser(String.format("%d x %d", columns, rows));

		Log.i(TAG, String.format("parentChanged() now width=%d, height=%d", columns, rows));
	}

	/**
	 * Somehow our parent {@link TerminalView} was destroyed. Now we don't need
	 * to redraw anywhere, and we can recycle our internal bitmap.
	 */
	public synchronized void parentDestroyed() {
		parent = null;
		discardBitmap();
	}

	private void discardBitmap() {
		if (bitmap != null)
			bitmap.recycle();
		bitmap = null;
	}

	public void setVDUBuffer(VDUBuffer buffer) {
		this.buffer = buffer;
	}

	public VDUBuffer getVDUBuffer() {
		return buffer;
	}

	public void propagateConsoleText(char[] rawText, int length) {
		if (parent != null) {
			parent.propagateConsoleText(rawText, length);
		}
	}

	public void onDraw() {
		int fg, bg;
		synchronized (buffer) {
			boolean entireDirty = buffer.update[0] || fullRedraw;
			boolean isWideCharacter = false;

			// walk through all lines in the buffer
			for(int l = 0; l < buffer.height; l++) {

				// check if this line is dirty and needs to be repainted
				// also check for entire-buffer dirty flags
				if (!entireDirty && !buffer.update[l + 1]) continue;

				// reset dirty flag for this line
				buffer.update[l + 1] = false;

				// walk through all characters in this line
				for (int c = 0; c < buffer.width; c++) {
					int addr = 0;
					int currAttr = buffer.charAttributes[buffer.windowBase + l][c];

					{
						int fgcolor = defaultFg;

						// check if foreground color attribute is set
						if ((currAttr & VDUBuffer.COLOR_FG) != 0)
							fgcolor = ((currAttr & VDUBuffer.COLOR_FG) >> VDUBuffer.COLOR_FG_SHIFT) - 1;

						if (fgcolor < 8 && (currAttr & VDUBuffer.BOLD) != 0)
							fg = color[fgcolor + 8];
						else
							fg = color[fgcolor];
					}

					// check if background color attribute is set
					if ((currAttr & VDUBuffer.COLOR_BG) != 0)
						bg = color[((currAttr & VDUBuffer.COLOR_BG) >> VDUBuffer.COLOR_BG_SHIFT) - 1];
					else
						bg = color[defaultBg];

					// support character inversion by swapping background and foreground color
					if ((currAttr & VDUBuffer.INVERT) != 0) {
						int swapc = bg;
						bg = fg;
						fg = swapc;
					}

					// set underlined attributes if requested
					defaultPaint.setUnderlineText((currAttr & VDUBuffer.UNDERLINE) != 0);

					isWideCharacter = (currAttr & VDUBuffer.FULLWIDTH) != 0;

					if (isWideCharacter)
						addr++;
					else {
						// determine the amount of continuous characters with the same settings and print them all at once
						while(c + addr < buffer.width
								&& buffer.charAttributes[buffer.windowBase + l][c + addr] == currAttr) {
							addr++;
						}
					}

					// Save the current clip region
					canvas.save(Canvas.CLIP_SAVE_FLAG);

					// clear this dirty area with background color
					defaultPaint.setColor(bg);
					if (isWideCharacter) {
						canvas.clipRect(c * charWidth,
								l * charHeight,
								(c + 2) * charWidth,
								(l + 1) * charHeight);
					} else {
						canvas.clipRect(c * charWidth,
								l * charHeight,
								(c + addr) * charWidth,
								(l + 1) * charHeight);
					}
					canvas.drawPaint(defaultPaint);

					// write the text string starting at 'c' for 'addr' number of characters
					defaultPaint.setColor(fg);
					if((currAttr & VDUBuffer.INVISIBLE) == 0)
						canvas.drawText(buffer.charArray[buffer.windowBase + l], c,
							addr, c * charWidth, (l * charHeight) - charTop,
							defaultPaint);

					// Restore the previous clip region
					canvas.restore();

					// advance to the next text block with different characteristics
					c += addr - 1;
					if (isWideCharacter)
						c++;
				}
			}

			// reset entire-buffer flags
			buffer.update[0] = false;
		}
		fullRedraw = false;
	}

	public void redraw() {
		if (parent != null)
			parent.postInvalidate();
	}

	// We don't have a scroll bar.
	public void updateScrollBar() {
	}

	/**
	 * Resize terminal to fit [rows]x[cols] in screen of size [width]x[height]
	 * @param rows
	 * @param cols
	 * @param width
	 * @param height
	 */
	public synchronized void resizeComputed(int cols, int rows, int width, int height) {
		float size = 8.0f;
		float step = 8.0f;
		float limit = 0.125f;

		int direction;

		while ((direction = fontSizeCompare(size, cols, rows, width, height)) < 0)
			size += step;

		if (direction == 0) {
			Log.d("fontsize", String.format("Found match at %f", size));
			return;
		}

		step /= 2.0f;
		size -= step;

		while ((direction = fontSizeCompare(size, cols, rows, width, height)) != 0
				&& step >= limit) {
			step /= 2.0f;
			if (direction > 0) {
				size -= step;
			} else {
				size += step;
			}
		}

		if (direction > 0)
			size -= step;

		this.columns = cols;
		this.rows = rows;
		forcedSize = true;
		setFontSize(size);
	}

	private int fontSizeCompare(float size, int cols, int rows, int width, int height) {
		// read new metrics to get exact pixel dimensions
		defaultPaint.setTextSize(size);
		FontMetrics fm = defaultPaint.getFontMetrics();

		float[] widths = new float[1];
		defaultPaint.getTextWidths("X", widths);
		int termWidth = (int)widths[0] * cols;
		int termHeight = (int)FloatMath.ceil(fm.descent - fm.top) * rows;

		Log.d("fontsize", String.format("font size %f resulted in %d x %d", size, termWidth, termHeight));

		// Check to see if it fits in resolution specified.
		if (termWidth > width || termHeight > height)
			return 1;

		if (termWidth == width || termHeight == height)
			return 0;

		return -1;
	}

	/**
	 * @return whether underlying transport can forward ports
	 */
	public boolean canFowardPorts() {
		return transport.canForwardPorts();
	}

	/**
	 * Adds the {@link PortForwardBean} to the list.
	 * @param portForward the port forward bean to add
	 * @return true on successful addition
	 */
	public boolean addPortForward(PortForwardBean portForward) {
		return transport.addPortForward(portForward);
	}

	/**
	 * Removes the {@link PortForwardBean} from the list.
	 * @param portForward the port forward bean to remove
	 * @return true on successful removal
	 */
	public boolean removePortForward(PortForwardBean portForward) {
		return transport.removePortForward(portForward);
	}

	/**
	 * @return the list of port forwards
	 */
	public List<PortForwardBean> getPortForwards() {
		return transport.getPortForwards();
	}

	/**
	 * Enables a port forward member. After calling this method, the port forward should
	 * be operational.
	 * @param portForward member of our current port forwards list to enable
	 * @return true on successful port forward setup
	 */
	public boolean enablePortForward(PortForwardBean portForward) {
		if (!transport.isConnected()) {
			Log.i(TAG, "Attempt to enable port forward while not connected");
			return false;
		}

		return transport.enablePortForward(portForward);
	}

	/**
	 * Disables a port forward member. After calling this method, the port forward should
	 * be non-functioning.
	 * @param portForward member of our current port forwards list to enable
	 * @return true on successful port forward tear-down
	 */
	public boolean disablePortForward(PortForwardBean portForward) {
		if (!transport.isConnected()) {
			Log.i(TAG, "Attempt to disable port forward while not connected");
			return false;
		}

		return transport.disablePortForward(portForward);
	}

	/**
	 * @return whether underlying transport can transfer files
	 */
	public boolean canTransferFiles() {
		return transport.canTransferFiles();
	}

	/**
	 * Downloads the specified remote file to the local connectbot folder.
	 * @return true on success, false on failure
	 */
	public boolean downloadFile(String remoteFile, String localFolder) {
		return transport.downloadFile(remoteFile, localFolder);
	}

	/**
	 * Uploads the specified local file to the remote host's default directory.
	 * @return true on success, false on failure
	 */
	public boolean uploadFile(String localFile, String remoteFolder, String remoteFile, String mode) {
		if (mode == null)
			mode = "0600";
		return transport.uploadFile(localFile, remoteFolder, remoteFile, mode);
	}

	/**
	 * @return whether the TerminalBridge should close
	 */
	public boolean isAwaitingClose() {
		return awaitingClose;
	}

	/**
	 * @return whether this connection had started and subsequently disconnected
	 */
	public boolean isDisconnected() {
		return disconnected;
	}

	/* (non-Javadoc)
	 * @see de.mud.terminal.VDUDisplay#setColor(byte, byte, byte, byte)
	 */
	public void setColor(int index, int red, int green, int blue) {
		// Don't allow the system colors to be overwritten for now. May violate specs.
		if (index < color.length && index >= 16)
			color[index] = 0xff000000 | red << 16 | green << 8 | blue;
	}

	public final void resetColors() {
		int[] defaults = manager.hostdb.getDefaultColorsForScheme(HostDatabase.DEFAULT_COLOR_SCHEME);
		defaultFg = defaults[0];
		defaultBg = defaults[1];

		color = manager.hostdb.getColorsForScheme(HostDatabase.DEFAULT_COLOR_SCHEME);
	}

	private static Pattern urlPattern = null;

	/**
	 * @return
	 */
	public List<String> scanForURLs() {
		Set<String> urls = new LinkedHashSet<String>();

		if (urlPattern == null) {
			// based on http://www.ietf.org/rfc/rfc2396.txt
			String scheme = "[A-Za-z][-+.0-9A-Za-z]*";
			String unreserved = "[-._~0-9A-Za-z]";
			String pctEncoded = "%[0-9A-Fa-f]{2}";
			String subDelims = "[!$&'()*+,;:=]";
			String userinfo = "(?:" + unreserved + "|" + pctEncoded + "|" + subDelims + "|:)*";
			String h16 = "[0-9A-Fa-f]{1,4}";
			String decOctet = "(?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])";
			String ipv4address = decOctet + "\\." + decOctet + "\\." + decOctet + "\\." + decOctet;
			String ls32 = "(?:" + h16 + ":" + h16 + "|" + ipv4address + ")";
			String ipv6address = "(?:(?:" + h16 + "){6}" + ls32 + ")";
			String ipvfuture = "v[0-9A-Fa-f]+.(?:" + unreserved + "|" + subDelims + "|:)+";
			String ipLiteral = "\\[(?:" + ipv6address + "|" + ipvfuture + ")\\]";
			String regName = "(?:" + unreserved + "|" + pctEncoded + "|" + subDelims + ")*";
			String host = "(?:" + ipLiteral + "|" + ipv4address + "|" + regName + ")";
			String port = "[0-9]*";
			String authority = "(?:" + userinfo + "@)?" + host + "(?::" + port + ")?";
			String pchar = "(?:" + unreserved + "|" + pctEncoded + "|" + subDelims + "|@)";
			String segment = pchar + "*";
			String pathAbempty = "(?:/" + segment + ")*";
			String segmentNz = pchar + "+";
			String pathAbsolute = "/(?:" + segmentNz + "(?:/" + segment + ")*)?";
			String pathRootless = segmentNz + "(?:/" + segment + ")*";
			String hierPart = "(?://" + authority + pathAbempty + "|" + pathAbsolute + "|" + pathRootless + ")";
			String query = "(?:" + pchar + "|/|\\?)*";
			String fragment = "(?:" + pchar + "|/|\\?)*";
			String uriRegex = scheme + ":" + hierPart + "(?:" + query + ")?(?:#" + fragment + ")?";
			urlPattern = Pattern.compile(uriRegex);
		}

		char[] visibleBuffer = new char[buffer.height * buffer.width];
		for (int l = 0; l < buffer.height; l++)
			System.arraycopy(buffer.charArray[buffer.windowBase + l], 0,
					visibleBuffer, l * buffer.width, buffer.width);

		Matcher urlMatcher = urlPattern.matcher(new String(visibleBuffer));
		while (urlMatcher.find())
			urls.add(urlMatcher.group());

		return (new LinkedList<String>(urls));
	}

	/**
	 * @return
	 */
	public boolean isUsingNetwork() {
		return transport.usesNetwork();
	}

	/**
	 * @return
	 */
	public TerminalKeyListener getKeyHandler() {
		return keyListener;
	}

	/**
	 *
	 */
	public void resetScrollPosition() {
		// if we're in scrollback, scroll to bottom of window on input
		if (buffer.windowBase != buffer.screenBase)
			buffer.setWindowBase(buffer.screenBase);
	}

	/**
	 *
	 */
	public void increaseFontSize() {
		setFontSize(fontSize + FONT_SIZE_STEP);
	}

	/**
	 *
	 */
	public void decreaseFontSize() {
		setFontSize(fontSize - FONT_SIZE_STEP);
	}

	/**
	 * Auto-size window back to default
	 */
	public void resetSize(TerminalView parent) {
		this.forcedSize = false;
		Integer defaultFontSize = Integer.parseInt(manager.prefs.getString(PreferenceConstants.DEFAULT_FONT_SIZE, "-1"));
		if (this.fontSize != -1 && defaultFontSize > 0)
			setFontSize(defaultFontSize);
		else
			setFontSize(DEFAULT_FONT_SIZE);
	}

	/**
	 * Create a screenshot of the current view
	 */
	public void captureScreen() {
			String msg;
			File dir, path;
			boolean success = true;
			Bitmap screenshot = this.bitmap;

			if (manager == null || parent == null || screenshot == null)
				return;

			SimpleDateFormat s = new SimpleDateFormat("yyyyMMdd_HHmmss");
			String date = s.format(new Date());
			String pref_path = manager.prefs.getString(PreferenceConstants.SCREEN_CAPTURE_FOLDER, "");
			File default_path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
			if (pref_path.equals(""))
				dir = default_path;
			else
				dir = new File(pref_path);
			path = new File(dir, "vx-" + date + ".png");
			try {
				dir.mkdirs();
				FileOutputStream out = new FileOutputStream(path);
				screenshot.compress(Bitmap.CompressFormat.PNG, 90, out);
				out.close();
			} catch (Exception e) {
				e.printStackTrace();
				success = false;
			}

			if (success) {
				msg = manager.getResources().getString(R.string.screenshot_saved_as) + " " + path;
				if (manager.prefs.getBoolean(PreferenceConstants.SCREEN_CAPTURE_POPUP,true)) {
					new AlertDialog.Builder(parent.getContext())
					.setTitle(R.string.screenshot_success_title)
					.setMessage(msg)
					.setPositiveButton(R.string.button_close, null)
					.show();
				}
			} else {
				msg = manager.getResources().getString(R.string.screenshot_not_saved_as) + " " + path;
				new AlertDialog.Builder(parent.getContext())
				.setTitle(R.string.screenshot_error_title)
				.setMessage(msg)
				.setNegativeButton(R.string.button_close, null)
				.show();
			}

			return;
	}

	/**
	 * Show change font size dialog
	 */
	public boolean showFontSizeDialog() {

		final String pickerString = "+-";
		CharSequence str = "";
		Editable content = Editable.Factory.getInstance().newEditable(str);

		if (parent == null)
			return false;

		CharacterPickerDialog cpd = new CharacterPickerDialog(parent.getContext(),
				parent, content, pickerString, true) {
			private void changeFontSize(CharSequence result) {
				if (result.equals("+"))
					increaseFontSize();
				else if (result.equals("-"))
					decreaseFontSize();
			}

			@Override
			public void onItemClick(AdapterView p, View v, int pos, long id) {
				final String result = String.valueOf(pickerString.charAt(pos));
				changeFontSize(result);
			}

			@Override
			public void onClick(View v) {
				if (v instanceof Button) {
					final CharSequence result = ((Button) v).getText();
					if (result.equals(""))
						dismiss();
					else
						changeFontSize(result);
				}
			}

			@Override
			public boolean dispatchKeyEvent(KeyEvent event) {
				if (event.getAction() == KeyEvent.ACTION_DOWN) {
					if (event.getKeyCode() == KeyEvent.KEYCODE_BACK)
						dismiss();
					return keyListener.onKey(parent, event.getKeyCode(), event);
				}
				return true;
			}
		};

		cpd.show();
		return true;
	}

	/**
	 * Show arrows dialog
	 */
	public boolean showArrowsDialog() {

		final String pickerString = "←→↑↓TIDE";
		CharSequence str = "";
		Editable content = Editable.Factory.getInstance().newEditable(str);

		if (parent == null)
			return false;

		CharacterPickerDialog cpd = new CharacterPickerDialog(parent.getContext(),
				parent, content, pickerString, true) {
			private void buttonPressed(CharSequence result) {
				if (result.equals("←"))
					((vt320)buffer).keyPressed(vt320.KEY_LEFT, ' ', 0);
				else if (result.equals("→"))
					((vt320)buffer).keyPressed(vt320.KEY_RIGHT, ' ', 0);
				else if (result.equals("↑"))
					((vt320)buffer).keyPressed(vt320.KEY_UP, ' ', 0);
				else if (result.equals("↓"))
					((vt320)buffer).keyPressed(vt320.KEY_DOWN, ' ', 0);
				else if (result.equals("I"))
					((vt320)buffer).keyPressed(vt320.KEY_INSERT, ' ', 0);
				else if (result.equals("D"))
					((vt320)buffer).keyPressed(vt320.KEY_DELETE, ' ', 0);
				else if (result.equals("E"))
					((vt320)buffer).keyTyped(vt320.KEY_ENTER, ' ', 0);
				else if (result.equals("T")) {
					try {
						transport.write(0x09);
					} catch (IOException e) {
						Log.e(TAG, "Problem with the arrowsDialog", e);
					}
				}
			}

			@Override
			public void onItemClick(AdapterView p, View v, int pos, long id) {
				final String result = String.valueOf(pickerString.charAt(pos));
				buttonPressed(result);
			}

			@Override
			public void onClick(View v) {
				if (v instanceof Button) {
					final CharSequence result = ((Button) v).getText();
					if (result.equals(""))
						dismiss();
					else
						buttonPressed(result);
				}
			}

			@Override
			public boolean dispatchKeyEvent(KeyEvent event) {
				if (event.getAction() == KeyEvent.ACTION_DOWN) {
					if (event.getKeyCode() == KeyEvent.KEYCODE_BACK)
						dismiss();
					return keyListener.onKey(parent, event.getKeyCode(), event);
				}
				return true;
			}
		};

		cpd.show();
		return true;
	}


	/**
	 * CTRL dialog
	 */
	private String getCtrlString() {
		final String defaultSet = "ABCDEKLOQRWSTUXZ";
		String set = manager.prefs.getString(PreferenceConstants.CTRL_STRING,defaultSet);
		if (set == null || set.equals("")) {
			set = defaultSet;
		}
		return set;
	}

	public boolean showCtrlDialog() {

		CharSequence str = "";
		Editable content = Editable.Factory.getInstance().newEditable(str);

		if (parent == null)
			return false;

		CharacterPickerDialog cpd = new CharacterPickerDialog(parent.getContext(),
				parent, content, getCtrlString(), true) {
			private void buttonPressed(CharSequence result) {
					int code = result.toString().toUpperCase().charAt(0) - 64;
					if (code > 0 && code < 80) {
						try {
							transport.write(code);
						} catch (IOException e) {
							Log.d(TAG, "Error writing CTRL+" + result.toString().toUpperCase().charAt(0));
						}
					}
					dismiss();
			}

			@Override
			public void onItemClick(AdapterView p, View v, int pos, long id) {
				final String result = String.valueOf(getCtrlString().charAt(pos));
				buttonPressed(result);
			}

			@Override
			public void onClick(View v) {
				if (v instanceof Button) {
					final CharSequence result = ((Button) v).getText();
					if (result.equals(""))
						dismiss();
					else
						buttonPressed(result);
				}
			}

			@Override
			public boolean dispatchKeyEvent(KeyEvent event) {
				if (event.getAction() == KeyEvent.ACTION_DOWN) {
					if (event.getKeyCode() == KeyEvent.KEYCODE_BACK)
						dismiss();
					return keyListener.onKey(parent, event.getKeyCode(), event);
				}
				return true;
			}
		};

		cpd.show();
		return true;
	}

	/**
	 * Function keys dialog
	 */
	public boolean showFKeysDialog() {

		final String pickerString = "1234567890";
		CharSequence str = "";
		Editable content = Editable.Factory.getInstance().newEditable(str);

		if (parent == null)
			return false;

		CharacterPickerDialog cpd = new CharacterPickerDialog(parent.getContext(),
				parent, content, pickerString, true) {
			private void buttonPressed(CharSequence result) {
				int key = 0;
				if (result.equals("1"))
					key = vt320.KEY_F1;
				else if (result.equals("2"))
					key = vt320.KEY_F2;
				else if (result.equals("3"))
					key = vt320.KEY_F3;
				else if (result.equals("4"))
					key = vt320.KEY_F4;
				else if (result.equals("5"))
					key = vt320.KEY_F5;
				else if (result.equals("6"))
					key = vt320.KEY_F6;
				else if (result.equals("7"))
					key = vt320.KEY_F7;
				else if (result.equals("8"))
					key = vt320.KEY_F8;
				else if (result.equals("9"))
					key = vt320.KEY_F9;
				else if (result.equals("0"))
					key = vt320.KEY_F10;
				if (key != 0)
					((vt320) buffer).keyPressed(key, ' ', 0);
				dismiss();
			}

			@Override
			public void onItemClick(AdapterView p, View v, int pos, long id) {
				final String result = String.valueOf(pickerString.charAt(pos));
				buttonPressed(result);
			}

			@Override
			public void onClick(View v) {
				if (v instanceof Button) {
					final CharSequence result = ((Button) v).getText();
					if (result.equals(""))
						dismiss();
					else
						buttonPressed(result);
				}
			}

			@Override
			public boolean dispatchKeyEvent(KeyEvent event) {
				if (event.getAction() == KeyEvent.ACTION_DOWN) {
					if (event.getKeyCode() == KeyEvent.KEYCODE_BACK)
						dismiss();
					return keyListener.onKey(parent, event.getKeyCode(), event);
				}
				return true;
			}
		};

		cpd.show();
		return true;
	}

	private String getPickerString() {
		final String defaultSet = "~\\^()[]{}<>|/:_;,.!@#$%&*?\"'-+=";
		String set = manager.prefs.getString(PreferenceConstants.PICKER_STRING,defaultSet);
		if (set == null || set.equals("")) {
			set = defaultSet;
		}
		return set;
	}

	public boolean showCharPickerDialog() {
		CharSequence str = "";
		Editable content = Editable.Factory.getInstance().newEditable(str);

		if (parent == null || !transport.isAuthenticated())
			return false;

		CharacterPickerDialog cpd = new CharacterPickerDialog(parent.getContext(),
				parent, content, getPickerString(), true) {
			private void writeChar(CharSequence result) {
				try {
					if (transport.isAuthenticated())
						transport.write(result.toString().getBytes(getCharset().name()));
				} catch (IOException e) {
					Log.e(TAG, "Problem with the CharacterPickerDialog", e);
				}
				if (!manager.prefs.getBoolean(PreferenceConstants.PICKER_KEEP_OPEN,false))
						dismiss();
			}

			@Override
			public void onItemClick(AdapterView p, View v, int pos, long id) {
				String result = String.valueOf(getPickerString().charAt(pos));
				writeChar(result);
			}

			@Override
			public void onClick(View v) {
				if (v instanceof Button) {
					CharSequence result = ((Button) v).getText();
					if (result.equals(""))
						dismiss();
					else
						writeChar(result);
				}
			}

			@Override
			public boolean dispatchKeyEvent(KeyEvent event) {
				int keyCode = event.getKeyCode();
				if (event.getAction() == KeyEvent.ACTION_DOWN) {
					// close window if SYM or BACK keys are pressed
					if (keyListener.isSymKey(keyCode) ||
							keyCode == KeyEvent.KEYCODE_BACK) {
						dismiss();
						return true;
					}
//					return keyListener.onKey(parent, event.getKeyCode(), event);
				}
				return super.dispatchKeyEvent(event);
			}
		};

		cpd.show();
		return true;
	}
}
