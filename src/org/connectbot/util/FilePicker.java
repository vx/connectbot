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

package org.connectbot.util;

import java.io.File;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.connectbot.R;

import org.openintents.intents.FileManagerIntents;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.net.Uri;
import android.os.Environment;

public final class FilePicker {
	public static final int REQUEST_CODE_PICK_FILE = 1;

	// Constants for AndExplorer's file picking intent
	private static final String ANDEXPLORER_TITLE = "explorer_title";
	private static final String MIME_TYPE_ANDEXPLORER_FILE = "vnd.android.cursor.dir/lysesoft.andexplorer.file";

	public static void pickFile(Activity source, FilePickerCallback callback) {
		Uri sdcard = Uri.fromFile(Environment.getExternalStorageDirectory());
		String pickerTitle = source.getString(R.string.pubkey_list_pick);

		// Try to use OpenIntent's file browser to pick a file
		Intent intent = new Intent(FileManagerIntents.ACTION_PICK_FILE);
		intent.setData(sdcard);
		intent.putExtra(FileManagerIntents.EXTRA_TITLE, pickerTitle);
		intent.putExtra(FileManagerIntents.EXTRA_BUTTON_TEXT, source.getString(android.R.string.ok));

		try {
			source.startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
		} catch (ActivityNotFoundException e) {
			// If OI didn't work, try AndExplorer
			intent = new Intent(Intent.ACTION_PICK);
			intent.setDataAndType(sdcard, MIME_TYPE_ANDEXPLORER_FILE);
			intent.putExtra(ANDEXPLORER_TITLE, pickerTitle);

			try {
				source.startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
			} catch (ActivityNotFoundException e1) {
				if (callback != null) {
					pickFileSimple(source, callback);
				}
			}
		}
	}

	private static void pickFileSimple(Activity source, final FilePickerCallback callback) {
		// Don't show a dialog if the SD card is completely absent.
		final String state = Environment.getExternalStorageState();
		if (!Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)
				&& !Environment.MEDIA_MOUNTED.equals(state)) {
			new AlertDialog.Builder(source)
				.setMessage(R.string.alert_sdcard_absent)
				.setNegativeButton(android.R.string.cancel, null).create().show();
			return;
		}

		// build list of all files in sdcard root
		final File sdcard = Environment.getExternalStorageDirectory();
		pickFileRecursive(source, callback, sdcard, sdcard);
	}

	private static void pickFileRecursive(final Activity source, final FilePickerCallback callback, final File sdcard, final File currentDir) {
		List<String> fileNames = new LinkedList<String>();
		List<String> dirNames = new LinkedList<String>();
		File[] files = currentDir.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					dirNames.add(file.getName() + '/');
				} else {
					fileNames.add(file.getName());
				}
			}
		}
		Collections.sort(dirNames);
		Collections.sort(fileNames);

		final String[] namesList = new String[(sdcard.equals(currentDir) ? 0 : 1) + dirNames.size() + fileNames.size()];
		int ix = 0;
		if (! sdcard.equals(currentDir)) {
			namesList[ix] = "..";
			ix++;
		}
		for (String dirname : dirNames) {
			namesList[ix] = dirname;
			ix++;
		}
		final int fileStartIndex = ix;
		for (String fileName : fileNames) {
			namesList[ix] = fileName;
			ix++;
		}

		// prompt user to select any file from the current dir
		new AlertDialog.Builder(source)
			.setTitle(R.string.pubkey_list_pick)
			.setItems(namesList, new OnClickListener() {
				public void onClick(DialogInterface dialog, int index) {
					if (! sdcard.equals(currentDir) && index == 0) {
						// go back up to the parent dir
						pickFileRecursive(source, callback, sdcard, currentDir.getParentFile());
						return;
					}
					File selected = new File(currentDir, namesList[index]);
					if (index < fileStartIndex) {
						// it's a directory
						pickFileRecursive(source, callback, sdcard, selected);
					} else {
						// it's a file, notify callback
						callback.filePicked(selected);
					}
				}
			})
			.setNegativeButton(android.R.string.cancel, null).create().show();
	}
}
