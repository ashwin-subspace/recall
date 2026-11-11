package com.recall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.recall.data.Db;
import com.recall.data.Model;
import com.recall.ui.Sparkline;

import java.util.ArrayList;
import java.util.List;

/** One concept, everything Recall knows about it, and what to do about it. */
public class ConceptActivity extends Activity {

    private long conceptId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.recall.util.Themes.apply(this);
        setContentView(R.layout.activity_concept);
        conceptId = getIntent().getLongExtra("conceptId", -1);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnMenu).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { menu(); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        final Db db = Db.get(this);
        final Model.Concept k = db.concept(conceptId);
        if (k == null) { finish(); return; }

        ((TextView) findViewById(R.id.cTitle)).setText(k.label);
        ((TextView) findViewById(R.id.cBig)).setText(String.valueOf(k.effective));

        TextView was = (TextView) findViewById(R.id.cWas);
        if (k.effective < k.confidence) {
            was.setText("Was " + k.confidence + ". It fades while you leave it alone.");
            was.setVisibility(View.VISIBLE);
        } else {
            was.setVisibility(View.GONE);
        }

        List<long[]> trend = db.trend(conceptId);
        List<Integer> vals = new ArrayList<Integer>();
        for (int i = 0; i < trend.size(); i++) vals.add((int) trend.get(i)[0]);
        Sparkline spark = (Sparkline) findViewById(R.id.cSpark);
        spark.setValues(vals);
        spark.animateIn();
        ((TextView) findViewById(R.id.cSparkNote)).setText(vals.size() < 2
                ? "One reading so far. The line appears once you have been here twice."
                : vals.size() + " readings, oldest on the left. The faint line is 50.");

        ((TextView) findViewById(R.id.cSchedule)).setText(scheduleText(k));
        renderGaps(k);
        renderSessions(db);
        renderNotes();

        findViewById(R.id.cDrill).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                db.bumpToFront(conceptId);
                Intent i = new Intent(ConceptActivity.this, SessionActivity.class);
                i.putExtra("targeted", true);
                startActivity(i);
            }
        });
    }

    private void renderGaps(Model.Concept k) {
        TextView head = (TextView) findViewById(R.id.cGapsHead);
        TextView gaps = (TextView) findViewById(R.id.cGaps);
        if (k.gaps.isEmpty()) {
            head.setVisibility(View.GONE);
            gaps.setVisibility(View.GONE);
            return;
        }
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < k.gaps.size(); i++) {
            if (i > 0) s.append("\n\n");
            s.append("· ").append(k.gaps.get(i));
        }
        gaps.setText(s.toString());
        head.setVisibility(View.VISIBLE);
        gaps.setVisibility(View.VISIBLE);
    }

    private void renderSessions(Db db) {
        List<Model.Sess> sessions = db.sessionsFor(conceptId);
        LinearLayout host = (LinearLayout) findViewById(R.id.cSessions);
        host.removeAllViews();
        findViewById(R.id.cSessHead).setVisibility(
                sessions.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < sessions.size(); i++) {
            final Model.Sess s = sessions.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_recent, host, false);
            ((TextView) row.findViewById(R.id.rTitle)).setText(s.title);
            ((TextView) row.findViewById(R.id.rSummary)).setText(s.summary);
            ((TextView) row.findViewById(R.id.rWhen)).setText(Db.ago(s.ended));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Intent i = new Intent(ConceptActivity.this, SessionActivity.class);
                    i.putExtra("sessId", s.id);
                    startActivity(i);
                }
            });
            host.addView(row);
        }
    }

    /** Say the schedule in words, so the number is never mysterious. */
    private String scheduleText(Model.Concept k) {
        long now = System.currentTimeMillis();
        int band = Db.intervalDays(k.confidence);
        String seen = "Covered " + k.seen + (k.seen == 1 ? " time" : " times")
                + ", last " + Db.ago(k.lastSeen) + ".";
        if (k.nextDue <= now) {
            long over = Math.round((now - k.nextDue) / 86400000.0);
            return seen + "\n\nDue now" + (over >= 1 ? ", " + over + " day"
                    + (over == 1 ? "" : "s") + " overdue" : "")
                    + ". At confidence " + k.confidence + " it gets left " + band
                    + (band == 1 ? " day" : " days") + " between looks.";
        }
        long in = Math.max(1, Math.round((k.nextDue - now) / 86400000.0));
        return seen + "\n\nSolid enough to leave " + band + (band == 1 ? " day" : " days")
                + " between looks, so the next one is in " + in
                + (in == 1 ? " day" : " days") + ".";
    }

    private void menu() {
        final Model.Concept k = Db.get(this).concept(conceptId);
        if (k == null) return;
        final boolean notes = !Db.get(this).sourcesFor(conceptId).isEmpty();
        final String[] items = notes
                ? new String[]{"Add your own notes", "Remove your notes", "Rename",
                        "Merge into another concept", "Forget this"}
                : new String[]{"Add your own notes", "Rename",
                        "Merge into another concept", "Forget this"};
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String pick = items[which];
                        if ("Add your own notes".equals(pick)) addNotes();
                        else if ("Remove your notes".equals(pick)) removeNotes();
                        else if ("Rename".equals(pick)) rename(k);
                        else if ("Merge into another concept".equals(pick)) mergeInto(k);
                        else forget(k);
                    }
                })
                .show();
    }


    /**
     * Paste a syllabus or lecture notes. The tutor is then told to teach from these and to
     * say so when they contradict what it would otherwise have said.
     */
    private void addNotes() {
        final EditText in = new EditText(this);
        in.setHint("Paste your notes or syllabus for this concept");
        in.setMinLines(6);
        in.setGravity(android.view.Gravity.TOP);
        new AlertDialog.Builder(this)
                .setTitle("Your own notes")
                .setView(in)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String body = in.getText().toString().trim();
                        if (body.length() == 0) {
                            Toast.makeText(ConceptActivity.this, "Nothing to save.",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Db.get(ConceptActivity.this).addSource(conceptId, "Notes", body);
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderNotes() {
        List<String[]> notes = Db.get(this).sourcesFor(conceptId);
        TextView head = (TextView) findViewById(R.id.cNotesHead);
        TextView body = (TextView) findViewById(R.id.cNotes);
        if (notes.isEmpty()) {
            head.setVisibility(View.GONE);
            body.setVisibility(View.GONE);
            return;
        }
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) s.append("\n\n");
            String text = notes.get(i)[2];
            if (text.length() > 400) text = text.substring(0, 400) + "…";
            s.append(text);
        }
        body.setText(s.toString());
        head.setVisibility(View.VISIBLE);
        body.setVisibility(View.VISIBLE);
    }
    private void removeNotes() {
        new AlertDialog.Builder(this)
                .setTitle("Remove your notes?")
                .setMessage("The tutor will stop seeing the notes you pasted for this concept.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Db.get(ConceptActivity.this).deleteSourcesFor(conceptId);
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void rename(Model.Concept k) {
        final EditText in = new EditText(this);
        in.setText(k.label);
        in.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Rename concept")
                .setView(in)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String next = in.getText().toString().trim();
                        if (next.length() == 0) {
                            Toast.makeText(ConceptActivity.this, "Enter a name.",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!Db.get(ConceptActivity.this).renameConcept(conceptId, next)) {
                            Toast.makeText(ConceptActivity.this,
                                    "That name is already a concept. Merge them instead.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The manual backstop for near-duplicate concepts the model still manages to mint. */
    private void mergeInto(Model.Concept k) {
        final List<Model.Concept> others = new ArrayList<Model.Concept>();
        List<Model.Concept> all = Db.get(this).concepts();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id != conceptId) others.add(all.get(i));
        }
        if (others.isEmpty()) {
            Toast.makeText(this, "Nothing to merge into yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[others.size()];
        for (int i = 0; i < others.size(); i++) names[i] = others.get(i).label;

        new AlertDialog.Builder(this)
                .setTitle("Merge into")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Db.get(ConceptActivity.this).merge(conceptId, others.get(which).id);
                        Toast.makeText(ConceptActivity.this,
                                "Merged into " + names[which], Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void forget(Model.Concept k) {
        new AlertDialog.Builder(this)
                .setTitle("Forget " + k.label + "?")
                .setMessage("Its history, its readings and its open gaps go with it. "
                        + "This cannot be undone.")
                .setPositiveButton("Forget it", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Db.get(ConceptActivity.this).archive(conceptId);
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
