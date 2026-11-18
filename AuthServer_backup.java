
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AuthServer {

    private static final Map<String, AuthenticatedUser> sessions =
            new ConcurrentHashMap<>();

    private static final SecureRandom random = new SecureRandom();

    private static final String[] ALLOWED_ORIGINS = {
        "http://127.0.0.1:5500",
        "http://localhost:5500"
    };

    public static void main(String[] args) throws Exception {

        HttpServer server = HttpServer.create(
            new InetSocketAddress(8080), 0
        );

        server.createContext("/api/status", exchange -> {
            sendResponse(exchange, 200,
                "MediCare Plus Backend Running!");
        });

        server.createContext("/api/login", exchange -> {

            if (handleOptions(exchange)) return;

            if (!exchange.getRequestMethod().equals("POST")) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            if (!isAllowedOrigin(exchange)) {
                sendResponse(exchange, 403, "Origin not allowed");
                return;
            }

            Map<String, String> data;

            try {
                data = readFormData(exchange);
            } catch (Exception e) {
                sendResponse(exchange, 400, "Invalid request");
                return;
            }

            String identifier = data.getOrDefault("username", "").trim();
            String password = data.getOrDefault("password", "");
            String role = data.getOrDefault("role", "");

            boolean validRole =
                role.equals("Patient")
                || role.equals("Doctor")
                || role.equals("Admin");

            if (identifier.isBlank()
                    || password.isBlank()
                    || !validRole) {

                sendResponse(exchange, 400,
                    "Please enter valid login details.");
                return;
            }

            AuthenticatedUser user =
                UserDAO.authenticate(identifier, password, role);

            if (user == null) {
                sendResponse(exchange, 401,
                    "Invalid username, password or role.");
                return;
            }

            String sessionId = createSessionId();

            sessions.put(sessionId, user);

            exchange.getResponseHeaders().add(
                "Set-Cookie",
                "MC_SESSION=" + sessionId
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800"
            );

            sendResponse(exchange, 200, "Login successful");
        });

        server.createContext("/api/me", exchange -> {

            if (handleOptions(exchange)) return;

            if (!exchange.getRequestMethod().equals("GET")) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            if (!isAllowedOrigin(exchange)) {
                sendResponse(exchange, 403, "Origin not allowed");
                return;
            }

            String sessionId = getSessionId(exchange);
            AuthenticatedUser user = sessions.get(sessionId);

            if (user == null) {
                sendResponse(exchange, 401, "Not logged in");
                return;
            }

            String json = "{"
                + "\"userId\":" + user.getUserId() + ","
                + "\"fullName\":\"" + escapeJson(user.getFullName()) + "\","
                + "\"username\":\"" + escapeJson(user.getUsername()) + "\","
                + "\"role\":\"" + escapeJson(user.getRole()) + "\""
                + "}";

            sendResponse(exchange, 200, json);
        });
server.createContext("/api/dashboard-stats", exchange -> {

    if (handleOptions(exchange)) return;

    if (!exchange.getRequestMethod().equals("GET")) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
    }

    if (!isAllowedOrigin(exchange)) {
        sendResponse(exchange, 403, "Origin not allowed");
        return;
    }

    String sessionId = getSessionId(exchange);
    AuthenticatedUser user = sessions.get(sessionId);

    if (user == null || !"Admin".equals(user.getRole())) {
        sendResponse(exchange, 401, "Admin login required");
        return;
    }

    String sql =
        "SELECT " +
        "(SELECT COUNT(*) FROM patients) AS total_patients, " +
        "(SELECT COUNT(*) FROM doctors) AS total_doctors, " +
        "(SELECT COUNT(*) FROM appointments) AS total_appointments, " +
        "(SELECT COUNT(*) FROM appointments " +
        "WHERE appointment_status = 'Cancelled') AS cancelled_appointments";

    try (Connection con = DBConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {

        if (rs.next()) {
            String json = "{"
                + "\"totalPatients\":" + rs.getInt("total_patients") + ","
                + "\"totalDoctors\":" + rs.getInt("total_doctors") + ","
                + "\"totalAppointments\":" + rs.getInt("total_appointments") + ","
                + "\"cancelledAppointments\":" + rs.getInt("cancelled_appointments")
                + "}";

            sendJson(exchange, 200, json);
        }

    } catch (Exception e) {
        System.out.println("Dashboard stats error: " + e.getMessage());
        sendResponse(exchange, 500, "Unable to load dashboard statistics");
    }
});
        server.createContext("/api/logout", exchange -> {

            if (handleOptions(exchange)) return;

            if (!exchange.getRequestMethod().equals("POST")) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            if (!isAllowedOrigin(exchange)) {
                sendResponse(exchange, 403, "Origin not allowed");
                return;
            }

            String sessionId = getSessionId(exchange);

            if (sessionId != null) {
                sessions.remove(sessionId);
            }

            exchange.getResponseHeaders().add(
                "Set-Cookie",
                "MC_SESSION=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
            );

            sendResponse(exchange, 200, "Logged out successfully");
        });

        server.setExecutor(null);
        server.start();

        System.out.println(
            "MediCare Plus Server Started at http://localhost:8080"
        );
    }

    private static String createSessionId() {

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes);
    }

    private static String getSessionId(HttpExchange exchange) {

        String cookie = exchange.getRequestHeaders()
            .getFirst("Cookie");

        if (cookie == null) return null;

        for (String part : cookie.split(";")) {
            String item = part.trim();

            if (item.startsWith("MC_SESSION=")) {
                return item.substring("MC_SESSION=".length());
            }
        }

        return null;
    }

    private static Map<String, String> readFormData(
            HttpExchange exchange) throws IOException {

        byte[] body = exchange.getRequestBody().readNBytes(8193);

        if (body.length > 8192) {
            throw new IOException("Request too large");
        }

        String content = new String(
            body, StandardCharsets.UTF_8
        );

        Map<String, String> data = new HashMap<>();

        for (String pair : content.split("&")) {
            String[] parts = pair.split("=", 2);

            if (parts.length == 2) {
                String key = URLDecoder.decode(
                    parts[0], StandardCharsets.UTF_8
                );

                String value = URLDecoder.decode(
                    parts[1], StandardCharsets.UTF_8
                );

                data.put(key, value);
            }
        }

        return data;
    }

    private static boolean isAllowedOrigin(HttpExchange exchange) {

        String origin = exchange.getRequestHeaders()
            .getFirst("Origin");

        if (origin == null) return false;

        for (String allowed : ALLOWED_ORIGINS) {
            if (allowed.equals(origin)) return true;
        }

        return false;
    }

    private static boolean handleOptions(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("OPTIONS")) {
            return false;
        }

        String origin = exchange.getRequestHeaders()
            .getFirst("Origin");

        if (origin != null && isAllowedOrigin(exchange)) {

            exchange.getResponseHeaders().set(
                "Access-Control-Allow-Origin", origin
            );

            exchange.getResponseHeaders().set(
                "Access-Control-Allow-Credentials", "true"
            );

            exchange.getResponseHeaders().set(
                "Access-Control-Allow-Methods",
                "GET, POST, OPTIONS"
            );

            exchange.getResponseHeaders().set(
                "Access-Control-Allow-Headers", "Content-Type"
            );

            exchange.getResponseHeaders().set(
                "Vary", "Origin"
            );

            exchange.sendResponseHeaders(204, -1);

        } else {
            exchange.sendResponseHeaders(403, -1);
        }

        exchange.close();
        return true;
    }

    private static String escapeJson(String value) {

        if (value == null) return "";

        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }

    private static void sendResponse(
            HttpExchange exchange,
            int status,
            String message) throws IOException {

        String origin = exchange.getRequestHeaders()
            .getFirst("Origin");

        if (origin != null && isAllowedOrigin(exchange)) {
            exchange.getResponseHeaders().set(
                "Access-Control-Allow-Origin", origin
            );

            exchange.getResponseHeaders().set(
                "Access-Control-Allow-Credentials", "true"
            );

            exchange.getResponseHeaders().set(
                "Vary", "Origin"
            );
        }

        exchange.getResponseHeaders().set(
            "Content-Type", "text/plain; charset=UTF-8"
        );

        exchange.getResponseHeaders().set(
            "Cache-Control", "no-store"
        );

        byte[] response = message.getBytes(
            StandardCharsets.UTF_8
        );

        exchange.sendResponseHeaders(status, response.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
    private static void sendJson(
        HttpExchange exchange,
        int status,
        String json) throws IOException {

    String origin = exchange.getRequestHeaders()
        .getFirst("Origin");

    if (origin != null && isAllowedOrigin(exchange)) {
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Origin", origin
        );
        exchange.getResponseHeaders().set(
            "Access-Control-Allow-Credentials", "true"
        );
        exchange.getResponseHeaders().set("Vary", "Origin");
    }

    exchange.getResponseHeaders().set(
        "Content-Type", "application/json; charset=UTF-8"
    );
    exchange.getResponseHeaders().set("Cache-Control", "no-store");

    byte[] response = json.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, response.length);

    try (OutputStream os = exchange.getResponseBody()) {
        os.write(response);
    }
}
}
