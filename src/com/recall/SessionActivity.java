package com.recall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.recall.data.Db;
import com.recall.data.Model;
import com.recall.net.Ai;
import com.recall.net.Tutor;
import com.recall.util.Bg;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class SessionActivity extends Activity {

    private static final int REQ_VOICE = 41;

    /** The activity currently on screen, so a reply that lands during rotation is not lost. */
    private static SessionActivity current;
    /** Session whose tutor request is already in flight. Stops a recreated screen asking twice. */
    private static long inflight = -1;
    private static long savingId = -1;
    private static long cancelled = -1;

    private ListView chat;
    private EditText input;
    private TextView send, mic, thinking, titleView, retry;
    private ChatAdapter adapter;

    private final List<Model.Msg> shown = new ArrayList<Model.Msg>();
    private long sessId;
    private boolean targeted;
    private String opener;
    private boolean busy;
    private boolean closing;
    private boolean resumed;
    private boolean resultsUp;
    /** Messages that already belong to this session. Speech after this is the new part. */
    private int keep;
    private int lastAnimated = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.recall.util.Themes.apply(this);
        setContentView(R.layout.activity_session);

        targeted = getIntent().getBooleanExtra("targeted", false);
        long existing = -1;
        boolean fresh = false;
        if (b != null && b.getLong("sessId", -1) > 0) {
            existing = b.getLong("sessId");
            fresh = b.getBoolean("fresh", false);
        } else {
            existing = getIntent().getLongExtra("sessId", -1);
            fresh = getIntent().getBooleanExtra("fresh", false);
        }
        if (existing > 0 && Db.get(this).session(existing) == null) existing = -1;

        if (existing > 0) {
            sessId = existing;
            resumed = !fresh;
            shown.addAll(Db.get(this).msgs(sessId));
        } else {
            sessId = Db.get(this).startSession();
            resumed = false;
            fresh = true;
            getIntent().putExtra("sessId", sessId);
            getIntent().putExtra("fresh", true);
        }
        opener = Tutor.opener(this, targeted, resumed);

        Model.Sess loaded = Db.get(this).session(sessId);
        boolean endedNow = loaded != null && loaded.ended > 0;
        if (b != null && b.containsKey("keep")) keep = b.getInt("keep");
        else keep = (resumed || endedNow) ? shown.size() : 0;
        if (resumed || shown.size() > 0) lastAnimated = shown.size();

        chat = (ListView) findViewById(R.id.chat);
        input = (EditText) findViewById(R.id.input);
        send = (TextView) findViewById(R.id.btnSend);
        mic = (TextView) findViewById(R.id.btnMic);
        thinking = (TextView) findViewById(R.id.thinking);
        retry = (TextView) findViewById(R.id.retry);
        retry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { retry.setVisibility(View.GONE); turn(); }
        });
        titleView = (TextView) findViewById(R.id.sessTitle);
        if (endedNow) {
            String t = loaded.title == null ? "" : loaded.title.trim();
            titleView.setText(t.length() > 0 ? t : "Earlier session");
        } else if (resumed) {
            String t = loaded == null || loaded.title == null ? "" : loaded.title.trim();
            titleView.setText(t.length() > 0 ? t : "Unfinished");
        } else {
            Model.Concept f = Db.get(this).focus();
            titleView.setText(targeted && f != null ? f.label : "Open session");
        }

        adapter = new ChatAdapter();
        chat.setAdapter(adapter);
        if (shown.size() > 0) chat.setSelection(adapter.getCount() - 1);

        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) { refreshSend(); }
        });
        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendTyped(); }
        });
        mic.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { speak(); }
        });
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onBackPressed(); }
        });
        findViewById(R.id.btnEnd).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finishSession(); }
        });
        refreshSend();

        if (!endedNow && (inflight == sessId || (!resumed && shown.isEmpty()))) turn();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("sessId", sessId);
        out.putInt("keep", keep);
        out.putBoolean("fresh", !resumed);
        getIntent().putExtra("sessId", sessId);
        getIntent().putExtra("fresh", !resumed);
    }

    @Override protected void onResume() {
        super.onResume();
        current = this;
        syncFromDb();
    }

    @Override protected void onPause() {
        if (current == this) current = null;
        super.onPause();
    }

    /** Pick up a reply that was written while this screen was not the one in front. */
    private void syncFromDb() {
        if (resultsUp || chat == null || adapter == null) return;
        if (savingId == sessId) {
            busy = true;
            refreshSend();
            stopPulse();
            thinking.setText("saving what you learned");
            thinking.setVisibility(View.VISIBLE);
            retry.setVisibility(View.GONE);
            return;
        }
        List<Model.Msg> now = Db.get(this).msgs(sessId);
        if (now.size() != shown.size()) {
            shown.clear();
            shown.addAll(now);
            lastAnimated = shown.size();
            adapter.notifyDataSetChanged();
            chat.setSelection(Math.max(0, adapter.getCount() - 1));
            retry.setVisibility(View.GONE);
        }
        if (inflight == sessId) {
            setBusy(true);
            retry.setVisibility(View.GONE);
            return;
        }
        Model.Sess s = Db.get(this).session(sessId);
        boolean ended = s != null && s.ended > 0;
        if (!ended && !shown.isEmpty() && "user".equals(shown.get(shown.size() - 1).role)
                && retry.getVisibility() != View.VISIBLE) {
            retry.setText("The reply did not come back.\n\nTap to try that again.");
            retry.setVisibility(View.VISIBLE);
        }
    }

    // ---- conversation -----------------------------------------------------

    private void sendTyped() {
        String text = input.getText().toString().trim();
        if (text.length() == 0 || busy) return;
        input.setText("");
        send.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        add("user", text);
        turn();
    }

    private void add(String role, String text) {
        Model.Msg m = new Model.Msg(role, text, System.currentTimeMillis());
        shown.add(m);
        Db.get(this).addMsg(sessId, role, text);
        adapter.notifyDataSetChanged();
        chat.setSelection(adapter.getCount() - 1);
    }

    /** Build the history sent to the model: the hidden opener, then everything on screen. */
    private List<Model.Msg> wire() {
        List<Model.Msg> a = new ArrayList<Model.Msg>();
        a.add(new Model.Msg("user", opener, 0));
        a.addAll(shown);
        return a;
    }

    private void turn() {
        if (cancelled == sessId) return;
        if (inflight == sessId) {
            setBusy(true);
            return;
        }
        inflight = sessId;
        retry.setVisibility(View.GONE);
        setBusy(true);
        final long sid = sessId;
        final Context app = getApplicationContext();
        final String system = Tutor.session(this, tutorTurns(), targeted, resumed);
        final List<Model.Msg> history = wire();
        Bg.go(new Bg.Work<String>() {
            public String run() throws Exception {
                return Ai.chat(app, system, history);
            }
        }, new Bg.Done<String>() {
            public void ok(String reply) { deliverReply(sid, reply); }
            public void fail(Exception e) { deliverTurnFailure(sid, e); }
        });
    }

    private void deliverReply(long sid, String reply) {
        if (cancelled == sid) {
            if (inflight == sid) inflight = -1;
            return;
        }
        if (inflight == sid) inflight = -1;
        SessionActivity ui = live(sid);
        if (ui == null) {
            Db.get(getApplicationContext()).addMsg(sid, "assistant", reply);
            return;
        }
        ui.retry.setVisibility(View.GONE);
        ui.setBusy(false);
        ui.add("assistant", reply);
    }

    private void deliverTurnFailure(long sid, Exception e) {
        if (cancelled == sid) {
            if (inflight == sid) inflight = -1;
            return;
        }
        if (inflight == sid) inflight = -1;
        SessionActivity ui = live(sid);
        if (ui == null) return;
        ui.setBusy(false);
        ui.offerRetry(e);
    }

    private static SessionActivity live(long sid) {
        SessionActivity ui = current;
        if (ui == null || ui.sessId != sid || ui.isDestroyed()) return null;
        return ui;
    }

    /** How many times the tutor has spoken. Drives the teach-now enforcement. */
    private int tutorTurns() {
        int n = 0;
        for (int i = 0; i < shown.size(); i++) {
            if ("assistant".equals(shown.get(i).role)) n++;
        }
        return n;
    }

    private void setBusy(boolean b) {
        busy = b;
        thinking.setVisibility(b ? View.VISIBLE : View.GONE);
        if (b) startPulse(); else stopPulse();
        refreshSend();
    }

    private void refreshSend() {
        boolean on = !busy && input.getText().toString().trim().length() > 0;
        send.setEnabled(on);
        send.setAlpha(on ? 1f : 0.38f);
    }

    /**
     * A failed turn leaves the learner's message sitting there unanswered. Rather than a
     * dialog that dead-ends, put the reason inline with a tap target that re-sends the
     * same history -- nothing is lost and nothing has to be retyped.
     */
    private void offerRetry(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.length() == 0) msg = "That did not go through.";
        retry.setText(msg + "\n\nTap to try that again.");
        retry.setVisibility(View.VISIBLE);
    }

    // ---- the waiting state ------------------------------------------------

    private final android.os.Handler pulse = new android.os.Handler();
    private int pulseStep;

    private final Runnable pulseTick = new Runnable() {
        public void run() {
            pulseStep = (pulseStep + 1) % 4;
            StringBuilder s = new StringBuilder("thinking");
            for (int i = 0; i < pulseStep; i++) s.append(" .");
            thinking.setText(s.toString());
            pulse.postDelayed(this, 420);
        }
    };

    private void startPulse() {
        stopPulse();
        pulseStep = 0;
        thinking.setText("thinking");
        if (!reducedMotion()) pulse.postDelayed(pulseTick, 420);
    }

    private void stopPulse() {
        pulse.removeCallbacks(pulseTick);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- ending: fold the session back into memory ------------------------

    private boolean userSpokeSince(int from) {
        for (int i = Math.max(0, from); i < shown.size(); i++) {
            if ("user".equals(shown.get(i).role)) return true;
        }
        return false;
    }

    /** Anything new since this screen opened, or since the last successful save. */
    private boolean learnerSpoke() { return userSpokeSince(keep); }

    private boolean transcriptHasLearner() { return userSpokeSince(0); }

    private void finishSession() {
        if (busy || closing || savingId == sessId) return;
        if (!transcriptHasLearner()) {
            if (!resumed && keep == 0) abandonFresh();
            else finish();
            return;
        }
        Model.Sess existing = Db.get(this).session(sessId);
        if (!learnerSpoke() && existing != null && existing.ended > 0) {
            finish();
            return;
        }
        closing = true;
        busy = true;
        refreshSend();
        stopPulse();
        thinking.setText("saving what you learned");
        thinking.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);

        final long sid = sessId;
        final Context app = getApplicationContext();
        final String system = Tutor.memory(this);
        final String said = transcript();
        savingId = sid;
        Bg.go(new Bg.Work<JSONObject>() {
            public JSONObject run() throws Exception {
                List<Model.Msg> m = new ArrayList<Model.Msg>();
                m.add(new Model.Msg("user", said, 0));
                return Ai.consolidate(app, system, m);
            }
        }, new Bg.Done<JSONObject>() {
            public void ok(JSONObject update) { deliverSave(sid, update); }
            public void fail(Exception e) { deliverSaveFailure(sid, e); }
        });
    }

    private void deliverSave(long sid, JSONObject update) {
        if (savingId == sid) savingId = -1;
        SessionActivity ui = live(sid);
        if (ui == null) {
            try { apply(update); } catch (RuntimeException ignored) { }
            return;
        }
        ui.closing = false;
        ui.setBusy(false);
        try {
            ui.apply(update);
        } catch (RuntimeException e) {
            ui.problemSaving(e);
            return;
        }
        if (!ui.isFinishing()) ui.showResults();
    }

    private void deliverSaveFailure(long sid, Exception e) {
        if (savingId == sid) savingId = -1;
        SessionActivity ui = live(sid);
        if (ui == null) return;
        ui.closing = false;
        ui.setBusy(false);
        ui.thinking.setText("thinking");
        ui.problemSaving(e);
    }

    private String transcript() {
        StringBuilder b = new StringBuilder("TRANSCRIPT OF THE SESSION THAT JUST ENDED\n\n");
        for (int i = 0; i < shown.size(); i++) {
            Model.Msg m = shown.get(i);
            b.append("user".equals(m.role) ? "LEARNER: " : "TUTOR: ");
            b.append(m.text).append("\n\n");
        }
        return b.toString();
    }

    private final List<String> movedLabel = new ArrayList<String>();
    private final List<int[]> movedFromTo = new ArrayList<int[]>();
    private final List<String> gapsOpened = new ArrayList<String>();
    private final List<String> gapsClosed = new ArrayList<String>();
    private String resultTitle = "", resultSummary = "";

    private void apply(JSONObject u) {
        Db db = Db.get(this);
        if (u == null) {
            saveTranscriptOnly();
            return;
        }
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            db.currentSession = sessId;
            movedLabel.clear(); movedFromTo.clear(); gapsOpened.clear(); gapsClosed.clear();
            HashSet<String> once = new HashSet<String>();

            JSONArray cs = u.optJSONArray("concepts");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    if (c == null) continue;
                    String label = c.optString("label", "").trim();
                    if (label.length() == 0 || !once.add(Db.canon(label))) continue;
                    int after = c.optInt("confidence", 40);
                    Model.Concept was = db.byLabel(label);
                    int before = was == null ? -1 : was.effective;
                    if (db.touchConcept(label, after) < 0) continue;
                    movedLabel.add(label);
                    movedFromTo.add(new int[]{before, after});
                }
            }
            JSONArray resolved = u.optJSONArray("gaps_resolved");
            if (resolved != null) {
                for (int i = 0; i < resolved.length(); i++) {
                    String t = textOf(resolved, i);
                    if (t.length() == 0) continue;
                    db.resolveGap(t);
                    gapsClosed.add(t);
                }
            }
            JSONArray opened = u.optJSONArray("gaps_opened");
            if (opened != null) {
                for (int i = 0; i < opened.length(); i++) {
                    JSONObject g = opened.optJSONObject(i);
                    String concept;
                    String gap;
                    if (g != null) {
                        concept = g.optString("concept", "").trim();
                        gap = g.optString("gap", "").trim();
                        if (gap.length() == 0) gap = g.optString("text", "").trim();
                    } else {
                        Model.Concept f = db.focus();
                        concept = f == null ? "" : f.label;
                        gap = textOf(opened, i);
                    }
                    if (gap.length() == 0) continue;
                    db.openGap(concept, gap);
                    gapsOpened.add(gap);
                }
            }
            String title = u.optString("title", "").trim();
            String summary = u.optString("summary", "").trim();
            if (title.length() == 0) title = fallbackTitle();
            if (summary.length() == 0) summary = "Saved. Nothing new stood out in this one.";
            db.endSession(sessId, title, summary);
            d.setTransactionSuccessful();
            resultTitle = title;
            resultSummary = summary;
            keep = shown.size();
        } finally {
            db.currentSession = 0;
            d.endTransaction();
        }
        FocusWidget.refresh(this);
    }

    private static String textOf(JSONArray arr, int i) {
        Object item = arr.opt(i);
        if (item instanceof String) return ((String) item).trim();
        if (item instanceof JSONObject) {
            JSONObject o = (JSONObject) item;
            String g = o.optString("gap", "").trim();
            if (g.length() == 0) g = o.optString("text", "").trim();
            return g;
        }
        String t = arr.optString(i, "");
        return t == null ? "" : t.trim();
    }

    private void problemSaving(Exception e) {
        if (isFinishing() || isDestroyed()) return;
        String why = e.getMessage();
        if (why == null || why.length() == 0) why = "Something went wrong.";
        new AlertDialog.Builder(this)
                .setTitle("Could not save this session")
                .setMessage(why + "\n\nThe conversation is still on this phone. Try again, "
                        + "save the transcript without updating memory, or keep going.")
                .setPositiveButton("Try again", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finishSession(); }
                })
                .setNeutralButton("Save transcript", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { saveTranscriptOnly(); }
                })
                .setNegativeButton("Keep going", null)
                .show();
    }

    /** Keep the words. Memory stays as it was when the model call could not finish. */
    private void saveTranscriptOnly() {
        Db db = Db.get(this);
        Model.Sess s = db.session(sessId);
        if (s == null || s.ended == 0) {
            db.endSession(sessId, fallbackTitle(),
                    "Saved on this phone. Recall could not update its memory of you, "
                            + "so no concepts were changed.");
            FocusWidget.refresh(this);
        }
        keep = shown.size();
        finish();
    }

    private String fallbackTitle() {
        CharSequence t = titleView == null ? null : titleView.getText();
        String s = t == null ? "" : t.toString().trim();
        if (s.length() == 0 || "Open session".equals(s) || "Earlier session".equals(s)
                || "Unfinished".equals(s)) return "Session";
        return s;
    }

    private void abandonFresh() {
        cancelled = sessId;
        if (inflight == sessId) inflight = -1;
        if (savingId == sessId) savingId = -1;
        Db.get(this).dropSession(sessId);
        finish();
    }

    private void discard() {
        Model.Sess s = Db.get(this).session(sessId);
        boolean ended = s != null && s.ended > 0;
        if (ended || keep > 0) {
            Db.get(this).trimMessagesAfter(sessId, keep);
            if (!ended && Db.get(this).msgs(sessId).isEmpty()) Db.get(this).dropSession(sessId);
            finish();
            return;
        }
        abandonFresh();
    }

    @Override public void onBackPressed() {
        if (resultsUp) { finish(); return; }
        if (closing || savingId == sessId) return;
        if (!learnerSpoke()) {
            Model.Sess s = Db.get(this).session(sessId);
            boolean ended = s != null && s.ended > 0;
            if (ended || resumed || keep > 0) finish();
            else abandonFresh();
            return;
        }
        Model.Sess s = Db.get(this).session(sessId);
        boolean ended = s != null && s.ended > 0;
        String message = (ended || keep > 0)
                ? "End it and Recall will update what it remembers. "
                    + "Discard drops only what you added just now."
                : "End it properly and I will remember what happened. "
                    + "Discard it and none of it is kept.";
        String discardLabel = (ended || keep > 0) ? "Discard new part" : "Discard";
        new AlertDialog.Builder(this)
                .setTitle("Leave this session?")
                .setMessage(message)
                .setPositiveButton("End and save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finishSession(); }
                })
                .setNegativeButton(discardLabel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { discard(); }
                })
                .setNeutralButton("Cancel", null)
                .show();
    }


    // ---- what the session actually changed --------------------------------

    /**
     * Consolidation used to announce itself with a one-line toast, which threw away the
     * most interesting moment in the app. Show the learner what moved instead.
     */
    private void showResults() {
        if (isFinishing() || isDestroyed()) return;
        final View chatHost = findViewById(R.id.chatHost);
        final View scrim = findViewById(R.id.resScrim);
        final View card = findViewById(R.id.results);

        ((TextView) findViewById(R.id.resTitle)).setText(resultTitle);
        ((TextView) findViewById(R.id.resSummary)).setText(resultSummary);

        LinearLayout moved = (LinearLayout) findViewById(R.id.resMoved);
        moved.removeAllViews();
        for (int i = 0; i < movedLabel.size(); i++) {
            int[] fromTo = movedFromTo.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_delta, moved, false);
            ((TextView) row.findViewById(R.id.dLabel)).setText(movedLabel.get(i));
            ((TextView) row.findViewById(R.id.dMove)).setText(fromTo[0] < 0
                    ? "new · " + fromTo[1]
                    : fromTo[0] + " → " + fromTo[1]);
            moved.addView(row);
            animateBar(row.findViewById(R.id.dBar), Math.max(0, fromTo[0]), fromTo[1], i);
        }
        findViewById(R.id.resMovedHead).setVisibility(
                movedLabel.isEmpty() ? View.GONE : View.VISIBLE);

        fillList(R.id.resGapsHead, R.id.resGaps, gapsOpened);
        fillList(R.id.resClosedHead, R.id.resClosed, gapsClosed);

        findViewById(R.id.resDone).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.resMore).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { hideResults(); }
        });

        card.setTranslationY(card.getHeight() > 0 ? card.getHeight() : 2000);
        card.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        resultsUp = true;

        long dur = reducedMotion() ? 120 : 300;
        card.animate().translationY(0).setDuration(dur)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        scrim.animate().alpha(0.5f).setDuration(dur).start();
        chatHost.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.6f).setDuration(dur).start();
    }

    private void hideResults() {
        final View chatHost = findViewById(R.id.chatHost);
        final View scrim = findViewById(R.id.resScrim);
        final View card = findViewById(R.id.results);
        long dur = reducedMotion() ? 120 : 260;
        resultsUp = false;
        closing = false;
        card.animate().translationY(card.getHeight()).setDuration(dur).start();
        scrim.animate().alpha(0f).setDuration(dur).start();
        chatHost.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(dur).start();
        thinking.setText("thinking");
        setBusy(false);
    }

    private void fillList(int headId, int bodyId, List<String> items) {
        TextView head = (TextView) findViewById(headId);
        TextView body = (TextView) findViewById(bodyId);
        if (items.isEmpty()) {
            head.setVisibility(View.GONE);
            body.setVisibility(View.GONE);
            return;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append("\n");
            b.append("· ").append(items.get(i));
        }
        body.setText(b.toString());
        head.setVisibility(View.VISIBLE);
        body.setVisibility(View.VISIBLE);
    }

    private void animateBar(final View bar, final int from, final int to, int index) {
        final View track = (View) bar.getParent();
        track.post(new Runnable() {
            public void run() {
                final int w = track.getWidth();
                if (reducedMotion()) {
                    ViewGroup.LayoutParams lp = bar.getLayoutParams();
                    lp.width = (int) (w * (to / 100f));
                    bar.setLayoutParams(lp);
                    return;
                }
                android.animation.ValueAnimator a =
                        android.animation.ValueAnimator.ofFloat(from / 100f, to / 100f);
                a.setDuration(600);
                a.setStartDelay(120);
                a.setInterpolator(new DecelerateInterpolator(1.5f));
                a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    public void onAnimationUpdate(android.animation.ValueAnimator v) {
                        ViewGroup.LayoutParams lp = bar.getLayoutParams();
                        lp.width = (int) (w * ((Float) v.getAnimatedValue()).floatValue());
                        bar.setLayoutParams(lp);
                    }
                });
                a.start();
            }
        });
    }

    private boolean reducedMotion() {
        try {
            return android.provider.Settings.Global.getFloat(getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) { return false; }
    }

    // ---- voice ------------------------------------------------------------

    /**
     * Hand off to the system's voice input screen rather than binding a SpeechRecognizer
     * in-process. The in-process route needs RECORD_AUDIO, and on some devices (Samsung
     * especially) it fails immediately depending on which recognition service is default,
     * firing an error callback per attempt. The activity route is what the platform
     * actually supports everywhere.
     */
    private void speak() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your answer");
        if (i.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "No voice input on this phone. Try the mic key on your "
                    + "keyboard instead.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "Voice input would not open. Type instead.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_VOICE || result != RESULT_OK || data == null) return;
        ArrayList<String> hits =
                data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (hits == null || hits.isEmpty()) return;
        String said = hits.get(0);
        if (said == null || said.trim().length() == 0) return;
        String existing = input.getText().toString().trim();
        input.setText(existing.length() > 0 ? existing + " " + said : said);
        input.setSelection(input.getText().length());
    }

    // ---- list ----------------------------------------------------------

    private class ChatAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View reuse, ViewGroup parent) {
            View v = reuse;
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_msg, parent, false);
            Model.Msg m = shown.get(i);
            LinearLayout row = (LinearLayout) v;
            TextView bubble = (TextView) v.findViewById(R.id.bubble);

            boolean mine = "user".equals(m.role);
            row.setGravity(mine ? Gravity.END : Gravity.START);
            bubble.setBackgroundResource(mine ? R.drawable.bubble_me : R.drawable.bubble_tutor);
            bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.82f));
            bubble.setText(m.text);

            // Only the newest turn animates; recycled rows must not stay invisible.
            if (i == shown.size() - 1 && i > lastAnimated && !reducedMotion()) {
                lastAnimated = i;
                v.setAlpha(0f);
                v.setTranslationY(dp(14));
                v.animate().alpha(1f).translationY(0f).setDuration(200)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(0.8f))
                        .start();
            } else {
                v.animate().cancel();
                v.setAlpha(1f);
                v.setTranslationY(0f);
            }
            return v;
        }
    }
}
