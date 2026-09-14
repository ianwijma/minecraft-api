package dev.example.mapi.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI entry point: speaks newline-delimited JSON-RPC 2.0 on stdio (Model
 * Context Protocol) and forwards tool calls to one MAPI instance.
 *
 * <pre>
 *   mcp-adapter --url http://127.0.0.1:25586 --token-file /path/token
 *   mcp-adapter --game-dir /path/to/instance
 * </pre>
 */
public final class Main {

    private Main() {
    }

    /**
     * Runs the adapter until stdin closes.
     *
     * @param args {@code --url <url>} and {@code --token-file <path>}, or
     *             {@code --game-dir <path>}
     */
    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            String key = args[i].startsWith("--") ? args[i].substring(2) : args[i];
            options.put(key, args[i + 1]);
        }
        InstanceClient instance;
        if (options.containsKey("game-dir")) {
            instance = InstanceClient.fromGameDir(Path.of(options.get("game-dir")));
        } else if (options.containsKey("url") && options.containsKey("token-file")) {
            instance = InstanceClient.fromUrl(options.get("url"), Path.of(options.get("token-file")));
        } else {
            System.err.println("usage: mcp-adapter (--url <url> --token-file <path> | --game-dir <path>)");
            return;
        }
        run(instance, System.in, System.out);
    }

    /**
     * Read-eval loop over the given streams (test seam).
     *
     * @param instance instance binding
     * @param in       request stream (one JSON-RPC request per line)
     * @param out      response sink
     * @return the responses written, newline-terminated
     */
    public static String run(InstanceClient instance, java.io.InputStream in, java.io.OutputStream out) {
        StringBuilder sink = new StringBuilder();
        McpServer server = new McpServer(instance, sink);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        PrintStream writer = new PrintStream(out, true, StandardCharsets.UTF_8);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!server.handleLine(line)) {
                    break;
                }
                if (sink.length() > 0) {
                    writer.print(sink);
                    writer.flush();
                    sink.setLength(0);
                }
            }
        } catch (IOException e) {
            System.err.println("mcp-adapter: stream error: " + e.getMessage());
        }
        return sink.toString();
    }

    /**
     * Convenience for tests: writes a file with content.
     *
     * @param path    target file
     * @param content text
     */
    static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
