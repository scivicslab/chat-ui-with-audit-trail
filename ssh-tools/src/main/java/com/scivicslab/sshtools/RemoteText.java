package com.scivicslab.sshtools;

/**
 * Checks and quotes the strings that go into a remote command (tasks/todo.md, "Stage 11 spec"). A value with a NUL or
 * a line break is refused before {@code ssh} starts; a value ssh-connector puts into a command it builds, such as a
 * path after {@code cat >}, is POSIX single-quoted, {@code '} becoming {@code '\''}.
 */
public final class RemoteText {

    private RemoteText() {
    }

    /**
     * @param field the argument's name, for the message
     * @param value the argument's value
     * @return {@code null} when the value may be used, otherwise an {@code error: } text saying why not
     */
    public static String refusal(String field, String value) {
        if (value == null || value.isEmpty()) return "error: " + field + " is empty";
        if (value.indexOf('\0') >= 0) return "error: " + field + " contains a NUL character";
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "error: " + field + " contains a line break; send multi-line text with submit_job or upload_file";
        }
        return null;
    }

    /** @return {@code value} as one POSIX single-quoted word */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
