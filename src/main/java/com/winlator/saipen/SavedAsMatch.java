package com.winlator.saipen;

import java.util.List;

/**
 * Which file a new canvas ("NewCanvas1") was saved as, found by how it
 * looks: the session's last frame against the small picture of each drawing
 * saved while it ran or just after. A saved drawing looks exactly as the
 * session ended, so this works even when SAI closed right after saving and
 * no frame or window title ever carried the new name (pb.sai2, 2026-11-27).
 *
 * Lenient on purpose (DrawingLibrary's ASK thresholds): only files written
 * during that session are candidates, so a wrong match is unlikely.
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class SavedAsMatch {
    private SavedAsMatch() {}

    public static final class Candidate {
        public final String name;
        public final DrawingLibrary.Signature look;

        public Candidate(String name, DrawingLibrary.Signature look) {
            this.name = name;
            this.look = look;
        }
    }

    /** The name of the candidate that looks most like `closing`, or null if
     *  none looks enough like it. */
    public static String pick(DrawingLibrary.Signature closing, List<Candidate> candidates) {
        if (closing == null || !closing.isValid() || closing.isFeatureless()) return null;
        Candidate best = null;
        double bestSim = 0, bestDist = 1;
        for (Candidate c : candidates) {
            if (c.look == null || !c.look.isValid()) continue;
            double sim = DrawingLibrary.Signature.similarity(closing, c.look);
            double dist = DrawingLibrary.Signature.distance(closing, c.look);
            if (best == null || sim > bestSim) {
                best = c;
                bestSim = sim;
                bestDist = dist;
            }
        }
        if (best == null) return null;
        return bestSim >= DrawingLibrary.ASK_SIMILARITY && bestDist <= DrawingLibrary.ASK_DISTANCE ? best.name : null;
    }
}
