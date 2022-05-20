import java.net.*;
import java.io.*;
import java.util.*;
import env.java.io.RandomAccessFile;

public class Downloader extends Thread {
	final static int STATE_OK = -2;
	final static int BUF_SIZE = 1;
	final static int DL_THREADS = 1;
	static Downloader main = new Downloader();
	// singleton instance that starts initial download and gets file size
	static URL url;
	static String filename;
	static String outputFileName;
	static RandomAccessFile output;
	int port = 8080;
	boolean ok = false;
	int start = 0;
	int end = Integer.MAX_VALUE;
	Socket s = null;
	PrintWriter pw = null;
	InputStream is = null;
	static boolean main_finished = false;
	static boolean firstChunkOK = false;
	static int firstWorkerStart = Integer.MAX_VALUE;
	static int downloaders = DL_THREADS;

	public static Downloader getMain() {
		return main;
	}

	private Downloader() {
	}

	public void die(String msg, Exception e) {
		System.err.println(msg + ": " + e);
		e.printStackTrace();
		System.exit(1);
	}

	public void configure (String url_str) {
		try {
			url = new URL(url_str);
		} catch (MalformedURLException e) {
			die("Malformed URL: " + url_str, e);
		}
		filename = url.getFile();
		if (filename.equals("")) {
			filename = "/";
		}
		try {
			outputFileName = URLDecoder.decode(filename, "UTF-8").substring(filename.indexOf("/") + 1);
			output = new RandomAccessFile(new File(outputFileName), "rw");
		} catch (IOException e) {
			die("Cannot open file", e);
		}
	}

	public Downloader(int start, int end) {
		this.start = start;
		this.end = end;
	}

	public void download() {
		int length = connect();
		launchDL(length);
		getData(length);
		try {
			synchronized (output) {
				output.close();
			}
		} catch (IOException e) {
			die("Error closing file", e);
		}
	}

	public int connect() {
		int length = 0;
		try {
			s = new Socket(url.getHost(), port);
			pw = new PrintWriter(s.getOutputStream(), true);
			is = s.getInputStream();
		} catch (IOException e) {
			die ("No connection to " + url.getHost() + ", port " + port, e);
		}
//		pw.println("GET " + filename + " HTTP/1.0");
//		if (start != 0) { // worker starting from within the payload
			pw.println("Range: bytes=" + start + "-" + end);
//		}
//		pw.println();

		while (true) {
			String line = null;
			try {
				line = readLine(is);
			} catch (IOException e) {
				die("Cannot read from server", e);
			}
			if (line == null || line.equals("")) {
				break;
			}
			int r = parse(line);
			if (r > 0) {
				length = r;
			} else if (r == STATE_OK) {	
				ok = true;
			}
		}
		return length;
	}

	public void getData(int length) {
		if (length != 0 && end > length) { // known length, adjust "end"
			end = length;
		}
		getData(is, start, end, true);
		synchronized(output){
			main_finished = true;
			System.out.println("Main download finished.");
		}
	}

	public void launchDL(int length) {
		if (downloaders > length) {
			downloaders = length;
		}
		int end = length;
		int chunkSize = length / (downloaders + 1);
		int start = 0;
		int nDownloaders = downloaders; // thread-safe copy
		for (int i = 0; i < nDownloaders; i++) {
			start = end - chunkSize;
			new Downloader(start, end).start();
			end = end - chunkSize;
		}
		firstWorkerStart = start;
	}

	public void run() {
		System.out.println("Worker: Download from " + start + " to " + end);
		connect();
		getData(is, start, end, false);
		synchronized (output) {
			downloaders--;
		}
		if (downloaders == 0) { // workers finished
			System.out.println("Workers finished.");
			synchronized (output) {
				if (firstChunkOK && !main_finished) {
					try {
						output.close();
					} catch (IOException e) {
						die("Error closing file", e);
					}
				}
			}
		}
	}
	public String readLine(InputStream is) throws IOException {
		StringBuilder s = new StringBuilder();
		char ch = '\0';

		while(ch != '\n') {
			int r = is.read();
			if (r == -1) {
				return s.toString();
			}
			ch = (char) r;
			if (ch == '\r') {
				continue;
			} else if (ch != '\n') {
				s.append(ch);
			}
		}
		return s.toString();
	}

	public int parse(String line) {
		if (line.startsWith("HTTP/1")) {
			return STATE_OK;
		} else if (line.startsWith("Content-Length:")) {
			String len = line.substring(line.indexOf(" ") + 1);
			return Integer.parseInt(len);
		}
		return 0;
	}

	public void getData(InputStream is, int start, int end, boolean isMain) {
		byte[] buffer = new byte[BUF_SIZE];
		int pos = start;
		int toRead = buffer.length;
		int r = 0;
		try {
			while (pos < end && r != -1) {
				if (pos + toRead > end) {
					toRead = end - pos;
				}
				r = is.read(buffer, 0, toRead);
				// TODO: check if already d/l'ed
				if (r != -1) {
					synchronized (output) {
						if (main_finished ||
						    (downloaders == 0 && firstChunkOK)) {
							return; // download done
						}
						writeBuf(pos, r, buffer);
					}
					pos += r;
					synchronized (output) {
						if (isMain && pos >= firstWorkerStart) {
							firstChunkOK = true;
						}
					}
				}
			}
			pw.close();
			is.close();
			s.close();
		} catch (IOException e) {
			System.out.println("Cannot read from server: " + e);
			// return, as other threads may still succeed
		}
	}

	public void writeBuf(int pos, int len, byte[] buffer) {
		try {
			synchronized (output) {
				output.seek(pos);
				output.write(buffer, 0, len);
			}
		} catch (IOException e) {
			die ("Cannot write to file", e);
		}
	}
}
