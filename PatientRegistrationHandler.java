
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

public class PatientRegistrationHandler {

    public static void handle(HttpExchange exchange)
            throws IOException {

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            addCors(exchange);
            exchange.getResponseHeaders().set(
                "Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set(
                "Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "Method not allowed");
            return;
        }

        String origin = exchange.getRequestHeaders()
                .getFirst("Origin");

        if (!"http://127.0.0.1:5500".equals(origin)
                && !"http://localhost:5500".equals(origin)) {
            send(exchange, 403, "Origin not allowed");
            return;
        }

        try {
            Map<String, String> data = readForm(exchange);

            String name = data.getOrDefault("name", "").trim();
            String ageText = data.getOrDefault("age", "").trim();
            String gender = data.getOrDefault("gender", "").trim();
            String phone = data.getOrDefault("phone", "").trim();
            String email = data.getOrDefault("email", "")
                    .trim().toLowerCase();
            String username = data.getOrDefault("username", "").trim();
            String password = data.getOrDefault("password", "");

            if (name.isBlank() || ageText.isBlank()
                    || gender.isBlank() || phone.isBlank()
                    || email.isBlank() || username.isBlank()
                    || password.isBlank()) {
                send(exchange, 400, "All fields are required.");
                return;
            }

            int age = Integer.parseInt(ageText);

            if (age < 1 || age > 120
                    || name.length() > 100
                    || !gender.matches("Male|Female|Other")
                    || !phone.matches("[0-9]{10,15}")
                    || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                    || username.length() < 4
                    || username.length() > 50
                    || !username.matches("[A-Za-z0-9_]+")
                    || password.length() < 8
                    || password.length() > 128) {
                send(exchange, 400, "Please check the entered details.");
                return;
            }

            String passwordHash = PasswordUtil.hashPassword(password);

            try (Connection con = DBConnection.getConnection()) {
                con.setAutoCommit(false);

                try {
                    int patientId;

                    String patientSql =
                        "INSERT INTO patients " +
                        "(patient_name, age, gender, phone, email) " +
                        "VALUES (?, ?, ?, ?, ?)";

                    try (PreparedStatement ps =
                            con.prepareStatement(
                                patientSql,
                                PreparedStatement.RETURN_GENERATED_KEYS)) {

                        ps.setString(1, name);
                        ps.setInt(2, age);
                        ps.setString(3, gender);
                        ps.setString(4, phone);
                        ps.setString(5, email);
                        ps.executeUpdate();

                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new Exception(
                                    "Patient ID was not generated.");
                            }
                            patientId = keys.getInt(1);
                        }
                    }

                    String userSql =
                        "INSERT INTO users " +
                        "(full_name, username, email, password_hash, " +
                        "role, account_status, patient_id) " +
                        "VALUES (?, ?, ?, ?, 'Patient', 'Active', ?)";

                    try (PreparedStatement ps =
                            con.prepareStatement(userSql)) {

                        ps.setString(1, name);
                        ps.setString(2, username);
                        ps.setString(3, email);
                        ps.setString(4, passwordHash);
                        ps.setInt(5, patientId);
                        ps.executeUpdate();
                    }

                    con.commit();
                    send(exchange, 201,
                        "Patient registration successful!");

                } catch (Exception e) {
                    con.rollback();

                    if (e.getMessage() != null
                            && e.getMessage().contains("Duplicate")) {
                        send(exchange, 409,
                            "Username or email already exists.");
                    } else {
                        send(exchange, 500,
                            "Registration could not be completed.");
                        System.out.println(
                            "Registration error: " + e.getMessage());
                    }

                } finally {
                    con.setAutoCommit(true);
                }
            }

        } catch (NumberFormatException e) {
            send(exchange, 400, "Please enter a valid age.");
        } catch (Exception e) {
            System.out.println(
                "Registration error: " + e.getMessage());
            send(exchange, 500, "Registration failed.");
        }
    }

    private static Map<String, String> readForm(
            HttpExchange exchange) throws IOException {

        byte[] body = exchange.getRequestBody().readNBytes(8193);

        if (body.length > 8192) {
            throw new IOException("Request too large");
        }

        String content = new String(body, StandardCharsets.UTF_8);
        Map<String, String> data = new HashMap<>();

        for (String pair : content.split("&")) {
            String[] parts = pair.split("=", 2);

            if (parts.length == 2) {
                String key = URLDecoder.decode(
                    parts[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(
                    parts[1], StandardCharsets.UTF_8);
                data.put(key, value);
            }
        }

        return data;
    }

    private static void addCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders()
                .getFirst("Origin");

        if ("http://127.0.0.1:5500".equals(origin)
                || "http://localhost:5500".equals(origin)) {
            exchange.getResponseHeaders().set(
                "Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set(
                "Access-Control-Allow-Credentials", "true");
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
    }

    private static void send(
            HttpExchange exchange, int status, String message)
            throws IOException {

        addCors(exchange);

        exchange.getResponseHeaders().set(
            "Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().set(
            "Cache-Control", "no-store");

        byte[] response = message.getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(status, response.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
}
