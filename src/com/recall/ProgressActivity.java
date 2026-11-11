package com.recall;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.recall.data.Db;
import com.recall.data.Model;
import com.recall.ui.Bars;
import com.recall.ui.Sparkline;

import java.util.ArrayList;
import java.util.List;

/** How the model has moved over time. Honest about having too little data to plot. */
public class ProgressActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.recall.util.Themes.apply(this);
        setContentView(R.layout.activity_progress);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        Db db = Db.get(this);
        List<long[]> trend = db.overallTrend();
        int sessions = db.sessionCount();

        // A trend line drawn through one point is a lie, so do not draw one.
        if (sessions < 2 || trend.size() < 2) {
            findViewById(R.id.pBody).setVisibility(View.GONE);
            TextView empty = (TextView) findViewById(R.id.pEmpty);
            empty.setVisibility(View.VISIBLE);
            if (sessions == 0) {
                empty.setText("Nothing to plot yet. Finish a session and this fills in.");
            } else if (sessions == 1) {
                empty.setText("One session so far. Progress needs at least two readings before a "
                        + "line means anything, so this stays empty until then.");
            } else {
                empty.setText("Those sessions landed close together, so there is only one point "
                        + "so far. The line appears once a later day adds another reading.");
            }
            return;
        }

        findViewById(R.id.pEmpty).setVisibility(View.GONE);
        findViewById(R.id.pBody).setVisibility(View.VISIBLE);

        List<Integer> vals = new ArrayList<Integer>();
        for (int i = 0; i < trend.size(); i++) vals.add((int) trend.get(i)[0]);
        int now = db.currentAverage();
        int first = vals.get(0);

        ((TextView) findViewById(R.id.pAvg)).setText(String.valueOf(now));
        int delta = now - first;
        String move = delta == 0 ? "level with where you started"
                : (delta > 0 ? "up " + delta : "down " + (-delta)) + " since your first session";
        ((TextView) findViewById(R.id.pAvgNote)).setText(
                "Across " + db.conceptCount() + " concepts, " + move + ".");

        Sparkline spark = (Sparkline) findViewById(R.id.pSpark);
        spark.setValues(vals);
        spark.animateIn();

        int[] weeks = db.sessionsPerWeek(12);
        Bars bars = (Bars) findViewById(R.id.pBars);
        bars.setValues(weeks);
        bars.animateIn();
        int recent = weeks[weeks.length - 1];
        ((TextView) findViewById(R.id.pBarsNote)).setText(recent == 0
                ? "Nothing this week yet. Oldest week on the left."
                : recent + (recent == 1 ? " session" : " sessions")
                        + " this week. Oldest week on the left.");

        LinearLayout stats = (LinearLayout) findViewById(R.id.pStats);
        stats.removeAllViews();
        addStat(stats, String.valueOf(sessions), sessions == 1 ? "session" : "sessions");
        addStat(stats, String.valueOf(db.streak()), "day streak");
        addStat(stats, minutes(db), "minutes");
    }

    private String minutes(Db db) {
        long total = 0;
        List<Model.Sess> all = db.recent(500);
        for (int i = 0; i < all.size(); i++) {
            Model.Sess s = all.get(i);
            if (s.ended > s.started) total += s.ended - s.started;
        }
        return String.valueOf(Math.round(total / 60000.0));
    }

    private void addStat(LinearLayout host, String value, String label) {
        View v = getLayoutInflater().inflate(R.layout.part_stat, host, false);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (host.getChildCount() > 0) {
            lp.leftMargin = (int) (10 * getResources().getDisplayMetrics().density);
        }
        v.setLayoutParams(lp);
        ((TextView) v.findViewById(R.id.statVal)).setText(value);
        ((TextView) v.findViewById(R.id.statLbl)).setText(label);
        host.addView(v);
    }
}
