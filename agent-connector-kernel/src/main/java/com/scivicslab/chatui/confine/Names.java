package com.scivicslab.chatui.confine;

import java.util.regex.Pattern;

/** The scope and model names a harness configuration may carry: no quoting is needed for any of the three formats. */
final class Names {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@+-]*");

    private Names() {
    }

    /** @throws IllegalArgumentException when either name could break out of a quoted TOML, JSON or YAML string */
    static void check(String scope, String model) {
        for (String name : new String[] {scope, model}) {
            if (name == null || !SAFE.matcher(name).matches()) {
                throw new IllegalArgumentException("not a plain name: " + name);
            }
        }
    }
}
