package com.recall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.recall.data.Db;
import com.recall.net.Ai;
import com.recall.util.Alarms;
import com.recall.util.Themes;
import com.recall.util.Bg;
import com.recall.util.Prefs;

public class SettingsActivity extends Activity {

    private EditText key, who;
    private Spinner provider, model, theme;
    private TextView testOut, keyHint, remindTime;
    private android.widget.Switch remindOn;
    private String chosenProvider;
    private boolean testing;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.recall.util.Themes.apply(this);
        setContentView(R.layout.activity_settings);

        key = (EditText) findViewById(R.id.key);
        who = (EditText) findViewById(R.id.who);
        provider = (Spinner) findViewById(R.id.provider);
        model = (Spinner) findViewById(R.id.model);
        testOut = (TextView) findViewById(R.id.testOut);
        keyHint = (TextView) findViewById(R.id.keyHint);
        remindTime = (TextView) findViewById(R.id.remindTime);
        remindOn = (android.widget.Switch) findViewById(R.id.remindOn);
        setUpReminder();
        setUpTheme();
        setUpBackup();

        key.setText(Prefs.key(this));
        who.setText(Prefs.who(this));
        chosenProvider = Prefs.provider(this);

        provider.setAdapter(adapter(Ai.PROVIDER_LABELS));
        for (int i = 0; i < Ai.PROVIDERS.length; i++) {
            if (Ai.PROVIDERS[i].equals(chosenProvider)) provider.setSelection(i);
        }
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String picked = Ai.PROVIDERS[pos];
                boolean changed = !picked.equals(chosenProvider);
                chosenProvider = picked;
                fillModels(changed ? Ai.defaultModel(picked) : Prefs.model(SettingsActivity.this));
                keyHint.setText(Ai.keyHint(picked));
                if (changed) testOut.setText("");
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });

        fillModels(Prefs.model(this));
        keyHint.setText(Ai.keyHint(chosenProvider));

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); finish(); }
        });
        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { test(); }
        });
        findViewById(R.id.btnWipe).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { wipe(); }
        });
    }



    // ---- appearance -------------------------------------------------------

    private static final String[] THEME_IDS = {"system", "dark", "light"};
    private static final String[] THEME_NAMES =
            {"Follow the system", "Always dark", "Always light"};

    private void setUpTheme() {
        theme = (Spinner) findViewById(R.id.theme);
        TextView note = (TextView) findViewById(R.id.themeNote);

        if (!Themes.canOverride()) {
            theme.setVisibility(View.GONE);
            note.setText("Recall follows your system light and dark setting. Choosing per-app "
                    + "needs Android 12 or newer.");
            return;
        }
        theme.setAdapter(adapter(THEME_NAMES));
        String current = Prefs.theme(this);
        for (int i = 0; i < THEME_IDS.length; i++) {
            if (THEME_IDS[i].equals(current)) theme.setSelection(i);
        }
        note.setText("Dark is what Recall was designed in. Light is there for daylight.");
        theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (THEME_IDS[pos].equals(Prefs.theme(SettingsActivity.this))) return;
                Prefs.setTheme(SettingsActivity.this, THEME_IDS[pos]);
                Themes.apply(SettingsActivity.this);
                recreate();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
    }
    // ---- the daily nudge --------------------------------------------------

    private void setUpReminder() {
        remindOn.setChecked(Prefs.remindOn(this));
        showRemindTime();

        remindOn.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Prefs.setRemindOn(SettingsActivity.this, on);
                Alarms.arm(SettingsActivity.this);
                showRemindTime();
            }
        });

        remindTime.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new android.app.TimePickerDialog(SettingsActivity.this,
                        new android.app.TimePickerDialog.OnTimeSetListener() {
                            public void onTimeSet(android.widget.TimePicker p, int h, int m) {
                                Prefs.setRemindAt(SettingsActivity.this, h, m);
                                Prefs.setRemindOn(SettingsActivity.this, true);
                                remindOn.setChecked(true);
                                Alarms.arm(SettingsActivity.this);
                                showRemindTime();
                            }
                        },
                        Prefs.remindHour(SettingsActivity.this),
                        Prefs.remindMinute(SettingsActivity.this), false).show();
            }
        });
    }

    private void showRemindTime() {
        if (!Prefs.remindOn(this)) {
            remindTime.setText("Off");
            remindTime.setTextColor(getResources().getColor(R.color.faint));
            return;
        }
        int h = Prefs.remindHour(this), m = Prefs.remindMinute(this);
        int display = h % 12 == 0 ? 12 : h % 12;
        remindTime.setText(String.format(java.util.Locale.US, "%d:%02d %s",
                display, m, h < 12 ? "am" : "pm"));
        remindTime.setTextColor(getResources().getColor(R.color.text));
    }
    private ArrayAdapter<String> adapter(String[] labels) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private void fillModels(String select) {
        model.setAdapter(adapter(Ai.modelLabels(chosenProvider)));
        String[] ids = Ai.models(chosenProvider);
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(select)) model.setSelection(i);
        }
    }

    private void persist() {
        Prefs.setKey(this, key.getText().toString());
        Prefs.setWho(this, who.getText().toString());
        Prefs.setProvider(this, chosenProvider);
        int i = model.getSelectedItemPosition();
        String[] ids = Ai.models(chosenProvider);
        if (i >= 0 && i < ids.length) Prefs.setModel(this, ids[i]);
    }

    private void save() {
        persist();
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    private void test() {
        if (testing) return;
        if (key.getText().toString().trim().length() < 10) {
            testOut.setTextColor(getResources().getColor(R.color.bad));
            testOut.setText("Paste a key first. Recall cannot tutor without one.");
            return;
        }
        testing = true;
        persist();
        testOut.setTextColor(getResources().getColor(R.color.muted));
        testOut.setText("Calling " + Prefs.model(this) + "...");
        Bg.go(new Bg.Work<String>() {
            public String run() throws Exception { return Ai.ping(SettingsActivity.this); }
        }, new Bg.Done<String>() {
            public void ok(String value) {
                testing = false;
                testOut.setTextColor(getResources().getColor(R.color.good));
                testOut.setText("Connected. " + Prefs.model(SettingsActivity.this)
                        + " replied: " + value.trim());
            }
            public void fail(Exception e) {
                testing = false;
                testOut.setTextColor(getResources().getColor(R.color.bad));
                testOut.setText(e.getMessage() == null ? "Failed." : e.getMessage());
            }
        });
    }


    // ---- export and restore -----------------------------------------------

    private static final int REQ_SAVE = 71, REQ_OPEN = 72;

    private void setUpBackup() {
        findViewById(R.id.btnExport).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, "recall-backup.json");
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (Exception e) {
                    say("No file picker on this phone.", false);
                }
            }
        });
        findViewById(R.id.btnImport).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmImport(); }
        });
    }

    private void confirmImport() {
        new AlertDialog.Builder(this)
                .setTitle("Restore from a file?")
                .setMessage("Everything currently on this phone is replaced by what is in "
                        + "the file. Export first if you are not sure.")
                .setPositiveButton("Choose a file", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        try {
                            startActivityForResult(i, REQ_OPEN);
                        } catch (Exception e) {
                            say("No file picker on this phone.", false);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();

        if (req == REQ_SAVE) {
            Bg.go(new Bg.Work<String>() {
                public String run() throws Exception {
                    String json = Db.get(SettingsActivity.this).exportJson();
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) throw new Exception("Could not open that file.");
                    os.write(json.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    return String.valueOf(json.length());
                }
            }, new Bg.Done<String>() {
                public void ok(String size) {
                    say("Exported. " + Math.round(Integer.parseInt(size) / 1024.0)
                            + " KB written.", true);
                }
                public void fail(Exception e) { say(reason(e), false); }
            });
        } else if (req == REQ_OPEN) {
            Bg.go(new Bg.Work<Integer>() {
                public Integer run() throws Exception {
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new Exception("Could not open that file.");
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    return Db.get(SettingsActivity.this)
                            .importJson(new String(out.toByteArray(), "UTF-8"));
                }
            }, new Bg.Done<Integer>() {
                public void ok(Integer concepts) {
                    FocusWidget.refresh(SettingsActivity.this);
                    say("Restored. " + concepts
                            + (concepts == 1 ? " concept is back." : " concepts are back."), true);
                }
                public void fail(Exception e) { say(reason(e), false); }
            });
        }
    }

    private String reason(Exception e) {
        return e.getMessage() == null ? "That did not work." : e.getMessage();
    }

    private void say(String s, boolean ok) {
        TextView t = (TextView) findViewById(R.id.backupOut);
        t.setTextColor(getResources().getColor(ok ? R.color.good : R.color.bad));
        t.setText(s);
    }
    private void wipe() {
        new AlertDialog.Builder(this)
                .setTitle("Forget everything?")
                .setMessage("Every concept, gap and session is deleted from this phone. "
                        + "Recall will meet you as a stranger again. This cannot be undone.")
                .setPositiveButton("Forget it all", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Db.get(SettingsActivity.this).wipe();
                        Toast.makeText(SettingsActivity.this, "Memory cleared",
                                Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
