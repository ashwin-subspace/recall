package com.recall;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.recall.net.Ai;
import com.recall.util.Alarms;
import com.recall.util.Bg;
import com.recall.util.Prefs;

/** Three cards, once. Says what Recall is, gets a key working, then gets out of the way. */
public class OnboardActivity extends Activity {

    private int step;
    private String provider = Ai.GEMINI;
    private String testedKey = "";
    private boolean testing;
    private boolean ignoreProvider = true;
    private TextView title, body, next, hint, testOut, time;
    private EditText key, name;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.recall.util.Themes.apply(this);
        setContentView(R.layout.activity_onboard);

        title = (TextView) findViewById(R.id.obTitle);
        body = (TextView) findViewById(R.id.obBody);
        next = (TextView) findViewById(R.id.obNext);
        hint = (TextView) findViewById(R.id.obHint);
        testOut = (TextView) findViewById(R.id.obTestOut);
        time = (TextView) findViewById(R.id.obTime);
        key = (EditText) findViewById(R.id.obKey);
        name = (EditText) findViewById(R.id.obName);

        Spinner sp = (Spinner) findViewById(R.id.obProvider);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, Ai.PROVIDER_LABELS);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String next = Ai.PROVIDERS[pos];
                // The first callbacks are the spinner attaching and restoring itself.
                // Clearing a test that already succeeded would trap the learner on this card.
                if (!ignoreProvider && !next.equals(provider)) {
                    testedKey = "";
                    testOut.setText("");
                }
                provider = next;
                hint.setText(Ai.keyHint(provider));
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });

        findViewById(R.id.obTest).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { test(); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { advance(); }
        });
        findViewById(R.id.obSkip).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { done(); }
        });
        time.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickTime(); }
        });

        ((android.widget.Switch) findViewById(R.id.obRemind)).setChecked(true);
        key.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable e) {
                if (!e.toString().trim().equals(testedKey)) testedKey = "";
            }
        });
        if (b != null) {
            step = b.getInt("step", 0);
            testedKey = b.getString("testedKey", "");
        }
        showTime();
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        ignoreProvider = false;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
        out.putString("testedKey", testedKey);
    }

    private void render() {
        findViewById(R.id.obStep2).setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.obStep3).setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.obSkip).setVisibility(step == 1 ? View.VISIBLE : View.GONE);

        if (step == 0) {
            title.setText("Most AI chats forget you. This one does not.");
            body.setText("Recall keeps track of what you have covered, how solid you are on "
                    + "each thing, and the exact misunderstandings you have shown it.\n\n"
                    + "Every session opens with what it thinks you should work on, instead "
                    + "of a blank box waiting for you to explain yourself all over again.");
            next.setText("Go on");
        } else if (step == 1) {
            title.setText("Pick a free model");
            body.setText("Recall talks to the model straight from this phone. The key stays "
                    + "in this app and goes nowhere else.\n\n"
                    + "Free tiers are rate limited, and they may keep and train on what you "
                    + "send, so keep genuinely private material off them.");
            next.setText("Next");
        } else {
            title.setText("Last thing");
            body.setText("A name to greet you by, and when to nudge you. The nudge only "
                    + "arrives when something you learned is actually going cold, and it "
                    + "names the concept rather than the app.");
            next.setText("Start learning");
        }
        buildDots();
    }

    private void buildDots() {
        LinearLayout dots = (LinearLayout) findViewById(R.id.dots);
        dots.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 3; i++) {
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) ((i == step ? 22 : 7) * d), (int) (7 * d));
            if (i > 0) lp.leftMargin = (int) (6 * d);
            v.setLayoutParams(lp);
            v.setBackgroundResource(i == step ? R.drawable.bar_fill : R.drawable.bar_track);
            dots.addView(v);
        }
    }

    private void advance() {
        if (step == 1) {
            String pasted = key.getText().toString().trim();
            if (!pasted.equals(testedKey) || testedKey.length() < 10) {
                testOut.setTextColor(getResources().getColor(R.color.bad));
                testOut.setText("Test the key before continuing. Skip for now if you do not have one yet.");
                return;
            }
            Prefs.setProvider(this, provider);
            Prefs.setModel(this, Ai.defaultModel(provider));
            Prefs.setKey(this, pasted);
        }
        if (step == 2) { done(); return; }
        step++;
        render();
    }

    private void test() {
        if (testing) return;
        final String pasted = key.getText().toString().trim();
        if (pasted.length() < 10) {
            testedKey = "";
            testOut.setTextColor(getResources().getColor(R.color.bad));
            testOut.setText("Paste the key first. It stays on this phone and is sent only to the provider you picked.");
            return;
        }
        testing = true;
        Prefs.setProvider(this, provider);
        Prefs.setModel(this, Ai.defaultModel(provider));
        Prefs.setKey(this, pasted);
        testOut.setTextColor(getResources().getColor(R.color.muted));
        testOut.setText("Calling " + Prefs.model(this) + "...");
        Bg.go(new Bg.Work<String>() {
            public String run() throws Exception { return Ai.ping(OnboardActivity.this); }
        }, new Bg.Done<String>() {
            public void ok(String value) {
                testing = false;
                if (!pasted.equals(key.getText().toString().trim())) return;
                testedKey = pasted;
                testOut.setTextColor(getResources().getColor(R.color.good));
                testOut.setText("Working. That key is good.");
            }
            public void fail(Exception e) {
                testing = false;
                testedKey = "";
                testOut.setTextColor(getResources().getColor(R.color.bad));
                testOut.setText(e.getMessage() == null ? "That did not work." : e.getMessage());
            }
        });
    }

    private void pickTime() {
        new android.app.TimePickerDialog(this,
                new android.app.TimePickerDialog.OnTimeSetListener() {
                    public void onTimeSet(android.widget.TimePicker p, int h, int m) {
                        Prefs.setRemindAt(OnboardActivity.this, h, m);
                        showTime();
                    }
                }, Prefs.remindHour(this), Prefs.remindMinute(this), false).show();
    }

    private void showTime() {
        int h = Prefs.remindHour(this), m = Prefs.remindMinute(this);
        int display = h % 12 == 0 ? 12 : h % 12;
        time.setText(String.format(java.util.Locale.US, "%d:%02d %s",
                display, m, h < 12 ? "am" : "pm"));
    }

    private void done() {
        if (step >= 1) {
            Prefs.setProvider(this, provider);
            Prefs.setModel(this, Ai.defaultModel(provider));
            String pasted = key.getText().toString().trim();
            if (pasted.length() > 0) Prefs.setKey(this, pasted);
        }
        if (step == 2) {
            Prefs.setWho(this, name.getText().toString());
            Prefs.setRemindOn(this,
                    ((android.widget.Switch) findViewById(R.id.obRemind)).isChecked());
        }
        Prefs.setOnboarded(this, true);
        Alarms.arm(this);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override public void onBackPressed() {
        if (step > 0) { step--; render(); return; }
        super.onBackPressed();
    }
}
