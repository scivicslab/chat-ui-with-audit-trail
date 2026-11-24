import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for the {@code claude} CLI, for the browser checks of the console. It speaks the stream-json the
 * harness provider reads: for every line on stdin (one user message) it prints a {@code system} line the first
 * time, then an {@code assistant} line and a {@code result} line, both with a {@code usage}. No model is involved,
 * so the token counts are the same every run: the first request of turn N has 3 input tokens, 41000 cache-creation
 * tokens on turn 1 and 200 on later turns, and 41000 cache-read tokens on later turns.
 *
 * <p>Started by the {@code claude} script beside it, which a throwaway instance finds first on its PATH.</p>
 */
public class FakeClaude {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String session = "fake-claude-session";
        int turn = 0;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            turn++;
            if (turn == 1) {
                out.println("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"" + session + "\",\"model\":\"fake\"}");
            }
            String usage = "{\"input_tokens\":3,\"cache_creation_input_tokens\":" + (turn == 1 ? 41000 : 200)
                    + ",\"cache_read_input_tokens\":" + (turn == 1 ? 0 : 41000) + ",\"output_tokens\":12}";
            String answer = "FAKE-CLAUDE-ANSWER-" + turn;
            out.println("{\"type\":\"assistant\",\"session_id\":\"" + session + "\",\"message\":{\"role\":\"assistant\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"" + answer + "\"}],\"usage\":" + usage + "}}");
            out.println("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\"" + session
                    + "\",\"result\":\"" + answer + "\",\"total_cost_usd\":0,\"duration_ms\":1,\"usage\":" + usage + "}");
        }
    }
}
