package com.recall.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.recall.util.Prefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The learner model. This is the whole point of the app: what survives between
 * sessions so the tutor never has to start cold.
 */
public class Db extends SQLiteOpenHelper {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static Db instance;
    private final Context app;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private static final int VERSION = 3;

    private Db(Context c) {
        super(c, "recall.db", null, VERSION);
        app = c.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE concept (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "ckey TEXT UNIQUE, label TEXT, confidence INTEGER, seen INTEGER, "
                + "last_seen INTEGER, next_due INTEGER)");
        d.execSQL("CREATE TABLE gap (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "concept_id INTEGER, text TEXT, open INTEGER, created INTEGER)");
        d.execSQL("CREATE TABLE sess (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "started INTEGER, ended INTEGER, title TEXT, summary TEXT)");
        d.execSQL("CREATE TABLE msg (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "sess_id INTEGER, role TEXT, text TEXT, ts INTEGER)");
        v2(d);
        v3(d);
    }

    /**
     * Real migrations. This used to be empty, which meant any schema change did nothing
     * on an installed app and then crashed on the missing column. Cases fall through on
     * purpose so an old install catches up one version at a time.
     */
    @Override public void onUpgrade(SQLiteDatabase d, int from, int to) {
        switch (from) {
            case 1: v2(d);
            case 2: v3(d);
            default: break;
        }
    }

    private void v2(SQLiteDatabase d) {
        addColumn(d, "concept", "archived", "INTEGER DEFAULT 0");
        addColumn(d, "sess", "mode", "TEXT DEFAULT 'free'");
        addColumn(d, "sess", "duration_ms", "INTEGER DEFAULT 0");
        d.execSQL("CREATE TABLE IF NOT EXISTS snapshot (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "concept_id INTEGER, confidence INTEGER, at INTEGER)");
        d.execSQL("CREATE TABLE IF NOT EXISTS source (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "concept_id INTEGER, title TEXT, body TEXT, added INTEGER)");
    }

    private void v3(SQLiteDatabase d) {
        // ties each confidence reading to the session that produced it, so a concept can
        // list the sessions that touched it
        addColumn(d, "snapshot", "sess_id", "INTEGER DEFAULT 0");
    }

    /** ALTER TABLE ADD COLUMN throws if it is already there, which is fine to ignore. */
    private void addColumn(SQLiteDatabase d, String table, String col, String spec) {
        try {
            d.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col + " " + spec);
        } catch (Exception alreadyThere) { }
    }

    // ---- scheduling -------------------------------------------------------

    /** Days until a concept at this confidence should be revisited. */
    public static int intervalDays(int confidence) {
        if (confidence < 30) return 1;
        if (confidence < 50) return 2;
        if (confidence < 70) return 4;
        if (confidence < 85) return 8;
        return 16;
    }


    /**
     * Confidence erodes while a concept sits untouched past its due date. Without this a
     * concept scored 88 once stays 88 for ever and the focus picker stops surfacing the
     * things you have actually forgotten. Computed on read and never written back, so
     * revisiting a concept restores it rather than fighting a decayed number.
     */
    public static int decayed(int stored, long nextDue) {
        long over = System.currentTimeMillis() - nextDue;
        if (over <= 0) return stored;
        double days = over / (double) DAY;
        int floor = (int) Math.round(stored * 0.25);
        int v = (int) Math.round(stored - days * 1.5);
        if (v < floor) v = floor;
        if (v < 0) v = 0;
        return v;
    }
    public static String canon(String label) {
        if (label == null) return "";
        return label.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", "").replaceAll(" +", " ");
    }

    // ---- concepts ---------------------------------------------------------

    public List<Model.Concept> concepts() {
        List<Model.Concept> out = new ArrayList<Model.Concept>();
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = d.rawQuery("SELECT id,ckey,label,confidence,seen,last_seen,next_due "
                + "FROM concept WHERE IFNULL(archived,0)=0 "
                + "ORDER BY next_due ASC, confidence ASC", null);
        while (c.moveToNext()) {
            Model.Concept k = new Model.Concept();
            k.id = c.getLong(0); k.key = c.getString(1); k.label = c.getString(2);
            k.confidence = c.getInt(3); k.seen = c.getInt(4);
            k.lastSeen = c.getLong(5); k.nextDue = c.getLong(6);
            k.effective = decayed(k.confidence, k.nextDue);
            out.add(k);
        }
        c.close();
        for (int i = 0; i < out.size(); i++) {
            Model.Concept k = out.get(i);
            Cursor g = d.rawQuery("SELECT text FROM gap WHERE concept_id=? AND open=1 ORDER BY id",
                    new String[]{String.valueOf(k.id)});
            while (g.moveToNext()) k.gaps.add(g.getString(0));
            g.close();
        }
        return out;
    }

    /** The single thing this session should attack: most overdue, then least confident. */
    public Model.Concept focus() {
        long pin = Prefs.pinned(app);
        if (pin >= 0) {
            Model.Concept pinned = concept(pin);
            if (pinned != null) return pinned;
            Prefs.setPinned(app, -1);
        }
        List<Model.Concept> all = concepts();
        if (all.isEmpty()) return null;
        long now = System.currentTimeMillis();
        Model.Concept best = null;
        double bestScore = -1e18;
        for (int i = 0; i < all.size(); i++) {
            Model.Concept k = all.get(i);
            double overdueDays = (now - k.nextDue) / (double) DAY;
            double score = overdueDays * 10 + (100 - k.effective) + (k.gaps.isEmpty() ? 0 : 25);
            if (score > bestScore) { bestScore = score; best = k; }
        }
        return best;
    }

    public int dueCount() {
        long now = System.currentTimeMillis();
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = d.rawQuery(
                "SELECT COUNT(*) FROM concept WHERE next_due<=? AND IFNULL(archived,0)=0",
                new String[]{String.valueOf(now)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int conceptCount() {
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = d.rawQuery(
                "SELECT COUNT(*) FROM concept WHERE IFNULL(archived,0)=0", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int sessionCount() {
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = d.rawQuery("SELECT COUNT(*) FROM sess WHERE ended>0", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    // ---- sessions ---------------------------------------------------------

    public long startSession() {
        ContentValues v = new ContentValues();
        v.put("started", System.currentTimeMillis());
        v.put("ended", 0);
        v.put("title", "");
        v.put("summary", "");
        return getWritableDatabase().insert("sess", null, v);
    }

    public void endSession(long id, String title, String summary) {
        ContentValues v = new ContentValues();
        v.put("ended", System.currentTimeMillis());
        v.put("title", title);
        v.put("summary", summary);
        getWritableDatabase().update("sess", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void dropSession(long id) {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("msg", "sess_id=?", new String[]{String.valueOf(id)});
        d.delete("sess", "id=?", new String[]{String.valueOf(id)});
    }

    public void addMsg(long sessId, String role, String text) {
        ContentValues v = new ContentValues();
        v.put("sess_id", sessId); v.put("role", role); v.put("text", text);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert("msg", null, v);
    }

    public List<Model.Msg> msgs(long sessId) {
        List<Model.Msg> out = new ArrayList<Model.Msg>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT role,text,ts FROM msg WHERE sess_id=? ORDER BY id",
                new String[]{String.valueOf(sessId)});
        while (c.moveToNext()) out.add(new Model.Msg(c.getString(0), c.getString(1), c.getLong(2)));
        c.close();
        return out;
    }


    public Model.Sess session(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,started,ended,title,summary FROM sess WHERE id=?",
                new String[]{String.valueOf(id)});
        Model.Sess s = null;
        if (c.moveToFirst()) {
            s = new Model.Sess();
            s.id = c.getLong(0); s.started = c.getLong(1); s.ended = c.getLong(2);
            s.title = c.getString(3); s.summary = c.getString(4);
        }
        c.close();
        return s;
    }
    public List<Model.Sess> recent(int n) {
        List<Model.Sess> out = new ArrayList<Model.Sess>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,started,ended,title,summary FROM sess WHERE ended>0 "
                        + "ORDER BY ended DESC LIMIT " + n, null);
        while (c.moveToNext()) {
            Model.Sess s = new Model.Sess();
            s.id = c.getLong(0); s.started = c.getLong(1); s.ended = c.getLong(2);
            s.title = c.getString(3); s.summary = c.getString(4);
            out.add(s);
        }
        c.close();
        return out;
    }

    // ---- applying what the tutor learned ---------------------------------

    /** Insert or update one concept, and reschedule it from its new confidence. */
    public long touchConcept(String label, int confidence) {
        String key = canon(label);
        if (key.length() == 0) return -1;
        long now = System.currentTimeMillis();
        if (confidence < 0) confidence = 0;
        if (confidence > 100) confidence = 100;

        SQLiteDatabase d = getWritableDatabase();
        Cursor c = d.rawQuery("SELECT id,seen FROM concept WHERE ckey=?", new String[]{key});
        long id = -1; int seen = 0;
        if (c.moveToFirst()) { id = c.getLong(0); seen = c.getInt(1); }
        c.close();

        ContentValues v = new ContentValues();
        v.put("label", label.trim());
        v.put("confidence", confidence);
        v.put("last_seen", now);
        v.put("next_due", now + intervalDays(confidence) * DAY);
        long result;
        if (id < 0) {
            v.put("ckey", key);
            v.put("seen", 1);
            result = d.insert("concept", null, v);
            snapshot(d, result, confidence, now);
        } else {
            v.put("seen", seen + 1);
            d.update("concept", v, "id=?", new String[]{String.valueOf(id)});
            snapshot(d, id, confidence, now);
            result = id;
        }
        if (result >= 0 && Prefs.pinned(app) == result) Prefs.setPinned(app, -1);
        return result;
    }

    public void openGap(String conceptLabel, String text) {
        if (text == null || text.trim().length() == 0) return;
        SQLiteDatabase d = getWritableDatabase();
        Cursor c = d.rawQuery("SELECT id FROM concept WHERE ckey=?",
                new String[]{canon(conceptLabel)});
        long cid = c.moveToFirst() ? c.getLong(0) : -1;
        c.close();
        if (cid < 0) cid = touchConcept(conceptLabel, 40);
        if (cid < 0) return;
        // do not stack duplicates of the same gap
        Cursor dup = d.rawQuery("SELECT id FROM gap WHERE concept_id=? AND text=? AND open=1",
                new String[]{String.valueOf(cid), text.trim()});
        boolean exists = dup.moveToFirst();
        dup.close();
        if (exists) return;
        ContentValues v = new ContentValues();
        v.put("concept_id", cid); v.put("text", text.trim()); v.put("open", 1);
        v.put("created", System.currentTimeMillis());
        d.insert("gap", null, v);
    }

    public void resolveGap(String text) {
        if (text == null || text.trim().length() == 0) return;
        ContentValues v = new ContentValues();
        v.put("open", 0);
        getWritableDatabase().update("gap", v, "text=? AND open=1", new String[]{text.trim()});
    }


    private void snapshot(SQLiteDatabase d, long conceptId, int confidence, long at) {
        if (conceptId < 0) return;
        ContentValues v = new ContentValues();
        v.put("concept_id", conceptId);
        v.put("confidence", confidence);
        v.put("at", at);
        v.put("sess_id", currentSession);
        d.insert("snapshot", null, v);
    }

    /** Set for the duration of a consolidation so snapshots know their session. */
    public long currentSession = 0;

    /** Consecutive days ending today (or yesterday, so an evening habit survives). */
    public int streak() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT date(ended/1000,'unixepoch','localtime') FROM sess "
                        + "WHERE ended>0 ORDER BY 1 DESC LIMIT 400", null);
        java.util.HashSet<String> days = new java.util.HashSet<String>();
        while (c.moveToNext()) days.add(c.getString(0));
        c.close();
        if (days.isEmpty()) return 0;

        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (!days.contains(f.format(cal.getTime()))) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1);
            if (!days.contains(f.format(cal.getTime()))) return 0;
        }
        int n = 0;
        while (days.contains(f.format(cal.getTime()))) {
            n++;
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1);
        }
        return n;
    }

    // ---- concept detail ---------------------------------------------------



    /**
     * Make this concept the focus for the next targeted session.
     * Pinning it, rather than backdating next_due, keeps decay from cratering a score
     * the learner just asked to practise.
     */
    public void bumpToFront(long id) {
        Prefs.setPinned(app, id);
    }
    public Model.Concept byLabel(String label) {
        String key = canon(label);
        if (key.length() == 0) return null;
        List<Model.Concept> all = concepts();
        for (int i = 0; i < all.size(); i++) {
            if (key.equals(all.get(i).key)) return all.get(i);
        }
        return null;
    }
    public Model.Concept concept(long id) {
        List<Model.Concept> all = concepts();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == id) return all.get(i);
        }
        return null;
    }

    /** Confidence readings oldest first: {confidence, at}. Drives the sparkline. */
    public List<long[]> trend(long conceptId) {
        List<long[]> out = new ArrayList<long[]>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT confidence, at FROM snapshot WHERE concept_id=? ORDER BY at ASC",
                new String[]{String.valueOf(conceptId)});
        while (c.moveToNext()) out.add(new long[]{c.getLong(0), c.getLong(1)});
        c.close();
        return out;
    }

    public List<Model.Sess> sessionsFor(long conceptId) {
        List<Model.Sess> out = new ArrayList<Model.Sess>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT s.id,s.started,s.ended,s.title,s.summary FROM sess s "
                        + "WHERE s.id IN (SELECT sess_id FROM snapshot WHERE concept_id=?) "
                        + "AND s.ended>0 ORDER BY s.ended DESC", new String[]{String.valueOf(conceptId)});
        while (c.moveToNext()) {
            Model.Sess s = new Model.Sess();
            s.id = c.getLong(0); s.started = c.getLong(1); s.ended = c.getLong(2);
            s.title = c.getString(3); s.summary = c.getString(4);
            out.add(s);
        }
        c.close();
        return out;
    }

    /** @return false when the name is empty or another concept already uses it. */
    public boolean renameConcept(long id, String label) {
        if (label == null || label.trim().length() == 0) return false;
        String key = canon(label);
        SQLiteDatabase d = getWritableDatabase();
        Cursor c = d.rawQuery("SELECT id FROM concept WHERE ckey=? AND id<>?",
                new String[]{key, String.valueOf(id)});
        boolean clash = c.moveToFirst();
        c.close();
        if (clash) return false;
        ContentValues v = new ContentValues();
        v.put("label", label.trim());
        v.put("ckey", key);
        d.update("concept", v, "id=?", new String[]{String.valueOf(id)});
        return true;
    }

    /** Fold one concept into another, keeping the target's name and both histories. */
    public void merge(long fromId, long intoId) {
        if (fromId == intoId) return;
        SQLiteDatabase d = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("concept_id", intoId);
        d.update("gap", v, "concept_id=?", new String[]{String.valueOf(fromId)});
        d.update("snapshot", v, "concept_id=?", new String[]{String.valueOf(fromId)});
        d.update("source", v, "concept_id=?", new String[]{String.valueOf(fromId)});
        Cursor dups = d.rawQuery("SELECT text, MIN(id) FROM gap WHERE concept_id=? AND open=1 "
                + "GROUP BY text HAVING COUNT(*)>1", new String[]{String.valueOf(intoId)});
        ArrayList<String[]> dupRows = new ArrayList<String[]>();
        while (dups.moveToNext()) {
            dupRows.add(new String[]{dups.getString(0), String.valueOf(dups.getLong(1))});
        }
        dups.close();
        for (int i = 0; i < dupRows.size(); i++) {
            d.delete("gap", "concept_id=? AND open=1 AND text=? AND id<>?",
                    new String[]{String.valueOf(intoId), dupRows.get(i)[0], dupRows.get(i)[1]});
        }
        if (Prefs.pinned(app) == fromId) Prefs.setPinned(app, intoId);
        Cursor c = d.rawQuery("SELECT seen FROM concept WHERE id=?",
                new String[]{String.valueOf(fromId)});
        int extra = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        d.execSQL("UPDATE concept SET seen = seen + ? WHERE id = ?",
                new Object[]{extra, intoId});
        d.delete("concept", "id=?", new String[]{String.valueOf(fromId)});
    }

    public void archive(long id) {
        SQLiteDatabase d = getWritableDatabase();
        d.delete("gap", "concept_id=?", new String[]{String.valueOf(id)});
        d.delete("snapshot", "concept_id=?", new String[]{String.valueOf(id)});
        d.delete("source", "concept_id=?", new String[]{String.valueOf(id)});
        d.delete("concept", "id=?", new String[]{String.valueOf(id)});
        if (Prefs.pinned(app) == id) Prefs.setPinned(app, -1);
    }

    // ---- sessions ---------------------------------------------------------

    public void renameSession(long id, String title) {
        if (title == null || title.trim().length() == 0) return;
        ContentValues v = new ContentValues();
        v.put("title", title.trim());
        getWritableDatabase().update("sess", v, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Model.Sess> search(String q) {
        String visible = "(ended>0 OR id IN (SELECT sess_id FROM msg))";
        String order = "ORDER BY CASE WHEN ended>0 THEN ended ELSE started END DESC";
        if (q == null || q.trim().length() == 0) {
            return readSessions("SELECT id,started,ended,title,summary FROM sess WHERE "
                    + visible + " " + order + " LIMIT 300", null);
        }
        String like = "%" + q.trim() + "%";
        return readSessions("SELECT id,started,ended,title,summary FROM sess WHERE "
                        + visible + " AND (title LIKE ? OR summary LIKE ?) " + order,
                new String[]{like, like});
    }

    private List<Model.Sess> readSessions(String sql, String[] args) {
        List<Model.Sess> out = new ArrayList<Model.Sess>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        while (c.moveToNext()) out.add(readSess(c));
        c.close();
        return out;
    }

    private Model.Sess readSess(Cursor c) {
        Model.Sess s = new Model.Sess();
        s.id = c.getLong(0); s.started = c.getLong(1); s.ended = c.getLong(2);
        s.title = c.getString(3); s.summary = c.getString(4);
        return s;
    }

    /** Empty shells left behind when a session is abandoned before anyone speaks. */
    public void dropAbandoned() {
        long cutoff = System.currentTimeMillis() - 2L * 60L * 1000L;
        getWritableDatabase().execSQL(
                "DELETE FROM sess WHERE ended=0 AND started<? "
                        + "AND id NOT IN (SELECT sess_id FROM msg)",
                new Object[]{cutoff});
    }

    /** Drop messages added after the learner reopened a session, keeping the earlier ones. */
    public void trimMessagesAfter(long sessId, int keep) {
        if (keep < 0) keep = 0;
        SQLiteDatabase d = getWritableDatabase();
        Cursor c = d.rawQuery("SELECT id FROM msg WHERE sess_id=? ORDER BY id",
                new String[]{String.valueOf(sessId)});
        ArrayList<Long> drop = new ArrayList<Long>();
        int i = 0;
        while (c.moveToNext()) {
            if (i >= keep) drop.add(Long.valueOf(c.getLong(0)));
            i++;
        }
        c.close();
        for (int j = 0; j < drop.size(); j++) {
            d.delete("msg", "id=?", new String[]{String.valueOf(drop.get(j).longValue())});
        }
    }

    /** Sessions per week for the last n weeks, oldest first. */
    public int[] sessionsPerWeek(int weeks) {
        int[] out = new int[weeks];
        long now = System.currentTimeMillis();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ended FROM sess WHERE ended>0", null);
        while (c.moveToNext()) {
            long ended = c.getLong(0);
            int back = (int) ((now - ended) / (7L * DAY));
            if (back >= 0 && back < weeks) out[weeks - 1 - back]++;
        }
        c.close();
        return out;
    }

    /**
     * Average confidence across every concept, at each point in time.
     *
     * Averaging only the snapshots written on a given day would be wrong: on a day when
     * one concept was studied, that single score would be reported as the overall average.
     * Each concept therefore carries its last known value forward, so the line always
     * averages everything known at that moment.
     */
    public List<long[]> overallTrend() {
        List<long[]> out = new ArrayList<long[]>();
        java.util.LinkedHashMap<Long, Integer> latest =
                new java.util.LinkedHashMap<Long, Integer>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT concept_id, confidence, at, date(at/1000,'unixepoch','localtime') "
                        + "FROM snapshot ORDER BY at ASC", null);
        String day = null;
        long at = 0;
        while (c.moveToNext()) {
            String thisDay = c.getString(3);
            if (day != null && !day.equals(thisDay)) out.add(new long[]{average(latest), at});
            latest.put(c.getLong(0), c.getInt(1));
            day = thisDay;
            at = c.getLong(2);
        }
        if (day != null) out.add(new long[]{average(latest), at});
        c.close();
        return out;
    }

    private static long average(java.util.Map<Long, Integer> m) {
        if (m.isEmpty()) return 0;
        long sum = 0;
        for (Integer v : m.values()) sum += v;
        return Math.round(sum / (double) m.size());
    }

    /** What the concepts average right now, decay included. The honest headline figure. */
    public int currentAverage() {
        List<Model.Concept> all = concepts();
        if (all.isEmpty()) return 0;
        int sum = 0;
        for (int i = 0; i < all.size(); i++) sum += all.get(i).effective;
        return Math.round(sum / (float) all.size());
    }

    // ---- taking your data with you ---------------------------------------

    /** Everything, as JSON. No account and no sync means export is the only safety net. */
    public String exportJson() throws Exception {
        org.json.JSONObject root = new org.json.JSONObject();
        root.put("app", "recall");
        root.put("version", VERSION);
        root.put("exported", System.currentTimeMillis());
        root.put("concept", dump("SELECT id,ckey,label,confidence,seen,last_seen,next_due,"
                + "archived FROM concept"));
        root.put("gap", dump("SELECT id,concept_id,text,open,created FROM gap"));
        root.put("sess", dump("SELECT id,started,ended,title,summary,mode FROM sess"));
        root.put("msg", dump("SELECT id,sess_id,role,text,ts FROM msg"));
        root.put("snapshot", dump("SELECT id,concept_id,confidence,at,sess_id FROM snapshot"));
        root.put("source", dump("SELECT id,concept_id,title,body,added FROM source"));
        return root.toString(2);
    }

    private org.json.JSONArray dump(String sql) throws Exception {
        org.json.JSONArray rows = new org.json.JSONArray();
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        while (c.moveToNext()) {
            org.json.JSONObject o = new org.json.JSONObject();
            for (int i = 0; i < c.getColumnCount(); i++) {
                if (c.getType(i) == Cursor.FIELD_TYPE_NULL) continue;
                if (c.getType(i) == Cursor.FIELD_TYPE_INTEGER) o.put(c.getColumnName(i), c.getLong(i));
                else o.put(c.getColumnName(i), c.getString(i));
            }
            rows.put(o);
        }
        c.close();
        return rows;
    }

    /** Replaces everything. Returns how many concepts came back. */
    public int importJson(String json) throws Exception {
        if (json == null || json.trim().length() == 0) {
            throw new Exception("That file is empty.");
        }
        org.json.JSONObject root;
        try {
            root = new org.json.JSONObject(json);
        } catch (Exception bad) {
            throw new Exception("That file is not a Recall export.");
        }
        if (!"recall".equals(root.optString("app"))) {
            throw new Exception("That file is not a Recall export.");
        }
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.execSQL("DELETE FROM concept"); d.execSQL("DELETE FROM gap");
            d.execSQL("DELETE FROM sess");    d.execSQL("DELETE FROM msg");
            d.execSQL("DELETE FROM snapshot");
            d.execSQL("DELETE FROM source");
            restore(d, "concept", root.optJSONArray("concept"));
            restore(d, "gap", root.optJSONArray("gap"));
            restore(d, "sess", root.optJSONArray("sess"));
            restore(d, "msg", root.optJSONArray("msg"));
            restore(d, "snapshot", root.optJSONArray("snapshot"));
            restore(d, "source", root.optJSONArray("source"));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        long pin = Prefs.pinned(app);
        if (pin >= 0 && concept(pin) == null) Prefs.setPinned(app, -1);
        return conceptCount();
    }

    private void restore(SQLiteDatabase d, String table, org.json.JSONArray rows) {
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            org.json.JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            ContentValues v = new ContentValues();
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object val = o.opt(k);
                if (val instanceof Number) v.put(k, ((Number) val).longValue());
                else if (val != null) v.put(k, String.valueOf(val));
            }
            try {
                d.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception skipBadRow) { }
        }
    }

    // ---- the learner's own material ---------------------------------------

    public void addSource(long conceptId, String title, String body) {
        if (body == null || body.trim().length() == 0) return;
        ContentValues v = new ContentValues();
        v.put("concept_id", conceptId);
        v.put("title", title == null || title.trim().length() == 0 ? "Notes" : title.trim());
        v.put("body", body.trim());
        v.put("added", System.currentTimeMillis());
        getWritableDatabase().insert("source", null, v);
    }

    /** {id, title, body} for a concept, newest first. */
    public List<String[]> sourcesFor(long conceptId) {
        List<String[]> out = new ArrayList<String[]>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,body FROM source WHERE concept_id=? ORDER BY added DESC",
                new String[]{String.valueOf(conceptId)});
        while (c.moveToNext()) {
            out.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1), c.getString(2)});
        }
        c.close();
        return out;
    }

    public void deleteSource(long id) {
        getWritableDatabase().delete("source", "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteSourcesFor(long conceptId) {
        getWritableDatabase().delete("source", "concept_id=?",
                new String[]{String.valueOf(conceptId)});
    }

    /** The learner's own notes for whatever this session is targeting. */
    public String sourceTextFor(Model.Concept k) {
        if (k == null) return "";
        List<String[]> all = sourcesFor(k.id);
        if (all.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            b.append("--- ").append(all.get(i)[1]).append(" ---\n");
            b.append(all.get(i)[2]).append("\n\n");
        }
        return b.toString();
    }
    public void wipe() {
        SQLiteDatabase d = getWritableDatabase();
        d.execSQL("DELETE FROM concept");
        d.execSQL("DELETE FROM gap");
        d.execSQL("DELETE FROM sess");
        d.execSQL("DELETE FROM msg");
        d.execSQL("DELETE FROM snapshot");
        d.execSQL("DELETE FROM source");
        Prefs.setPinned(app, -1);
    }

    // ---- rendering the model for the tutor -------------------------------

    public static String ago(long ts) {
        if (ts <= 0) return "never";
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60L * 1000) return "just now";
        if (diff < 60L * 60 * 1000) return (diff / (60L * 1000)) + " min ago";
        if (diff < DAY) return (diff / (60L * 60 * 1000)) + "h ago";
        long days = Math.round(diff / (double) DAY);
        if (days == 1) return "yesterday";
        if (days < 30) return days + " days ago";
        long months = Math.round(days / 30.0);
        return months <= 1 ? "a month ago" : months + " months ago";
    }

    /**
     * The learner model as the tutor sees it. This block is the difference between
     * a tutor and a chat window, so it goes into every request.
     */
    public String profileText(String who) { return profileText(who, true, false); }

    public String profileText(String who, boolean includeTarget) {
        return profileText(who, includeTarget, false);
    }

    /**
     * @param includeTarget only for a targeted session. An open session must not carry a
     *                      target line: the model follows it over what the learner just
     *                      asked for, which is how "teach me networking" once produced a
     *                      lecture on the Turing Test.
     * @param continuing    the learner reopened a transcript. Neither a fresh target nor
     *                      the "no preset target" lecture belongs in that prompt.
     */
    public String profileText(String who, boolean includeTarget, boolean continuing) {
        StringBuilder b = new StringBuilder();
        List<Model.Concept> all = concepts();

        if (all.isEmpty()) {
            if (who != null && who.length() > 0) b.append("They are called ").append(who).append(".\n");
            if (continuing) {
                b.append("No concepts are stored yet. Continue the conversation already in ")
                        .append("the transcript.\n");
            } else if (sessionCount() == 0) {
                b.append("This is your first ever session with this learner. ");
                b.append("You know nothing about them yet.\n");
                b.append("\nOpen by asking what they are trying to learn and why, then start ");
                b.append("teaching immediately. Do not ask more than one question before you begin.\n");
            } else {
                b.append("No concepts are stored right now.\n");
                b.append("Ask what they want to work on, then teach.\n");
            }
            return b.toString();
        }

        if (who != null && who.length() > 0) b.append("Learner: ").append(who).append("\n");
        b.append("Sessions completed: ").append(sessionCount()).append("\n\n");

        long now = System.currentTimeMillis();
        b.append("CONCEPTS (confidence 0-100; higher means more solid)\n");
        for (int i = 0; i < all.size(); i++) {
            Model.Concept k = all.get(i);
            b.append("- ").append(k.label).append(" | confidence ").append(k.effective);
            if (k.effective < k.confidence) {
                b.append(" (was ").append(k.confidence).append(", faded while untouched)");
            }
            b.append(" | covered ").append(k.seen).append(k.seen == 1 ? " time" : " times");
            b.append(" | last touched ").append(ago(k.lastSeen));
            if (k.nextDue <= now) {
                long over = (now - k.nextDue) / DAY;
                b.append(over >= 1 ? " | DUE (overdue " + over + "d)" : " | DUE");
            }
            b.append("\n");
            for (int j = 0; j < k.gaps.size(); j++) {
                b.append("    unresolved: ").append(k.gaps.get(j)).append("\n");
            }
        }

        List<Model.Sess> rs = recent(4);
        if (!rs.isEmpty()) {
            b.append("\nWHAT HAPPENED RECENTLY\n");
            for (int i = 0; i < rs.size(); i++) {
                Model.Sess s = rs.get(i);
            b.append("- ").append(ago(s.ended)).append(", \"")
                    .append(s.title == null ? "" : s.title).append("\": ");
            b.append(s.summary == null ? "" : s.summary).append("\n");
            }
        }

        Model.Concept f = focus();
        if (continuing) {
            b.append("\nThe learner reopened an earlier session to continue it. Stay with ")
                    .append("that conversation. Do not switch them onto a different concept ")
                    .append("unless they ask.\n");
        } else if (includeTarget && f != null) {
            b.append("\nTARGET FOR THIS SESSION: ").append(f.label);
            if (!f.gaps.isEmpty()) b.append(" -- specifically: ").append(f.gaps.get(0));
            b.append("\n");
        } else if (!includeTarget) {
            b.append("\nTHIS SESSION HAS NO PRESET TARGET. The learner chose an open ")
             .append("session, so they pick the subject. Everything above is context about ")
             .append("who they are, not an agenda for today. Whatever they name in reply to ")
             .append("your opening question IS the subject: teach that, even when it appears ")
             .append("nowhere above. Never pull them onto a concept from the list unless ")
             .append("they ask for it.\n");
        }
        return b.toString();
    }
}
