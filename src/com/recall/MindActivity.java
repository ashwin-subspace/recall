package com.recall;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.recall.data.Db;
import com.recall.data.Model;

import java.util.ArrayList;
import java.util.List;

/** The receipts. Everything Recall claims to remember, laid out plainly. */
public class MindActivity extends Activity {

    private final List<Model.Concept> all = new ArrayList<Model.Concept>();
    private final List<Model.Concept> items = new ArrayList<Model.Concept>();
    private int filter;   // 0 all, 1 due, 2 shaky, 3 solid
    private Adapter adapter;
    private ListView list;
    private TextView sub;
    private TextView empty;

    private static final String[] FILTERS = {"All", "Due", "Shaky", "Solid"};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.recall.util.Themes.apply(this);
        setContentView(R.layout.activity_mind);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnProgress).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MindActivity.this, ProgressActivity.class));
            }
        });

        sub = (TextView) findViewById(R.id.mindSub);
        list = (ListView) findViewById(R.id.concepts);
        empty = (TextView) findViewById(R.id.mindEmpty);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= items.size()) return;
                Intent i = new Intent(MindActivity.this, ConceptActivity.class);
                i.putExtra("conceptId", items.get(pos).id);
                startActivity(i);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    /** Mind used to freeze whatever it loaded on the way in, including concepts just forgotten. */
    private void reload() {
        Db db = Db.get(this);
        all.clear();
        all.addAll(db.concepts());
        View filters = findViewById(R.id.filters);
        View scroller = (View) filters.getParent();
        if (all.isEmpty()) {
            sub.setVisibility(View.GONE);
            list.setVisibility(View.GONE);
            scroller.setVisibility(View.GONE);
            empty.setText("Nothing yet. Finish a session and I will start keeping track.");
            empty.setVisibility(View.VISIBLE);
            return;
        }
        scroller.setVisibility(View.VISIBLE);
        sub.setVisibility(View.VISIBLE);
        int gaps = 0;
        for (int i = 0; i < all.size(); i++) gaps += all.get(i).gaps.size();
        sub.setText(all.size() + (all.size() == 1 ? " concept" : " concepts") + " tracked  ·  "
                + db.dueCount() + " due  ·  " + gaps + (gaps == 1 ? " open gap" : " open gaps"));
        buildFilters();
        applyFilter();
    }


    private void buildFilters() {
        LinearLayout host = (LinearLayout) findViewById(R.id.filters);
        host.removeAllViews();
        for (int i = 0; i < FILTERS.length; i++) {
            final int which = i;
            TextView chip = new TextView(this);
            chip.setText(FILTERS[i]);
            chip.setTextSize(13f);
            chip.setTextColor(getResources().getColor(R.color.text));
            chip.setBackgroundResource(R.drawable.chip);
            chip.setSelected(i == filter);
            int px = dp(14), py = dp(7);
            chip.setPadding(px, py, px, py);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    filter = which;
                    applyFilter();
                    buildFilters();
                }
            });
            host.addView(chip);
        }
    }

    private void applyFilter() {
        long now = System.currentTimeMillis();
        items.clear();
        for (int i = 0; i < all.size(); i++) {
            Model.Concept k = all.get(i);
            boolean keep;
            if (filter == 1) keep = k.nextDue <= now;
            else if (filter == 2) keep = k.effective < 50;
            else if (filter == 3) keep = k.effective >= 70;
            else keep = true;
            if (keep) items.add(k);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (items.isEmpty()) {
            empty.setText("Nothing here under \"" + FILTERS[filter] + "\".");
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        } else {
            empty.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
    private class Adapter extends BaseAdapter {
        public int getCount() { return items.size(); }
        public Object getItem(int i) { return items.get(i); }
        public long getItemId(int i) { return items.get(i).id; }

        public View getView(int i, View reuse, ViewGroup parent) {
            View v = reuse;
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_concept, parent, false);
            Model.Concept k = items.get(i);

            ((TextView) v.findViewById(R.id.cName)).setText(k.label);
            ((TextView) v.findViewById(R.id.cPct)).setText(k.effective + "/100");

            final View bar = v.findViewById(R.id.cBar);
            final int pct = k.effective;
            final View track = (View) bar.getParent();
            track.post(new Runnable() {
                public void run() {
                    ViewGroup.LayoutParams lp = bar.getLayoutParams();
                    lp.width = (int) (track.getWidth() * (pct / 100f));
                    bar.setLayoutParams(lp);
                }
            });

            boolean due = k.nextDue <= System.currentTimeMillis();
            String meta = "last touched " + Db.ago(k.lastSeen) + "  ·  covered " + k.seen
                    + (k.seen == 1 ? " time" : " times");
            if (due) meta = meta + "  ·  DUE";
            else { String dd = daysUntil(k.nextDue); meta = meta + (dd.equals("today") ? "  ·  due later today" : "  ·  next in " + dd); }
            ((TextView) v.findViewById(R.id.cMeta)).setText(meta);

            TextView gapsView = (TextView) v.findViewById(R.id.cGaps);
            if (k.gaps.isEmpty()) {
                gapsView.setVisibility(View.GONE);
            } else {
                StringBuilder b = new StringBuilder();
                for (int j = 0; j < k.gaps.size(); j++) {
                    if (j > 0) b.append("\n");
                    b.append("· ").append(k.gaps.get(j));
                }
                gapsView.setText(b.toString());
                gapsView.setVisibility(View.VISIBLE);
            }
            return v;
        }
    }

    private static String daysUntil(long when) {
        long d = Math.round((when - System.currentTimeMillis()) / (24.0 * 60 * 60 * 1000));
        if (d <= 0) return "today";
        if (d == 1) return "1 day";
        return d + " days";
    }
}
