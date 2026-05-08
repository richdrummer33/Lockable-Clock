// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms.calendar;

import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.best.deskclock.R;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.uicomponents.CustomDialog;
import com.best.deskclock.utils.SdkUtils;
import com.best.deskclock.utils.ThemeUtils;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DialogFragment that shows upcoming calendar events (via {@link CalendarContract.Instances})
 * and lets the user pick one. After selection an offset picker is shown, and the result is
 * delivered to a registered {@link OnEventPickedListener}.
 */
public final class CalendarEventPickerDialogFragment extends DialogFragment {

    public static final String TAG = "calendar_event_picker";

    private static final String ARG_ALARM = "arg_alarm";

    /** UTC window: look 30 days ahead for upcoming events. */
    private static final long WINDOW_MS = 30L * 24 * 60 * 60 * 1000;

    /** Offset options in minutes: negative = before event, 0 = at start, positive = after. */
    private static final int[] OFFSET_MINUTES = {-120, -90, -60, -45, -30, -20, -15, -10, -5, 0, 5, 10, 15, 30, 60};

    private Context mContext;
    private Alarm mAlarm;
    private RecyclerView mRecyclerView;
    private TextView mEmptyView;
    private TextInputEditText mSearchEdit;
    private EventAdapter mAdapter;
    private AlertDialog mCurrentDialog;

    // Callback registered by the caller (stored in a static map to survive re-creation).
    private static final Map<String, OnEventPickedListener> sPendingListeners = new HashMap<>();

    private final ActivityResultLauncher<String> mPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    loadEvents();
                } else {
                    showPermissionDeniedMessage();
                }
            });

    // -------------------------------------------------------------------------
    // Public factory
    // -------------------------------------------------------------------------

    /**
     * Shows a new instance of the picker attached to {@code fm}.
     *
     * @param alarm    the alarm that will be updated when the user confirms an event + offset
     * @param listener callback invoked when the user confirms an event and offset
     */
    public static void show(FragmentManager fm, Alarm alarm, OnEventPickedListener listener) {
        String key = TAG + "_" + alarm.id;
        sPendingListeners.put(key, listener);

        Bundle args = new Bundle();
        args.putParcelable(ARG_ALARM, alarm);

        CalendarEventPickerDialogFragment frag = new CalendarEventPickerDialogFragment();
        frag.setArguments(args);
        frag.show(fm, TAG);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = requireContext();

        Bundle args = requireArguments();
        mAlarm = SdkUtils.isAtLeastAndroid13()
                ? args.getParcelable(ARG_ALARM, Alarm.class)
                : args.getParcelable(ARG_ALARM);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        @SuppressLint("InflateParams")
        View view = LayoutInflater.from(mContext)
                .inflate(R.layout.dialog_calendar_event_picker, null);

        mRecyclerView = view.findViewById(R.id.calendar_events_recycler);
        mEmptyView = view.findViewById(R.id.calendar_empty_view);
        mSearchEdit = view.findViewById(R.id.calendar_search_edit);

        SharedPreferences prefs = getDefaultSharedPreferences(mContext);
        Typeface typeface = ThemeUtils.loadFont(SettingsDAO.getGeneralFont(prefs));
        if (typeface != null) {
            mSearchEdit.setTypeface(typeface);
            mEmptyView.setTypeface(typeface);
        }

        mAdapter = new EventAdapter(typeface, this::onEventClicked);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.addItemDecoration(
                new DividerItemDecoration(mContext, DividerItemDecoration.VERTICAL));
        mRecyclerView.setAdapter(mAdapter);

        mSearchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mAdapter.filter(s.toString());
                updateEmptyView();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        mCurrentDialog = CustomDialog.create(
                mContext,
                null,
                null,
                getString(R.string.calendar_event_picker_title),
                null,
                view,
                null, null,
                getString(android.R.string.cancel),
                (d, w) -> dismiss(),
                null, null,
                null,
                CustomDialog.SoftInputMode.NONE
        );

        return mCurrentDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        checkPermissionAndLoad();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mCurrentDialog = null;
    }

    // -------------------------------------------------------------------------
    // Permission handling
    // -------------------------------------------------------------------------

    private void checkPermissionAndLoad() {
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED) {
            loadEvents();
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) {
            showRationale();
        } else {
            mPermissionLauncher.launch(Manifest.permission.READ_CALENDAR);
        }
    }

    private void showRationale() {
        new androidx.appcompat.app.AlertDialog.Builder(mContext)
                .setMessage(R.string.calendar_permission_rationale)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> mPermissionLauncher.launch(Manifest.permission.READ_CALENDAR))
                .setNegativeButton(android.R.string.cancel, (d, w) -> dismiss())
                .show();
    }

    private void showPermissionDeniedMessage() {
        View root = getView();
        if (root == null || !isAdded()) return;
        Snackbar.make(root, R.string.calendar_permission_denied, Snackbar.LENGTH_LONG)
                .setAction(R.string.go_to_settings, v -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", mContext.getPackageName(), null));
                    startActivity(intent);
                })
                .show();
    }

    // -------------------------------------------------------------------------
    // Loading events
    // -------------------------------------------------------------------------

    private void loadEvents() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            List<CalendarEvent> events = queryUpcomingEvents();
            handler.post(() -> {
                if (!isAdded()) return;
                mAdapter.setEvents(events);
                updateEmptyView();
            });
        });
    }

    private List<CalendarEvent> queryUpcomingEvents() {
        List<CalendarEvent> result = new ArrayList<>();
        ContentResolver cr = mContext.getContentResolver();

        long now = System.currentTimeMillis();
        long end = now + WINDOW_MS;

        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, now);
        ContentUris.appendId(builder, end);

        String[] projection = {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.CALENDAR_COLOR,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY,
        };

        try (Cursor cursor = cr.query(builder.build(), projection, null, null,
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (cursor == null) return result;
            int colId     = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID);
            int colTitle  = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE);
            int colCal    = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME);
            int colColor  = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_COLOR);
            int colBegin  = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN);
            int colAllDay = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY);

            while (cursor.moveToNext()) {
                if (cursor.getInt(colAllDay) != 0) continue; // skip all-day events: they have no specific start time for alarm offset calculation
                String title = cursor.getString(colTitle);
                if (title == null || title.isEmpty()) continue;
                result.add(new CalendarEvent(
                        cursor.getLong(colId),
                        title,
                        cursor.getString(colCal),
                        cursor.getInt(colColor),
                        cursor.getLong(colBegin)
                ));
            }
        } catch (SecurityException ignored) {
            // Permission revoked mid-query
        }
        return result;
    }

    private void updateEmptyView() {
        if (mAdapter.getItemCount() == 0) {
            mRecyclerView.setVisibility(View.GONE);
            mEmptyView.setVisibility(View.VISIBLE);
        } else {
            mRecyclerView.setVisibility(View.VISIBLE);
            mEmptyView.setVisibility(View.GONE);
        }
    }

    // -------------------------------------------------------------------------
    // Event selection → offset picker
    // -------------------------------------------------------------------------

    private void onEventClicked(CalendarEvent event) {
        showOffsetPicker(event);
    }

    private void showOffsetPicker(CalendarEvent event) {
        String[] labels = buildOffsetLabels();
        new androidx.appcompat.app.AlertDialog.Builder(mContext)
                .setTitle(R.string.calendar_offset_title)
                .setItems(labels, (d, which) -> {
                    int offsetMinutes = OFFSET_MINUTES[which];
                    deliverResult(event, offsetMinutes);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String[] buildOffsetLabels() {
        String[] labels = new String[OFFSET_MINUTES.length];
        for (int i = 0; i < OFFSET_MINUTES.length; i++) {
            int m = OFFSET_MINUTES[i];
            if (m < 0) {
                labels[i] = getString(R.string.calendar_offset_before, -m);
            } else if (m == 0) {
                labels[i] = getString(R.string.calendar_offset_at_start);
            } else {
                labels[i] = getString(R.string.calendar_offset_after, m);
            }
        }
        return labels;
    }

    private void deliverResult(CalendarEvent event, int offsetMinutes) {
        String key = TAG + "_" + (mAlarm != null ? mAlarm.id : "");
        OnEventPickedListener listener = sPendingListeners.remove(key);
        if (listener != null) {
            listener.onEventPicked(event, offsetMinutes);
        }
        dismiss();
    }

    // -------------------------------------------------------------------------
    // Callback interface
    // -------------------------------------------------------------------------

    /** Called when the user has chosen an event and an offset. */
    public interface OnEventPickedListener {
        /**
         * @param event         the calendar event chosen by the user
         * @param offsetMinutes signed offset in minutes (negative = before event start)
         */
        void onEventPicked(CalendarEvent event, int offsetMinutes);
    }

    // -------------------------------------------------------------------------
    // Inner RecyclerView adapter
    // -------------------------------------------------------------------------

    private static final class EventAdapter
            extends RecyclerView.Adapter<EventAdapter.VH> {

        interface OnClickListener {
            void onClick(CalendarEvent event);
        }

        private final Typeface mTypeface;
        private final OnClickListener mClickListener;
        private final List<CalendarEvent> mAll = new ArrayList<>();
        private final List<CalendarEvent> mFiltered = new ArrayList<>();
        private String mQuery = "";

        private static final SimpleDateFormat DATE_FMT =
                new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());

        EventAdapter(Typeface typeface, OnClickListener clickListener) {
            mTypeface = typeface;
            mClickListener = clickListener;
        }

        void setEvents(List<CalendarEvent> events) {
            mAll.clear();
            mAll.addAll(events);
            applyFilter();
        }

        void filter(String query) {
            mQuery = query.toLowerCase(Locale.getDefault()).trim();
            applyFilter();
        }

        @SuppressLint("NotifyDataSetChanged")
        private void applyFilter() {
            mFiltered.clear();
            if (mQuery.isEmpty()) {
                mFiltered.addAll(mAll);
            } else {
                for (CalendarEvent e : mAll) {
                    if (e.title.toLowerCase(Locale.getDefault()).contains(mQuery)
                            || (e.calendarName != null
                            && e.calendarName.toLowerCase(Locale.getDefault()).contains(mQuery))) {
                        mFiltered.add(e);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_calendar_event, parent, false);
            return new VH(v, mTypeface);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            CalendarEvent event = mFiltered.get(position);
            holder.bind(event);
            holder.itemView.setOnClickListener(v -> mClickListener.onClick(event));
        }

        @Override
        public int getItemCount() {
            return mFiltered.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            private final View colorDot;
            private final TextView titleView;
            private final TextView subtitleView;

            VH(@NonNull View itemView, Typeface typeface) {
                super(itemView);
                colorDot = itemView.findViewById(R.id.calendar_color_dot);
                titleView = itemView.findViewById(R.id.event_title);
                subtitleView = itemView.findViewById(R.id.event_time_and_calendar);
                if (typeface != null) {
                    titleView.setTypeface(typeface);
                    subtitleView.setTypeface(typeface);
                }
            }

            void bind(CalendarEvent event) {
                titleView.setText(event.title);
                String timeStr = DATE_FMT.format(new Date(event.startMillis));
                String sub = event.calendarName != null
                        ? timeStr + "  ·  " + event.calendarName
                        : timeStr;
                subtitleView.setText(sub);

                // Color dot
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(event.calendarColor != 0 ? event.calendarColor : 0xFF888888);
                colorDot.setBackground(dot);
            }
        }
    }
}
