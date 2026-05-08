// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms.calendar;

/**
 * Lightweight representation of a single upcoming calendar event used to populate the
 * {@link CalendarEventPickerDialogFragment} list.
 */
public final class CalendarEvent {

    public final long id;
    public final String title;
    public final String calendarName;
    public final int calendarColor;
    /** Event start time in milliseconds since epoch (UTC). */
    public final long startMillis;

    public CalendarEvent(long id, String title, String calendarName,
                         int calendarColor, long startMillis) {
        this.id = id;
        this.title = title;
        this.calendarName = calendarName;
        this.calendarColor = calendarColor;
        this.startMillis = startMillis;
    }
}
