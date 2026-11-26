package com.winlator.saipen;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extra container environment for speed tests, set from a computer and
 * nowhere on screen: `adb shell setprop debug.saipen.env "BOX64_DYNACACHE=1"`
 * (space between several). Applied just before SAI starts; when the property
 * is emptied again, the variables it added last time are taken back out, so
 * a test never stays behind in the container. The speed log names it.
 *
 * No Android types: the JVM tests run it as it is.
 */
public final class DebugEnv {
    public static final String PROP = "debug.saipen.env";
    /** The names added last time, comma-separated (SharedPreferences). */
    public static final String PREF_KEYS = "debug_env_keys";

    private DebugEnv() {}

    /** {new env, the names now added} from `env`, the property's text and
     *  the names added last time. */
    public static String[] apply(String env, String prop, String lastKeys) {
        String out = env == null ? "" : env;
        if (lastKeys != null) {
            for (String k : lastKeys.split(",")) {
                if (!k.trim().isEmpty()) out = EnvVarsEdit.set(out, k.trim(), null);
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        if (prop != null) {
            for (String pair : prop.trim().split("\\s+")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = pair.substring(0, eq);
                if (!k.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                out = EnvVarsEdit.set(out, k, pair.substring(eq + 1));
                keys.add(k);
            }
        }
        return new String[] {out, String.join(",", keys)};
    }
}
