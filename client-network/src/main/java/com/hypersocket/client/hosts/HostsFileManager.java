/*******************************************************************************
 * Copyright (c) 2013 Hypersocket Limited.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 ******************************************************************************/
package com.hypersocket.client.hosts;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hypersocket.client.util.BashSilentSudoCommand;
import com.hypersocket.client.util.CommandExecutor;

public class HostsFileManager {

	static Logger log = LoggerFactory.getLogger(HostsFileManager.class);

	File hostsFile;
	List<String> content = new ArrayList<String>();
	LinkedList<String> aliasPool = new LinkedList<String>();

	Map<String, String> hostsToLoopbackAlias = new HashMap<String, String>();
	AliasCommand aliasCommand = null;
	int poolIndexA = 0;
	int poolIndexB = 0;
	
	static final String BEGIN = "#----HYPERSOCKET BEGIN----";

	public HostsFileManager(File hostsFile, AliasCommand aliasCommand)
			throws IOException {
		this.hostsFile = hostsFile;
		this.aliasCommand = aliasCommand;
		generatePool();
		loadFile();

		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				try {
					cleanup();
				} catch (IOException e) {
				}
			}
		});
	}

	public List<String> getAliasPool() {
		return aliasPool;
	}
	
	public static HostsFileManager getSystemHostsFile() throws IOException {

		File hostsFile = null;

		String osName = System.getProperty("os.name");
		AliasCommand aliasCommand = null;

		if (osName.startsWith("Mac")) {
			hostsFile = new File("/private/etc/hosts");
			aliasCommand = new OSXAliasCommand();
		} else if (osName.startsWith("Linux")) {
			hostsFile = new File("/etc/hosts");
			aliasCommand = new LinuxAliasCommand();
		} else if (osName.startsWith("Windows")) {
			hostsFile = new File(System.getenv("SystemRoot"), "System32"
					+ File.separator + "drivers" + File.separator + "etc"
					+ File.separator + "hosts");
		} else {
			throw new IOException("Unsupported operating system " + osName);
		}

		if (log.isInfoEnabled()) {
			log.info("Starting hosts file manager for "
					+ System.getProperty("os.name"));
		}

		return new HostsFileManager(hostsFile, aliasCommand);

	}

	public void generatePool() throws IOException {
		if (poolIndexA > 255) {
			poolIndexA = 0;
			poolIndexB++;
			if(poolIndexB > 255) {
				throw new IOException(
					"Loopback address pool has run out of pooled addresses");
			}
		}
		int i = (poolIndexB == 0 && poolIndexA == 0) ? 2 : 1;
		for (; i <= 255; i++) {
			System.out.println("Generating " + "127." + poolIndexB + "." + poolIndexA + "." + i);
			aliasPool.addLast("127." + poolIndexB + "." + poolIndexA + "." + i);
		}
		poolIndexA++;
	}

	public void cleanup() throws IOException {
		flushFile(false);
	}

	private void loadFile() throws IOException {
		// Load and remove any aliases that might be left in the file
		BufferedReader reader = new BufferedReader(new InputStreamReader(
				new FileInputStream(hostsFile)));

		try {
			String str;
			while ((str = reader.readLine()) != null && !str.equals(BEGIN)) {
				content.add(str);
			}

			processContentForAliases();

		} finally {
			reader.close();
		}
	}

	private void processContentForAliases() {

		for (String str : content) {
			if (!str.trim().startsWith("#")) {
				StringTokenizer t = new StringTokenizer(str, " ");
				if (t.hasMoreTokens()) {
					String address = t.nextToken();
					if (aliasPool.contains(address)) {
						// The localhost alias is taken already
						aliasPool.remove(address);
					}
				}
			}
		}
	}

	private synchronized void flushFile(boolean outputAliases) throws IOException {

		if (!outputAliases) {
			for (String alias : hostsToLoopbackAlias.values()) {
				if (aliasCommand != null && !aliasCommand.deleteAlias(alias)) {
					if (log.isWarnEnabled()) {
						log.warn("Failed to remove loopback alias " + alias);
					}
					continue;
				}
				aliasPool.addLast(alias);
			}
			hostsToLoopbackAlias.clear();
		}

		if (Boolean.getBoolean("hypersocket.development")) {
			File tmp = File.createTempFile("hypersocket", ".tmp");
			writeFile(tmp, outputAliases);

			CommandExecutor cmd = new BashSilentSudoCommand(System.getProperty(
					"sudo.password").toCharArray(), "mv", "-f",
					tmp.getAbsolutePath(), hostsFile.getAbsolutePath());

			if (cmd.execute() != 0) {
				throw new IOException("Could not flush localhost alias to "
						+ hostsFile.getAbsolutePath());
			}
		} else {
			writeFile(hostsFile, outputAliases);
		}
	}

	private void writeFile(File file, boolean outputAliases) throws IOException {
		FileOutputStream out = new FileOutputStream(file);
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out));
		try {
			for (String host : content) {
				writer.write(host);
				writer.write(System.getProperty("line.separator"));
			}

			if (outputAliases) {
				writer.write(System.getProperty("line.separator"));
				writer.write(BEGIN);
				writer.write(System.getProperty("line.separator"));
				writer.write("# WARNING: Any hosts added beyond this line will be removed when the Hypersocket client disconnects.");
				writer.write(System.getProperty("line.separator"));

				for (Map.Entry<String, String> host : hostsToLoopbackAlias
						.entrySet()) {
					writer.write(host.getValue() + " " + host.getKey());
					writer.write(System.getProperty("line.separator"));
				}
			}
			writer.flush();
		} finally {
			writer.close();
		}
	}

	public synchronized String getAlias(String hostname) throws IOException {
		if (hostsToLoopbackAlias.containsKey(hostname)) {
			return hostsToLoopbackAlias.get(hostname);
		}

		return addAlias(hostname);
	}

	public synchronized boolean hasAlias(String hostname) {
		return hostsToLoopbackAlias.containsKey(hostname);
	}

	private synchronized String addAlias(String hostname) throws IOException {
		if (!aliasPool.iterator().hasNext()) {
			generatePool();
		}
		hostsToLoopbackAlias.put(hostname, aliasPool.removeFirst());
		String alias = getAlias(hostname);

		if (aliasCommand != null) {
			if (!aliasCommand.createAlias(alias)) {
				throw new IOException("Failed to create localhost alias "
						+ alias);
			}
		}

		flushFile(true);
		return alias;
	}
}
