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
		// build list of all files in sdcard root
		final File sdcard = Environment.getExternalStorageDirectory();

		// Don't show a dialog if the SD card is completely absent.
		final String state = Environment.getExternalStorageState();
		if (!Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)
				&& !Environment.MEDIA_MOUNTED.equals(state)) {
			new AlertDialog.Builder(source)
				.setMessage(R.string.alert_sdcard_absent)
				.setNegativeButton(android.R.string.cancel, null).create().show();
			return;
		}

		List<String> names = new LinkedList<String>();
		File[] files = sdcard.listFiles();
		if (files != null) {
			for (File file : sdcard.listFiles()) {
				if (! file.isDirectory()) {
					names.add(file.getName());
				}
			}
		}
		Collections.sort(names);

		final String[] namesList = names.toArray(new String[] {});

		// prompt user to select any file from the sdcard root
		new AlertDialog.Builder(source)
			.setTitle(R.string.pubkey_list_pick)
			.setItems(namesList, new OnClickListener() {
				public void onClick(DialogInterface dialog, int index) {
					String name = namesList[index];
					callback.filePicked(new File(sdcard, name));
				}
			})
			.setNegativeButton(android.R.string.cancel, null).create().show();
	}
}
