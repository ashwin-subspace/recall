package com.recall.data;

import java.util.ArrayList;
import java.util.List;

/** Plain data holders for the learner model. */
public class Model {

    public static class Concept {
        public long id;
        public String key;      // canonical lowercase name, used for matching across sessions
        public String label;    // what the tutor calls it
        public int confidence;  // 0..100, as last judged and stored
        public int effective;   // confidence after time decay; what the app acts on
        public int seen;        // how many sessions have touched it
        public long lastSeen;
        public long nextDue;
        public List<String> gaps = new ArrayList<String>();  // open misconceptions
    }

    public static class Gap {
        public long id;
        public long conceptId;
        public String text;
        public boolean open;
    }

    public static class Sess {
        public long id;
        public long started;
        public long ended;
        public String title;
        public String summary;
    }

    public static class Msg {
        public String role;   // "user" or "assistant"
        public String text;
        public long ts;

        public Msg(String role, String text, long ts) {
            this.role = role;
            this.text = text;
            this.ts = ts;
        }
    }
}
