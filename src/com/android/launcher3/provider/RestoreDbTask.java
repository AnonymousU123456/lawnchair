/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.provider;

import static android.os.Process.myUserHandle;

import static com.android.launcher3.InvariantDeviceProfile.TYPE_MULTI_DISPLAY;
import static com.android.launcher3.LauncherPrefs.APP_WIDGET_IDS;
import static com.android.launcher3.LauncherPrefs.OLD_APP_WIDGET_IDS;
import static com.android.launcher3.LauncherPrefs.RESTORE_DEVICE;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;
import static com.android.launcher3.provider.LauncherDbUtils.dropTable;
import static com.android.launcher3.widget.LauncherWidgetHolder.APPWIDGET_HOST_ID;

import android.app.backup.BackupManager;
import android.appwidget.AppWidgetHost;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseLongArray;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.launcher3.AppWidgetsRestoredReceiver;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.Utilities;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.model.DeviceGridState;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.LauncherAppWidgetInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;
import com.android.launcher3.uioverrides.ApiWrapper;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.LogConfig;

import java.io.InvalidObjectException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class to update DB schema after it has been restored.
 *
 * This task is executed when Launcher starts for the first time and not
 * immediately after restore.
 * This helps keep the model consistent if the launcher updates between restore
 * and first startup.
 */
public class RestoreDbTask {

    private static final String TAG = "RestoreDbTask";
    public static final String RESTORED_DEVICE_TYPE = "restored_task_pending";

    private static final String INFO_COLUMN_NAME = "name";
    private static final String INFO_COLUMN_DEFAULT_VALUE = "dflt_value";

    public static final String APPWIDGET_OLD_IDS = "appwidget_old_ids";
    public static final String APPWIDGET_IDS = "appwidget_ids";
    public static final String FIRST_LOAD_AFTER_RESTORE_KEY = "first_load_after_restore";

    private static final String[] DB_COLUMNS_TO_LOG = { "profileId", "title", "itemType", "screen",
            "container", "cellX", "cellY", "spanX", "spanY", "intent" };

    /**
     * Tries to restore the backup DB if needed
     */
    public static void restoreIfNeeded(Context context, ModelDbController dbController) {
        if (!isPending(context)) {
            Log.d(TAG, "No restore task pending, exiting RestoreDbTask");
            return;
        }
        if (!performRestore(context, dbController)) {
            dbController.createEmptyDB();
        }

        // Obtain InvariantDeviceProfile first before setting pending to false, so
        // InvariantDeviceProfile won't switch to new grid when initializing.
        InvariantDeviceProfile idp = InvariantDeviceProfile.INSTANCE.get(context);

        // Set is pending to false irrespective of the result, so that it doesn't get
        // executed again.
        LauncherPrefs.get(context).removeSync(RESTORE_DEVICE);

        idp.reinitializeAfterRestore(context);
    }

    private static boolean performRestore(Context context, ModelDbController controller) {
        SQLiteDatabase db = controller.getDb();
        FileLog.d(TAG, "performRestore: starting restore from db");
        try (SQLiteTransaction t = new SQLiteTransaction(db)) {
            RestoreDbTask task = new RestoreDbTask();
            task.sanitizeDB(context, controller, db, new BackupManager(context));
            task.restoreAppWidgetIdsIfExists(context, controller);
            t.commit();
            return true;
        } catch (Exception e) {
            FileLog.e(TAG, "Failed to verify db", e);
            return false;
        }
    }

    /**
     * Makes the following changes in the provider DB.
     * 1. Removes all entries belonging to any profiles that were not restored.
     * 2. Marks all entries as restored. The flags are updated during first load or
     * as
     * the restored apps get installed.
     * 3. If the user serial for any restored profile is different than that of the
     * previous
     * device, update the entries to the new profile id.
     * 4. If restored from a single display backup, remove gaps between screenIds
     * 5. Override shortcuts that need to be replaced.
     *
     * @return number of items deleted.
     */
    @VisibleForTesting
    protected int sanitizeDB(Context context, ModelDbController controller, SQLiteDatabase db,
            BackupManager backupManager) throws Exception {
        FileLog.d(TAG, "Old Launcher Database before sanitizing:");
        // Primary user ids
        long myProfileId = controller.getSerialNumberForUser(myUserHandle());
        long oldProfileId = getDefaultProfileId(db);
        FileLog.d(TAG, "sanitizeDB: myProfileId=" + myProfileId + " oldProfileId=" + oldProfileId);
        LongSparseArray<Long> oldManagedProfileIds = getManagedProfileIds(db, oldProfileId);
        LongSparseArray<Long> profileMapping = new LongSparseArray<>(oldManagedProfileIds.size()
                + 1);

        // Build mapping of restored profile ids to their new profile ids.
        profileMapping.put(oldProfileId, myProfileId);
        for (int i = oldManagedProfileIds.size() - 1; i >= 0; --i) {
            long oldManagedProfileId = oldManagedProfileIds.keyAt(i);
            UserHandle user = getUserForAncestralSerialNumber(backupManager, oldManagedProfileId);
            if (user != null) {
                long newManagedProfileId = controller.getSerialNumberForUser(user);
                profileMapping.put(oldManagedProfileId, newManagedProfileId);
                FileLog.d(TAG, "sanitizeDB: managed profile id=" + oldManagedProfileId
                        + " should be mapped to new id=" + newManagedProfileId);
            } else {
                FileLog.e(TAG, "sanitizeDB: No User found for old profileId, Ancestral Serial "
                        + "Number: " + oldManagedProfileId);
            }
        }

        // Delete all entries which do not belong to any restored profile(s).
        int numProfiles = profileMapping.size();
        String[] profileIds = new String[numProfiles];
        profileIds[0] = Long.toString(oldProfileId);
        for (int i = numProfiles - 1; i >= 1; --i) {
            profileIds[i] = Long.toString(profileMapping.keyAt(i));
        }

        final String[] args = new String[profileIds.length];
        Arrays.fill(args, "?");
        final String where = "profileId NOT IN (" + TextUtils.join(", ", Arrays.asList(args)) + ")";
        logUnrestoredItems(db, where, profileIds);
        int itemsDeletedCount = db.delete(Favorites.TABLE_NAME, where, profileIds);
        FileLog.d(TAG, itemsDeletedCount + " total items from unrestored user(s) were deleted");

        // Mark all items as restored.
        boolean keepAllIcons = Utilities.isPropertyEnabled(LogConfig.KEEP_ALL_ICONS);
        ContentValues values = new ContentValues();
        values.put(Favorites.RESTORED, WorkspaceItemInfo.FLAG_RESTORED_ICON
                | (keepAllIcons ? WorkspaceItemInfo.FLAG_RESTORE_STARTED : 0));
        db.update(Favorites.TABLE_NAME, values, null, null);

        // Mark widgets with appropriate restore flag.
        values.put(Favorites.RESTORED, LauncherAppWidgetInfo.FLAG_ID_NOT_VALID
                | LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY
                | LauncherAppWidgetInfo.FLAG_UI_NOT_READY
                | (keepAllIcons ? LauncherAppWidgetInfo.FLAG_RESTORE_STARTED : 0));
        db.update(Favorites.TABLE_NAME, values, "itemType = ?",
                new String[] { Integer.toString(Favorites.ITEM_TYPE_APPWIDGET) });

        // Migrate ids. To avoid any overlap, we initially move conflicting ids to a
        // temp
        // location. Using Long.MIN_VALUE since profile ids can not be negative, so
        // there will
        // be no overlap.
        final long tempLocationOffset = Long.MIN_VALUE;
        SparseLongArray tempMigratedIds = new SparseLongArray(profileMapping.size());
        int numTempMigrations = 0;
        for (int i = profileMapping.size() - 1; i >= 0; --i) {
            long oldId = profileMapping.keyAt(i);
            long newId = profileMapping.valueAt(i);

            if (oldId != newId) {
                if (profileMapping.indexOfKey(newId) >= 0) {
                    tempMigratedIds.put(numTempMigrations, newId);
                    numTempMigrations++;
                    newId = tempLocationOffset + newId;
                }
                migrateProfileId(db, oldId, newId);
            }
        }

        // Migrate ids from their temporary id to their actual final id.
        for (int i = tempMigratedIds.size() - 1; i >= 0; --i) {
            long newId = tempMigratedIds.valueAt(i);
            migrateProfileId(db, tempLocationOffset + newId, newId);
        }

        if (myProfileId != oldProfileId) {
            changeDefaultColumn(db, myProfileId);
        }

        // If restored from a single display backup, remove gaps between screenIds
        if (LauncherPrefs.get(context).get(RESTORE_DEVICE) != TYPE_MULTI_DISPLAY) {
            removeScreenIdGaps(db);
        }

        // Override shortcuts
        maybeOverrideShortcuts(context, controller, db, myProfileId);
        return itemsDeletedCount;
    }

    /**
     * Queries and logs the items we will delete from unrestored profiles in the
     * launcher db.
     * This is to understand why items might be missing during the restore process
     * for Launcher.
     * 
     * @param database   the Launcher db to query from.
     * @param where      the SELECT statement to query items that will be deleted.
     * @param profileIds the profile ID's the user will be migrating to.
     */
    private void logUnrestoredItems(SQLiteDatabase database, String where, String[] profileIds) {
        try (Cursor itemsToDelete = database.query(
                /* table */ Favorites.TABLE_NAME,
                /* columns */ DB_COLUMNS_TO_LOG,
                /* selection */ where,
                /* selection args */ profileIds,
                /* groupBy */ null,
                /* having */ null,
                /* orderBy */ null)) {
            if (itemsToDelete.moveToFirst()) {
                String[] columnNames = itemsToDelete.getColumnNames();
                StringBuilder stringBuilder = new StringBuilder(
                        "items to be deleted from the Favorites Table during restore:\n");
                do {
                    for (String columnName : columnNames) {
                        stringBuilder.append(columnName)
                                .append("=")
                                .append(itemsToDelete.getString(
                                        itemsToDelete.getColumnIndex(columnName)))
                                .append(" ");
                    }
                    stringBuilder.append("\n");
                } while (itemsToDelete.moveToNext());
                FileLog.d(TAG, stringBuilder.toString());
            } else {
                FileLog.d(TAG, "logDeletedItems: No items found to delete");
            }
        } catch (Exception e) {
            FileLog.e(TAG, "logDeletedItems: Error reading from database", e);
        }
    }

    /**
     * Remove gaps between screenIds to make sure no empty pages are left in
     * between.
     *
     * e.g. [0, 3, 4, 6, 7] -> [0, 1, 2, 3, 4]
     */
    protected void removeScreenIdGaps(SQLiteDatabase db) {
        FileLog.d(TAG, "Removing gaps between screenIds");
        IntArray distinctScreens = LauncherDbUtils.queryIntArray(true, db, Favorites.TABLE_NAME,
                Favorites.SCREEN, Favorites.CONTAINER + " = " + Favorites.CONTAINER_DESKTOP, null,
                Favorites.SCREEN);
        if (distinctScreens.isEmpty()) {
            return;
        }

        StringBuilder sql = new StringBuilder("UPDATE ").append(Favorites.TABLE_NAME)
                .append(" SET ").append(Favorites.SCREEN).append(" =\nCASE\n");
        int screenId = distinctScreens.contains(0) ? 0 : 1;
        for (int i = 0; i < distinctScreens.size(); i++) {
            sql.append("WHEN ").append(Favorites.SCREEN).append(" == ")
                    .append(distinctScreens.get(i)).append(" THEN ").append(screenId++).append("\n");
        }
        sql.append("ELSE screen\nEND WHERE ").append(Favorites.CONTAINER).append(" = ")
                .append(Favorites.CONTAINER_DESKTOP).append(";");
        db.execSQL(sql.toString());
    }

    /**
     * Updates profile id of all entries from {@param oldProfileId} to
     * {@param newProfileId}.
     */
    protected void migrateProfileId(SQLiteDatabase db, long oldProfileId, long newProfileId) {
        FileLog.d(TAG, "Changing profile user id from " + oldProfileId + " to " + newProfileId);
        // Update existing entries.
        ContentValues values = new ContentValues();
        values.put(Favorites.PROFILE_ID, newProfileId);
        db.update(Favorites.TABLE_NAME, values, "profileId = ?",
                new String[] { Long.toString(oldProfileId) });
    }

    /**
     * Changes the default value for the column.
     */
    protected void changeDefaultColumn(SQLiteDatabase db, long newProfileId) {
        db.execSQL("ALTER TABLE favorites RENAME TO favorites_old;");
        Favorites.addTableToDb(db, newProfileId, false);
        db.execSQL("INSERT INTO favorites SELECT * FROM favorites_old;");
        dropTable(db, "favorites_old");
    }

    /**
     * Returns a list of the managed profile id(s) used in the favorites table of
     * the provided db.
     */
    private LongSparseArray<Long> getManagedProfileIds(SQLiteDatabase db, long defaultProfileId) {
        LongSparseArray<Long> ids = new LongSparseArray<>();
        try (Cursor c = db.rawQuery("SELECT profileId from favorites WHERE profileId != ? "
                + "GROUP BY profileId", new String[] { Long.toString(defaultProfileId) })) {
            while (c.moveToNext()) {
                ids.put(c.getLong(c.getColumnIndex(Favorites.PROFILE_ID)), null);
            }
        }
        return ids;
    }

    /**
     * Returns a UserHandle of a restored managed profile with the given serial
     * number, or null
     * if none found.
     */
    private UserHandle getUserForAncestralSerialNumber(BackupManager backupManager,
            long ancestralSerialNumber) {
        if (!Utilities.ATLEAST_Q) {
            return null;
        }
        return backupManager.getUserForAncestralSerialNumber(ancestralSerialNumber);
    }

    /**
     * Returns the profile id used in the favorites table of the provided db.
     */
    protected long getDefaultProfileId(SQLiteDatabase db) throws Exception {
        try (Cursor c = db.rawQuery("PRAGMA table_info (favorites)", null)) {
            int nameIndex = c.getColumnIndex(INFO_COLUMN_NAME);
            while (c.moveToNext()) {
                if (Favorites.PROFILE_ID.equals(c.getString(nameIndex))) {
                    return c.getLong(c.getColumnIndex(INFO_COLUMN_DEFAULT_VALUE));
                }
            }
            throw new InvalidObjectException("Table does not have a profile id column");
        }
    }

    public static boolean isPending(Context context) {
        return LauncherPrefs.get(context).has(RESTORE_DEVICE);
    }

    /**
     * Marks the DB state as pending restoration
     */
    public static void setPending(Context context) {
        FileLog.d(TAG, "Restore data received through full backup");
        LauncherPrefs.get(context)
                .putSync(RESTORE_DEVICE.to(new DeviceGridState(context).getDeviceType()));
    }

    private void restoreAppWidgetIdsIfExists(Context context, ModelDbController controller) {
        LauncherPrefs lp = LauncherPrefs.get(context);
        if (lp.has(APP_WIDGET_IDS, OLD_APP_WIDGET_IDS)) {
            AppWidgetHost host = new AppWidgetHost(context, APPWIDGET_HOST_ID);
            AppWidgetsRestoredReceiver.restoreAppWidgetIds(context, controller,
                    IntArray.fromConcatString(lp.get(OLD_APP_WIDGET_IDS)).toArray(),
                    IntArray.fromConcatString(lp.get(APP_WIDGET_IDS)).toArray(),
                    host);
        } else {
            FileLog.d(TAG, "No app widget ids to restore.");
        }

        lp.remove(APP_WIDGET_IDS, OLD_APP_WIDGET_IDS);
    }

    public static void setRestoredAppWidgetIds(Context context, @NonNull int[] oldIds,
            @NonNull int[] newIds) {
        LauncherPrefs.get(context).putSync(
                OLD_APP_WIDGET_IDS.to(IntArray.wrap(oldIds).toConcatString()),
                APP_WIDGET_IDS.to(IntArray.wrap(newIds).toConcatString()));
    }

    protected static void maybeOverrideShortcuts(Context context, ModelDbController controller,
            SQLiteDatabase db, long currentUser) {
        Map<String, LauncherActivityInfo> activityOverrides = ApiWrapper.getActivityOverrides(
                context);

        if (activityOverrides == null || activityOverrides.isEmpty()) {
            return;
        }

        try (Cursor c = db.query(Favorites.TABLE_NAME,
                new String[] { Favorites._ID, Favorites.INTENT },
                String.format("%s=? AND %s=? AND ( %s )", Favorites.ITEM_TYPE, Favorites.PROFILE_ID,
                        getTelephonyIntentSQLLiteSelection(activityOverrides.keySet())),
                new String[] { String.valueOf(ITEM_TYPE_APPLICATION), String.valueOf(currentUser) },
                null, null, null);
                SQLiteTransaction t = new SQLiteTransaction(db)) {
            final int idIndex = c.getColumnIndexOrThrow(Favorites._ID);
            final int intentIndex = c.getColumnIndexOrThrow(Favorites.INTENT);
            while (c.moveToNext()) {
                LauncherActivityInfo override = activityOverrides.get(Intent.parseUri(
                        c.getString(intentIndex), 0).getComponent().getPackageName());
                if (override != null) {
                    ContentValues values = new ContentValues();
                    values.put(Favorites.PROFILE_ID,
                            controller.getSerialNumberForUser(override.getUser()));
                    values.put(Favorites.INTENT, AppInfo.makeLaunchIntent(override).toUri(0));
                    db.update(Favorites.TABLE_NAME, values, String.format("%s=?", Favorites._ID),
                            new String[] { String.valueOf(c.getInt(idIndex)) });
                }
            }
            t.commit();
        } catch (Exception ex) {
            Log.e(TAG, "Error while overriding shortcuts", ex);
        }
    }

    private static String getTelephonyIntentSQLLiteSelection(Collection<String> packages) {
        return packages.stream().map(
                packageToChange -> String.format("intent LIKE '%%' || '%s' || '%%' ",
                        packageToChange))
                .collect(
                        Collectors.joining(" OR "));
    }

}
