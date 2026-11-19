package com.winlator.saipen;

/**
 * Sets or removes one variable in Winlator's container environment, a
 * space-separated "NAME=value NAME=value" string (Container.getEnvVars).
 * The timelapse switch turns WT_TIMELAPSE on and off this way: our
 * wintab32.dll reads it inside SAI at start (timelapse_win.h).
 *
 * No Android types: the JVM tests check the strings.
 */
public final class EnvVarsEdit {
    private EnvVarsEdit() {}

    /** env with `name` set to `value`, or removed when value is null. The
     *  other variables keep their order. */
    public static String set(String env, String name, String value) {
        StringBuilder out = new StringBuilder();
        boolean done = false;
        for (String part : (env == null ? "" : env).trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            boolean same = part.equals(name) || part.startsWith(name + "=");
            if (same) {
                if (value == null || done) continue;
                part = name + "=" + value;
                done = true;
            }
            if (out.length() > 0) out.append(' ');
            out.append(part);
        }
        if (value != null && !done) {
            if (out.length() > 0) out.append(' ');
            out.append(name).append('=').append(value);
        }
        return out.toString();
    }
}
