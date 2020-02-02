/*
 * Copyright (C) 2013 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2012 Harald Seltner <h.seltner@gmx.at>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.localcalendar;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.sufficientlysecure.localcalendar.util.Constants;
import org.sufficientlysecure.localcalendar.util.Log;

//@SuppressLint("NewApi")
public class CalendarController {
    private static final boolean BEFORE_JELLYBEAN = android.os.Build.VERSION.SDK_INT < 16;

    public static final String ACCOUNT_NAME = "Local Calendar";
    /*
     * Use ACCOUNT_TYPE_LOCAL only on Android >= 4.1
     * 
     * see http://code.google.com/p/android/issues/detail?id=27474
     */
    public static final String ACCOUNT_TYPE = BEFORE_JELLYBEAN ? "org.sufficientlysecure.localcalendar.account"
            : CalendarContract.ACCOUNT_TYPE_LOCAL;
    public static final String CONTENT_AUTHORITY = "com.android.calendar";
    public static final Account ACCOUNT = new Account(ACCOUNT_NAME, ACCOUNT_TYPE);

    private static final String INT_NAME_PREFIX = "local_";

    // Projection array. Creating indices for this array instead of doing
    // dynamic lookups improves performance.
    public static final String[] PROJECTION = new String[]{
            CalendarContract.Calendars._ID, // 0
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, // 1
            CalendarContract.Calendars.CALENDAR_COLOR // 2
    };

    // The indices for the projection array above.
    public static final int PROJECTION_ID_INDEX = 0;
    public static final int PROJECTION_DISPLAY_NAME_INDEX = 1;
    public static final int PROJECTION_COLOR_INDEX = 2;

    private static Uri buildCalUri() {
        return CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE).build();
    }

    private static ContentValues buildContentValues(String displayName, int color) {
        String intName = INT_NAME_PREFIX + displayName;
        final ContentValues cv = new ContentValues();
        cv.put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME);
        cv.put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE);
        cv.put(CalendarContract.Calendars.NAME, intName);
        cv.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName);
        cv.put(CalendarContract.Calendars.CALENDAR_COLOR, color);
        cv.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        cv.put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME);
        cv.put(CalendarContract.Calendars.VISIBLE, 1);
        cv.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        return cv;
    }

    /**
     * Add calendar with given name and color
     */
    public static void addCalendar(Context context, String displayName, int color,
                                   final ContentResolver cr) {
        if (displayName == null) {
            throw new IllegalArgumentException();
        }

        /*
         * On Android < 4.1 create an account for our calendars. Using ACCOUNT_TYPE_LOCAL would
         * cause these bugs:
         * 
         * - On Android < 4.1: Selecting "Calendars to sync" in the calendar app it crashes with
         * NullPointerException. see http://code.google.com/p/android/issues/detail?id=27474
         * 
         * - On Android <= 2.3: Opening the calendar app will ask to create an account first even
         * when local calendars are present
         */
        if (BEFORE_JELLYBEAN) {
            if (addAccount(context)) {
                Log.d(Constants.TAG, "Account was added!");

                // wait until account is added asynchronously
                try {
                    Thread.sleep(2000);
                    Log.d(Constants.TAG, "after wait...");
                } catch (InterruptedException e) {
                    Log.e(Constants.TAG, "InterruptedException", e);
                }
            } else {
                Log.e(Constants.TAG, "There was a problem when trying to add the account!");
            }
        }

        // Add calendar
        final ContentValues cv = buildContentValues(displayName, color);
        Uri resultUri = cr.insert(buildCalUri(), cv);
        if (resultUri == null)
            throw new IllegalArgumentException();

        /*
         * If Cyanogenmod's Privacy Guard is enabled or Android 4.3 AppOps disallows "calendar read" for this app,
         * calendar query returns an empty query result -> List is empty even after adding a new Calendar.
         * This confuses users!
         *
         * If AppOps disallows "calendar write" for this app the resultUri is valid (ID=0), but the calendar is not inserted!
         */
        final String[] projection = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.NAME
        };
        final String selection = CalendarContract.Calendars.NAME + " = ?";
        Cursor cursor = cr.query(buildCalUri(), projection, selection,
                new String[]{cv.getAsString(CalendarContract.Calendars.NAME)}, null);
        try {
            if (cursor == null || !cursor.moveToFirst()) {
                Log.e(Constants.TAG, "Query is empty after insert! AppOps disallows access to read or write calendar?");
                throw new IllegalArgumentException();
            }
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
    }

    /**
     * Add account to Android system
     */
    private static boolean addAccount(Context context) {
        Log.d(Constants.TAG, "Adding account...");

        AccountManager am = AccountManager.get(context);
        if (am.addAccountExplicitly(CalendarController.ACCOUNT, null, null)) {
            // explicitly disable sync
            ContentResolver.setSyncAutomatically(ACCOUNT, CONTENT_AUTHORITY, false);
            ContentResolver.setIsSyncable(ACCOUNT, CONTENT_AUTHORITY, 0);

            return true;
        } else {
            return false;
        }
    }

    /**
     * @return true iff exactly one row is deleted
     */
    public static boolean deleteCalendar(long id, ContentResolver cr) {
        if (id < 0) {
            throw new IllegalArgumentException();
        }

        Uri calUri = ContentUris.withAppendedId(buildCalUri(), id);
        return cr.delete(calUri, null, null) == 1;
    }

    /**
     * Update values of existing calendar with id
     */
    public static void updateCalendar(long id, String displayName, int color, ContentResolver cr) {
        Uri calUri = ContentUris.withAppendedId(buildCalUri(), id);
        ContentValues cv = buildContentValues(displayName, color);
        cr.update(calUri, cv, null, null);
    }
}
