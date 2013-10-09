/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.apps.mytracks.io.sync;

import com.google.android.apps.mytracks.Constants;
import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.content.TracksColumns;
import com.google.android.apps.mytracks.io.file.TrackFileFormat;
import com.google.android.apps.mytracks.io.file.exporter.FileTrackExporter;
import com.google.android.apps.mytracks.io.file.exporter.KmzTrackExporter;
import com.google.android.apps.mytracks.io.file.exporter.TrackExporter;
import com.google.android.apps.mytracks.util.FileUtils;
import com.google.android.apps.mytracks.util.PreferencesUtils;
import com.google.android.maps.mytracks.R;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Files.List;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;
import com.google.common.annotations.VisibleForTesting;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Utilites for Google Drive sync.
 * 
 * @author Jimmy Shih
 */
public class SyncUtils {

  // Get tracks with drive id
  public static final String DRIVE_ID_TRACKS_QUERY = TracksColumns.DRIVEID + " IS NOT NULL AND "
      + TracksColumns.DRIVEID + "!=''";

  // Get tracks without drive id
  public static final String NO_DRIVE_ID_TRACKS_QUERY = TracksColumns.DRIVEID + " IS NULL OR "
      + TracksColumns.DRIVEID + "=''";
  
  // KML mime type
  public static final String KML_MIME_TYPE = "application/vnd.google-earth.kml+xml";

  // KMZ mime type
  public static final String KMZ_MIME_TYPE = "application/vnd.google-earth.kmz";

  // KML and KMZ mime types
  private static final String KML_KMZ_MINE_TYPES = "not (mimeType != '" + KML_MIME_TYPE
      + "' and mimeType != '" + KMZ_MIME_TYPE + "')";

  // Get KML/KMZ files in the My Tracks folder
  public static final String MY_TRACKS_FOLDER_FILES_QUERY = "'%s' in parents and "
      + KML_KMZ_MINE_TYPES + " and trashed = false";

  // Get shared with me KML/KMZ files
  public static final String SHARED_WITH_ME_FILES_QUERY = "sharedWithMe and " + KML_KMZ_MINE_TYPES
      + " and trashed = false";

  // Folder mime type
  private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";

  // Get My Tracks folder
  @VisibleForTesting
  public static final String MY_TRACKS_FOLDER_QUERY =
      "'root' in parents and title = '%s' and mimeType = '" + FOLDER_MIME_TYPE
      + "' and trashed = false";

  private static final String TAG = SyncUtils.class.getSimpleName();
  private static final String SYNC_AUTHORITY = "com.google.android.maps.mytracks";

  private SyncUtils() {}

  /**
   * Syncs now for the current account.
   * 
   * @param context the context
   */
  public static void syncNow(Context context) {
    Account[] accounts = AccountManager.get(context).getAccountsByType(Constants.ACCOUNT_TYPE);
    String googleAccount = PreferencesUtils.getString(
        context, R.string.google_account_key, PreferencesUtils.GOOGLE_ACCOUNT_DEFAULT);
    for (Account account : accounts) {
      if (account.name.equals(googleAccount)) {
        ContentResolver.cancelSync(account, SYNC_AUTHORITY);
        ContentResolver.requestSync(account, SYNC_AUTHORITY, new Bundle());
        break;
      }
    }
  }

  /**
   * Disables sync.
   * 
   * @param context the context
   */
  public static void disableSync(Context context) {
    Account[] accounts = AccountManager.get(context).getAccountsByType(Constants.ACCOUNT_TYPE);
    for (Account account : accounts) {
      ContentResolver.cancelSync(account, SYNC_AUTHORITY);
      ContentResolver.setIsSyncable(account, SYNC_AUTHORITY, 0);
      ContentResolver.setSyncAutomatically(account, SYNC_AUTHORITY, false);
    }
  }

  /**
   * Returns true if sync is active.
   * 
   * @param context the context
   */
  public static boolean isSyncActive(Context context) {
    Account[] accounts = AccountManager.get(context).getAccountsByType(Constants.ACCOUNT_TYPE);
    for (Account account : accounts) {
      if (ContentResolver.isSyncActive(account, SYNC_AUTHORITY)) {
        return true;
      }
    }
    return false;
  }
  
  /**
   * Enables sync.
   * 
   * @param account the account
   */
  public static void enableSync(Account account) {
    ContentResolver.setIsSyncable(account, SYNC_AUTHORITY, 1);
    ContentResolver.setSyncAutomatically(account, SYNC_AUTHORITY, true);
    ContentResolver.requestSync(account, SYNC_AUTHORITY, new Bundle());
  }

  /**
   * Clears the sync state. Assumes sync is turned off. Do not want clearing the
   * sync state to cause sync activities.
   */
  public static void clearSyncState(Context context) {
    MyTracksProviderUtils myTracksProviderUtils = MyTracksProviderUtils.Factory.get(context);
    Cursor cursor = null;
    try {
      cursor = myTracksProviderUtils.getTrackCursor(SyncUtils.DRIVE_ID_TRACKS_QUERY, null, null);
      if (cursor != null && cursor.moveToFirst()) {
        do {
          Track track = myTracksProviderUtils.createTrack(cursor);
          if (track.isSharedWithMe()) {
            myTracksProviderUtils.deleteTrack(track.getId());
          } else {
            SyncUtils.updateTrack(myTracksProviderUtils, track, null);
          }
        } while (cursor.moveToNext());
      }
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }
    PreferencesUtils.setLong(context, R.string.drive_largest_change_id_key,
        PreferencesUtils.DRIVE_LARGEST_CHANGE_ID_DEFAULT);

    // Clear the drive_deleted_list_key last
    PreferencesUtils.setString(
        context, R.string.drive_deleted_list_key, PreferencesUtils.DRIVE_DELETED_LIST_DEFAULT);
  }

  /**
   * Gets the drive service.
   * 
   * @param credential the credential
   */
  public static Drive getDriveService(GoogleAccountCredential credential) {
    return new Drive.Builder(AndroidHttp.newCompatibleTransport(), new GsonFactory(), credential)
        .build();
  }

  /**
   * Gets the My Tracks folder. Creates one if necessary.
   * 
   * @param context the context
   * @param drive the drive
   */
  public static File getMyTracksFolder(Context context, Drive drive) throws IOException {
    String folderName = context.getString(R.string.my_tracks_app_name);
    List list = drive.files()
        .list().setQ(String.format(Locale.US, MY_TRACKS_FOLDER_QUERY, folderName));
    FileList result = list.execute();
    for (File file : result.getItems()) {
      if (file.getSharedWithMeDate() == null) {
        return file;
      }
    }
    File file = new File();
    file.setTitle(folderName);
    file.setMimeType(FOLDER_MIME_TYPE);
    return drive.files().insert(file).execute();
  }

  /**
   * Returns true if a drive file is a Shared with me KML or KMZ file.
   * 
   * @param driveFile the drive file
   */
  public static boolean isSharedWithMe(File driveFile) {
    if (driveFile == null) {
      return false;
    }
    String mimeType = driveFile.getMimeType();
    if (!SyncUtils.KML_MIME_TYPE.equals(mimeType) && !SyncUtils.KMZ_MIME_TYPE.equals(mimeType)) {
      return false;
    }
    return driveFile.getSharedWithMeDate() != null;
  }

  /**
   * Returns true if a drive file is a KML or KMZ file in the My Tracks folder
   * and not trashed.
   * 
   * @param driveFile the drive file
   * @param folderId the My Tracks folder id
   */
  public static boolean isValid(File driveFile, String folderId) {
    return isInFolder(driveFile, folderId) && !driveFile.getLabels().getTrashed();
  }

  /**
   * Returns true if a drive file is a KML or KMZ file in the My Tracks folder.
   * 
   * @param driveFile the drive file
   * @param folderId the My Tracks folder id
   */
  public static boolean isInFolder(File driveFile, String folderId) {
    if (driveFile == null) {
      return false;
    }
    String mimeType = driveFile.getMimeType();
    if (!SyncUtils.KML_MIME_TYPE.equals(mimeType) && !SyncUtils.KMZ_MIME_TYPE.equals(mimeType)) {
      return false;
    }
    if (driveFile.getSharedWithMeDate() != null) {
      return false;
    }
    for (ParentReference parentReference : driveFile.getParents()) {
      String id = parentReference.getId();
      if (id != null && id.equals(folderId)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Inserts a drive file using info from a track.
   * 
   * @param drive the drive
   * @param folderId the folder id
   * @param context the context
   * @param myTracksProviderUtils the myTracksProviderUtils
   * @param track the track
   * @param canRetry true if can retry
   * @return the added drive file or null.
   */
  public static File insertDriveFile(Drive drive, String folderId, Context context,
      MyTracksProviderUtils myTracksProviderUtils, Track track, boolean canRetry)
      throws IOException {
    java.io.File file = null;
    try {
      file = getTempFile(context, myTracksProviderUtils, track, true);

      if (file == null) {
        Log.e(TAG, "Unable to add Drive file. File is null for track " + track.getName());
        return null;
      }

      Log.d(TAG, "Add Drive file for track " + track.getName());
      File uploadedFile = insertDriveFile(drive, folderId, track.getName(), file, canRetry);
      if (uploadedFile == null) {
        Log.e(TAG, "Unable to add Drive file. Uploaded file is null for track " + track.getName());
        return null;
      }
      SyncUtils.updateTrack(myTracksProviderUtils, track, uploadedFile);
      return uploadedFile;
    } finally {
      if (file != null) {
        file.delete();
      }
    }
  }

  /**
   * Inserts a drive file using info from a track file.
   * 
   * @param drive the drive
   * @param folderId the folder id
   * @param trackName the track name
   * @param file the track file
   * @param canRetry true if can retry
   */
  private static File insertDriveFile(
      Drive drive, String folderId, String trackName, java.io.File file, boolean canRetry)
      throws IOException {
    try {
      // file's parent
      ParentReference parentReference = new ParentReference();
      parentReference.setId(folderId);
      ArrayList<ParentReference> parents = new ArrayList<ParentReference>();
      parents.add(parentReference);

      // file's metadata
      File newMetaData = new File();
      newMetaData.setTitle(trackName + "." + KmzTrackExporter.KMZ_EXTENSION);
      newMetaData.setMimeType(KMZ_MIME_TYPE);
      newMetaData.setParents(parents);

      FileContent fileContent = new FileContent(KMZ_MIME_TYPE, file);
      return drive.files().insert(newMetaData, fileContent).execute();
    } catch (UserRecoverableAuthIOException e) {
      throw e;
    } catch (IOException e) {
      if (canRetry) {
        return insertDriveFile(drive, folderId, trackName, file, false);
      }
      throw e;
    }
  }

  /**
   * Updates a drive file using info from a track. Returns true if successful.
   * 
   * @param drive the drive
   * @param driveFile the drive file
   * @param context the context
   * @param myTracksProviderUtils the myTracksProviderUtils
   * @param track the track
   * @param canRetry true if can retry
   */
  public static boolean updateDriveFile(Drive drive, File driveFile, Context context,
      MyTracksProviderUtils myTracksProviderUtils, Track track, boolean canRetry)
      throws IOException {
    Log.d(TAG, "Update drive file for track " + track.getName());
    java.io.File file = null;

    try {
      file = SyncUtils.getTempFile(context, myTracksProviderUtils, track, true);

      if (file == null) {
        Log.e(TAG, "Unable to update drive file. File is null for track " + track.getName());
        return false;
      }

      String title = track.getName() + "." + KmzTrackExporter.KMZ_EXTENSION;
      File updatedFile;
      String digest = md5(file);
      if (digest != null && digest.equals(driveFile.getMd5Checksum())) {
        if (title.equals(driveFile.getTitle())) {
          updatedFile = driveFile;
        } else {
          // Only update the title
          updatedFile = updateDriveFile(drive, driveFile, title, null, canRetry);
        }
      } else {
        updatedFile = updateDriveFile(drive, driveFile, title, file, canRetry);
      }
      if (updatedFile == null) {
        Log.e(
            TAG, "Unable to update drive file. Updated file is null for track " + track.getName());
        return false;
      }
      long modifiedTime = updatedFile.getModifiedDate().getValue();
      if (track.getModifiedTime() != modifiedTime) {
        track.setModifiedTime(modifiedTime);
        myTracksProviderUtils.updateTrack(track);
      }
      return true;
    } finally {
      if (file != null) {
        file.delete();
      }
    }
  }

  /**
   * Updates a drive file using a track file.
   * 
   * @param drive the drive
   * @param driveFile the drive file
   * @param driveTitle the drive title
   * @param file the track file. If null, just update the driveFile meta data
   * @param canRetry true if can retry
   */
  public static File updateDriveFile(
      Drive drive, File driveFile, String driveTitle, java.io.File file, boolean canRetry)
      throws IOException {
    try {
      driveFile.setTitle(driveTitle);
      driveFile.setMimeType(KMZ_MIME_TYPE);

      if (file != null) {
        FileContent fileContent = new FileContent(KMZ_MIME_TYPE, file);
        return drive.files().update(driveFile.getId(), driveFile, fileContent).execute();
      } else {
        return drive.files().update(driveFile.getId(), driveFile).execute();
      }
    } catch (UserRecoverableAuthIOException e) {
      throw e;
    } catch (IOException e) {
      if (canRetry) {
        return updateDriveFile(drive, driveFile, driveTitle, file, false);
      }
      throw e;
    }
  }

  /**
   * Gets a temporary file for a track.
   * 
   * @param context the context
   * @param myTracksProviderUtils the myMyTracksProviderUtils
   * @param track the track
   * @param useKmz true to output kmz
   */
  public static java.io.File getTempFile(
      Context context, MyTracksProviderUtils myTracksProviderUtils, Track track, boolean useKmz)
      throws FileNotFoundException {
    String extension = useKmz ? KmzTrackExporter.KMZ_EXTENSION : TrackFileFormat.KML.getExtension();
    java.io.File directory = new java.io.File(context.getCacheDir(), FileUtils.TEMP_FILES_DIR);

    if (!FileUtils.ensureDirectoryExists(directory)) {
      Log.d(TAG, "Unable to create " + directory.getAbsolutePath());
      return null;
    }

    for (java.io.File file : directory.listFiles()) {
      file.delete();
    }

    Track[] tracks = new Track[] { track };
    java.io.File file = new java.io.File(
        directory, FileUtils.buildUniqueFileName(directory, track.getName(), extension));
    FileTrackExporter fileTrackExporter = new FileTrackExporter(
        myTracksProviderUtils, tracks, TrackFileFormat.KML.newTrackWriter(context, false), null);
    TrackExporter trackExporter = useKmz ? new KmzTrackExporter(
        myTracksProviderUtils, fileTrackExporter, tracks)
        : fileTrackExporter;

    FileOutputStream fileOutputStream = null;
    try {
      fileOutputStream = new FileOutputStream(file);
      if (trackExporter.writeTrack(fileOutputStream)) {
        return file;
      } else {
        if (!file.delete()) {
          Log.d(TAG, "Unable to delete file for track " + track.getName());
        }
        Log.d(TAG, "Unable to get file for track " + track.getName());
        return null;
      }
    } finally {
      if (fileOutputStream != null) {
        try {
          fileOutputStream.close();
        } catch (IOException e) {
          Log.e(TAG, "Unable to close file output stream", e);
        }
      }
    } 
  }
  
  /**
   * Updates a track with info from a drive file.
   * 
   * @param myTracksProviderUtils the myTracksProviderUtils
   * @param track the track
   * @param driveFile the drive file
   */
  public static void updateTrack(
      MyTracksProviderUtils myTracksProviderUtils, Track track, File driveFile) {
    track.setDriveId(driveFile != null ? driveFile.getId() : "");
    track.setModifiedTime(driveFile != null ? driveFile.getModifiedDate().getValue() : -1L);
    track.setSharedWithMe(driveFile != null ? driveFile.getSharedWithMeDate() != null : false);
    track.setSharedOwner(driveFile != null && driveFile.getSharedWithMeDate() != null
        && driveFile.getOwnerNames().size() > 0 ? driveFile.getOwnerNames().get(0)
        : "");
    myTracksProviderUtils.updateTrack(track);
  }

  /**
   * Gets the md5 digest for a file.
   * 
   * @param file the file
   */
  public static String md5(java.io.File file) {
    if (file == null) {
      return null;
    }
    InputStream in = null;
    byte[] digest;
    try {
      in = new FileInputStream(file);
      MessageDigest digester = MessageDigest.getInstance("MD5");
      byte[] bytes = new byte[8192];
      int byteCount;
      while ((byteCount = in.read(bytes)) > 0) {
        digester.update(bytes, 0, byteCount);
      }
      digest = digester.digest();

      StringBuilder builder = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        if ((b & 0xFF) < 0x10) {
          builder.append("0");
        }
        builder.append(Integer.toHexString(b & 0xFF));
      }
      return builder.toString();

    } catch (IOException e) {
      Log.e(TAG, "IOException", e);
      return null;
    } catch (NoSuchAlgorithmException e) {
      Log.e(TAG, "NoSuchAlgorithmException", e);
      return null;
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException e) {
          Log.e(TAG, "Unable to close inputstream", e);
          return null;
        }
      }
    }
  }
}
