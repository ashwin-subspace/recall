package com.recall;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.recall.data.Db;
import com.recall.data.Model;
import com.recall.util.Alarms;
import com.recall.util.Prefs;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private TextView greeting, kicker, title, meta, note, start, free, sheetSub, streak;
    private LinearLayout statRow;
    private View homeHost, scrim, sheet;
    private TextView sheetEmpty;
    private ListView sessionList;

    private final List<Model.Sess> sessions = new ArrayList<Model.Sess>();
    private SessionAdapter sessionAdapter;

    private float progress;          // 0 = sheet closed, 1 = sheet open
    private float sheetTravel = 1f;  // px the sheet moves between those states
    private boolean sheetOpen;
    private boolean askedNotifications;
    private String query = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.recall.util.Themes.apply(this);
        setContentView(R.layout.activity_main);

        greeting = (TextView) findViewById(R.id.greeting);
        streak = (TextView) findViewById(R.id.streak);
        kicker = (TextView) findViewById(R.id.focusKicker);
        title = (TextView) findViewById(R.id.focusTitle);
        meta = (TextView) findViewById(R.id.focusMeta);
        note = (TextView) findViewById(R.id.focusNote);
        start = (TextView) findViewById(R.id.btnStart);
        free = (TextView) findViewById(R.id.btnFree);
        statRow = (LinearLayout) findViewById(R.id.statRow);
        homeHost = findViewById(R.id.homeHost);
        scrim = findViewById(R.id.scrim);
        sheet = findViewById(R.id.sheet);
        sheetSub = (TextView) findViewById(R.id.sheetSub);
        sheetEmpty = (TextView) findViewById(R.id.sheetEmpty);
        sessionList = (ListView) findViewById(R.id.sessionList);

        findViewById(R.id.btnMind).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, MindActivity.class));
            }
        });
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        free.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { open(false); }
        });

        setUpSheet();
        if (!Prefs.onboarded(this) && !Prefs.hasKey(this)) {
            startActivity(new Intent(this, OnboardActivity.class));
            finish();
            return;
        }
        handleDeepLink(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleDeepLink(i);
    }

    /** Tapping the daily nudge should land in the session, not on the home screen. */
    private void handleDeepLink(Intent i) {
        if (i == null || !i.getBooleanExtra("openTarget", false)) return;
        i.removeExtra("openTarget");
        if (Prefs.hasKey(this) && Db.get(this).focus() != null) open(true);
    }

    @Override protected void onResume() {
        super.onResume();
        Db.get(this).dropAbandoned();
        render();
        loadSessions();
        FocusWidget.refresh(this);
        Alarms.arm(this);
        askForNotificationsOnce();
    }

    /**
     * Asked in context -- only once the learner has a key and therefore something worth
     * being reminded about -- rather than on first launch when it means nothing to them.
     */
    private void askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (!Prefs.hasKey(this) || !Prefs.remindOn(this)) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (askedNotifications) return;
        askedNotifications = true;
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 55);
    }

    private void open(boolean targeted) {
        if (!Prefs.hasKey(this)) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        Intent i = new Intent(this, SessionActivity.class);
        i.putExtra("targeted", targeted);
        growFrom(start, i);
    }

    /**
     * Grow the new screen out of the card that was tapped. makeScaleUpAnimation is the
     * framework's own version of a shared-element morph and needs no AndroidX.
     */
    private void growFrom(View source, Intent i) {
        if (source == null || reducedMotion()) { startActivity(i); return; }
        android.app.ActivityOptions o = android.app.ActivityOptions.makeScaleUpAnimation(
                source, 0, 0, source.getWidth(), source.getHeight());
        startActivity(i, o.toBundle());
    }

    private boolean reducedMotion() {
        try {
            return android.provider.Settings.Global.getFloat(getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) { return false; }
    }

    private void resume(Model.Sess s) {
        Intent i = new Intent(this, SessionActivity.class);
        i.putExtra("sessId", s.id);
        startActivity(i);
    }

    private void resumeFrom(View row, Model.Sess s) {
        Intent i = new Intent(this, SessionActivity.class);
        i.putExtra("sessId", s.id);
        growFrom(row, i);
    }

    private void render() {
        Db db = Db.get(this);
        String who = Prefs.who(this);
        boolean keyed = Prefs.hasKey(this);
        Model.Concept f = db.focus();

        greeting.setText(who.length() > 0 ? "Welcome back, " + who + "." : "Welcome back.");

        int days = db.streak();
        if (days >= 2) {
            streak.setText(days + " day streak");
            streak.setVisibility(View.VISIBLE);
        } else {
            streak.setVisibility(View.GONE);
        }
        note.setVisibility(View.GONE);
        free.setVisibility(View.VISIBLE);

        if (!keyed) {
            kicker.setText("FIRST THINGS FIRST");
            title.setText("Connect Recall");
            meta.setText("Recall needs a free API key before it can tutor anyone. "
                    + "It takes about a minute.");
            start.setText("Open setup");
            free.setVisibility(View.GONE);
            start.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                }
            });
        } else if (f == null) {
            kicker.setText("NOTHING REMEMBERED YET");
            title.setText("Let's find out what you know");
            meta.setText("After the first session I will start keeping track of what sticks "
                    + "and what does not.");
            start.setText("Start first session");
            free.setVisibility(View.GONE);
            start.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { open(false); }
            });
        } else {
            boolean due = f.nextDue <= System.currentTimeMillis();
            kicker.setText(due ? "DUE FOR REVIEW" : "TODAY'S FOCUS");
            title.setText(f.label);
            meta.setText("Confidence " + f.effective + " of 100  ·  last touched "
                    + Db.ago(f.lastSeen));
            if (!f.gaps.isEmpty()) {
                note.setText("Still open: " + f.gaps.get(0));
                note.setVisibility(View.VISIBLE);
            }
            start.setText("Pick this up");
            start.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { open(true); }
            });
        }
        stats(db);
    }

    private void stats(Db db) {
        statRow.removeAllViews();
        addStat(String.valueOf(db.conceptCount()), "tracked");
        addStat(String.valueOf(db.dueCount()), "due now");
        addStat(String.valueOf(db.sessionCount()), "sessions");
    }

    private void addStat(String value, String label) {
        View v = getLayoutInflater().inflate(R.layout.part_stat, statRow, false);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (statRow.getChildCount() > 0) lp.leftMargin = dp(10);
        v.setLayoutParams(lp);
        ((TextView) v.findViewById(R.id.statVal)).setText(value);
        ((TextView) v.findViewById(R.id.statLbl)).setText(label);
        statRow.addView(v);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- the sessions sheet -----------------------------------------------

    private void setUpSheet() {
        sessionAdapter = new SessionAdapter();
        sessionList.setAdapter(sessionAdapter);
        sessionList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                resumeFrom(v, sessions.get(pos));
            }
        });
        sessionList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                sessionMenu(sessions.get(pos));
                return true;
            }
        });

        final android.widget.EditText search =
                (android.widget.EditText) findViewById(R.id.sheetSearch);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable e) {
                query = e.toString();
                loadSessions();
            }
        });

        findViewById(R.id.btnCloseSheet).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { animateTo(0f); }
        });
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { animateTo(0f); }
        });

        // The home layer keeps rounded corners so it reads as a card sliding back.
        homeHost.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(30));
            }
        });

        sheet.post(new Runnable() {
            public void run() {
                int h = ((View) sheet.getParent()).getHeight();
                sheetTravel = h - openTop(h);
                sheet.setTranslationY(h);
                applyProgress(0f);
            }
        });

        findViewById(R.id.dragCatcher).setOnTouchListener(new DragUp());
        findViewById(R.id.sheetGrip).setOnTouchListener(new DragDown());
    }

    /** Where the sheet's top edge sits when open -- almost, but not quite, full screen. */
    private int openTop(int parentHeight) {
        return (int) (parentHeight * 0.11f);
    }

    /**
     * Drives every part of the transition from one number, so a finger drag and the
     * settle animation cannot disagree with each other.
     */
    private void applyProgress(float p) {
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        progress = p;

        int h = ((View) sheet.getParent()).getHeight();
        int top = openTop(h);
        sheet.setTranslationY(h - (h - top) * p);

        // The landing page shrinks back as the sheet comes forward.
        float scale = 1f - 0.09f * p;
        homeHost.setPivotX(homeHost.getWidth() / 2f);
        homeHost.setPivotY(h * 0.12f);
        homeHost.setScaleX(scale);
        homeHost.setScaleY(scale);
        homeHost.setClipToOutline(p > 0.01f);

        // Kept out of the hierarchy at rest so it can never flash on launch.
        sheet.setVisibility(p > 0.001f ? View.VISIBLE : View.INVISIBLE);
        scrim.setVisibility(p > 0.001f ? View.VISIBLE : View.GONE);
        scrim.setAlpha(0.45f * p);

        // Real blur needs Android 12. Below that the scale and scrim carry the effect.
        if (Build.VERSION.SDK_INT >= 31) {
            if (p < 0.02f) {
                homeHost.setRenderEffect(null);
            } else {
                float r = 1f + 22f * p;
                homeHost.setRenderEffect(
                        RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            }
        }
    }

    private void animateTo(final float target) {
        ValueAnimator a = ValueAnimator.ofFloat(progress, target);
        a.setDuration(target > progress ? 300 : 260);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) {
                applyProgress(((Float) v.getAnimatedValue()).floatValue());
            }
        });
        a.start();
        sheetOpen = target > 0.5f;
    }

    /** Swipe up from the bottom edge, or just tap the handle. */
    private class DragUp implements View.OnTouchListener {
        private float startY;
        private long startT;
        private boolean moved;

        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = e.getRawY();
                    startT = System.currentTimeMillis();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float up = startY - e.getRawY();
                    if (!moved && up > dp(6)) moved = true;
                    if (moved) applyProgress(up / sheetTravel);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float up = startY - e.getRawY();
                    boolean flick = System.currentTimeMillis() - startT < 250 && up > dp(50);
                    if (!moved) animateTo(1f);            // treated as a tap
                    else animateTo(progress > 0.32f || flick ? 1f : 0f);
                    return true;
                }
            }
            return false;
        }
    }

    /** Drag the sheet back down by its grip. */
    private class DragDown implements View.OnTouchListener {
        private float startY;

        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    applyProgress(1f - (e.getRawY() - startY) / sheetTravel);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animateTo(progress < 0.68f ? 0f : 1f);
                    return true;
            }
            return false;
        }
    }

    @Override public void onBackPressed() {
        if (sheetOpen || progress > 0.01f) { animateTo(0f); return; }
        super.onBackPressed();
    }

    private void loadSessions() {
        sessions.clear();
        sessions.addAll(Db.get(this).search(query));
        sessionAdapter.notifyDataSetChanged();

        boolean empty = sessions.isEmpty();
        sheetEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        sessionList.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            sheetEmpty.setText(query.length() > 0
                    ? "Nothing matches \"" + query + "\"."
                    : "No sessions yet. Finish one and it will be waiting here.");
            sheetSub.setText("");
        } else {
            sheetSub.setText(sessions.size()
                    + (sessions.size() == 1 ? " session  ·  tap to carry it on"
                                            : " sessions  ·  tap one to carry it on")
                    + "  ·  hold to rename");
        }
    }


    private void sessionMenu(final Model.Sess s) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(s.title)
                .setItems(new String[]{"Carry it on", "Rename", "Delete"},
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                if (which == 0) resume(s);
                                else if (which == 1) renameSession(s);
                                else deleteSession(s);
                            }
                        })
                .show();
    }

    private void renameSession(final Model.Sess s) {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setText(s.title);
        in.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rename session")
                .setView(in)
                .setPositiveButton("Rename",
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                Db.get(MainActivity.this)
                                        .renameSession(s.id, in.getText().toString());
                                loadSessions();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSession(final Model.Sess s) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete this session?")
                .setMessage("The transcript goes. What it taught Recall about you stays "
                        + "in the learner model.")
                .setPositiveButton("Delete",
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                Db.get(MainActivity.this).dropSession(s.id);
                                loadSessions();
                                render();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }
    private class SessionAdapter extends BaseAdapter {
        public int getCount() { return sessions.size(); }
        public Object getItem(int i) { return sessions.get(i); }
        public long getItemId(int i) { return sessions.get(i).id; }

        public View getView(int i, View reuse, ViewGroup parent) {
            View v = reuse;
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_recent, parent, false);
            Model.Sess s = sessions.get(i);
            String title = s.title == null ? "" : s.title.trim();
            if (title.length() == 0) title = s.ended > 0 ? "Session" : "Unfinished";
            String summary = s.summary == null ? "" : s.summary;
            if (summary.length() == 0 && s.ended == 0) {
                summary = "Not folded into memory yet. Tap to carry it on.";
            }
            ((TextView) v.findViewById(R.id.rTitle)).setText(title);
            ((TextView) v.findViewById(R.id.rSummary)).setText(summary);
            ((TextView) v.findViewById(R.id.rWhen)).setText(s.ended > 0 ? Db.ago(s.ended) : "in progress");
            return v;
        }
    }
}
