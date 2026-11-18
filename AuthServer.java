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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

public class AuthServer {

    private static final Map<String, AuthenticatedUser> sessions =
            new ConcurrentHashMap<>();

    private static final SecureRandom random = new SecureRandom();

   private static final String[] ALLOWED_ORIGINS = {
    "http://127.0.0.1:5500",
    "http://localhost:5500"
};

    private static final Set<LocalTime> AVAILABLE_SLOTS = Set.of(
        LocalTime.of(9, 0),
        LocalTime.of(10, 0),
        LocalTime.of(11, 0),
        LocalTime.of(12, 0),
        LocalTime.of(14, 0),
        LocalTime.of(15, 0),
        LocalTime.of(16, 0)
    );

    public static void main(String[] args) throws Exception {

        HttpServer server = HttpServer.create(
            new InetSocketAddress(8080), 0
        );

        // BACKEND STATUS
        server.createContext("/api/status", exchange -> {
            sendResponse(exchange, 200,
                "MediCare Plus Backend Running!");
        });

        // LOGIN API
        server.createContext("/api/login", exchange -> {

            if (handleOptions(exchange)) return;

            if (!"POST".equals(exchange.getRequestMethod())) {
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
            try (Connection con = DBConnection.getConnection();
     PreparedStatement ps = con.prepareStatement(
         "INSERT INTO login_activity "
         + "(username, user_role, login_status) "
         + "VALUES (?, ?, ?)")) {

    ps.setString(1, identifier);
    ps.setString(2, role);
    ps.setString(3, "Successful");

    ps.executeUpdate();

} catch (Exception e) {
    System.out.println("Login activity error: " + e.getMessage());
}

            exchange.getResponseHeaders().add(
                "Set-Cookie",
                "MC_SESSION=" + sessionId
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800"
            );

            sendResponse(exchange, 200, "Login successful");
        });

        // LOGGED-IN USER DETAILS
        server.createContext("/api/me", exchange -> {

            if (handleOptions(exchange)) return;

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            if (!isAllowedOrigin(exchange)) {
                sendResponse(exchange, 403, "Origin not allowed");
                return;
            }

            AuthenticatedUser user = getSessionUser(exchange);

            if (user == null) {
                sendResponse(exchange, 401, "Not logged in");
                return;
            }

            String json = "{"
                + "\"userId\":" + user.getUserId() + ","
                + "\"fullName\":\"" + escapeJson(user.getFullName()) + "\","
                + "\"username\":\"" + escapeJson(user.getUsername()) + "\","
                + "\"role\":\"" + escapeJson(user.getRole()) + "\","
                + "\"patientId\":" + user.getPatientId() + ","
                + "\"doctorId\":" + user.getDoctorId()
                + "}";

            sendJson(exchange, 200, json);
        });

        // DOCTOR LIST API
        server.createContext("/api/doctors", exchange -> {

            if (handleOptions(exchange)) return;

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            if (!isAllowedOrigin(exchange)) {
                sendResponse(exchange, 403, "Origin not allowed");
                return;
            }

            AuthenticatedUser user = getSessionUser(exchange);

            if (user == null || !"Patient".equals(user.getRole())) {
                sendResponse(exchange, 401, "Patient login required");
                return;
            }


String sql =
    "SELECT doctor_id, doctor_name, specialization, "
    + "department, consultation_fee "
    + "FROM doctors";

            StringBuilder json = new StringBuilder("[");
            boolean first = true;

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {

                    if (!first) {
                        json.append(",");
                    }

                    json.append("{")
                        .append("\"doctorId\":")
                        .append(rs.getInt("doctor_id"))
                        .append(",")

                        .append("\"doctorName\":\"")
                        .append(escapeJson(rs.getString("doctor_name")))
                        .append("\",")

                        .append("\"specialization\":\"")
                        .append(escapeJson(rs.getString("specialization")))
                        .append("\",")

                        .append("\"department\":\"")
                        .append(escapeJson(rs.getString("department")))
                        .append("\",")

                        .append("\"consultationFee\":")
                        .append(rs.getBigDecimal("consultation_fee"))
                        .append("}");

                    first = false;
                }

                json.append("]");
                sendJson(exchange, 200, json.toString());

            } catch (Exception e) {
                System.out.println("Doctor list error: " + e.getMessage());
                sendResponse(exchange, 500,
                    "Unable to load doctor list.");
            }
        });

        // ADMIN DASHBOARD STATISTICS

server.createContext("/api/dashboard-stats", exchange -> {

    if (handleOptions(exchange)) return;

    if (!"GET".equals(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return;
    }

    if (!isAllowedOrigin(exchange)) {
        sendResponse(exchange, 403, "Origin not allowed");
        return;
    }

    AuthenticatedUser user = getSessionUser(exchange);

    if (user == null || !"Admin".equals(user.getRole())) {
        sendResponse(exchange, 401, "Admin login required");
        return;
    }

    String sql =
        "SELECT "
        + "(SELECT COUNT(*) FROM patients) AS total_patients, "
        + "(SELECT COUNT(*) FROM doctors) AS total_doctors, "
        + "(SELECT COUNT(*) FROM appointments) AS total_appointments, "
        + "(SELECT COUNT(*) FROM appointments "
        + "WHERE appointment_status LIKE 'Cancelled%') "
        + "AS cancelled_appointments, "
        + "(SELECT COUNT(*) FROM appointments "
        + "WHERE appointment_status LIKE 'Completed%') "
        + "AS completed_appointments, "
        + "(SELECT COUNT(*) FROM appointments "
        + "WHERE appointment_status LIKE 'Pending%') "
        + "AS pending_appointments";

    try (Connection con = DBConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {

        if (rs.next()) {

            String json = "{"
                + "\"totalPatients\":" + rs.getInt("total_patients") + ","
                + "\"totalDoctors\":" + rs.getInt("total_doctors") + ","
                + "\"totalAppointments\":" + rs.getInt("total_appointments") + ","
                + "\"cancelledAppointments\":" + rs.getInt("cancelled_appointments") + ","
                + "\"completedAppointments\":" + rs.getInt("completed_appointments") + ","
                + "\"pendingAppointments\":" + rs.getInt("pending_appointments")
                + "}";

            sendJson(exchange, 200, json);
        }

    } catch (Exception e) {

        System.out.println(
            "Dashboard stats error: " + e.getMessage()
        );

        sendResponse(
            exchange,
            500,
            "Unable to load dashboard statistics"
        );
    }
});
// ADMIN: PATIENT LIST
server.createContext("/api/admin/patients",
    AuthServer::adminPatients);

// ADMIN: DOCTOR LIST
server.createContext("/api/admin/doctors",
    AuthServer::adminDoctors);

// ADMIN: APPOINTMENT LIST
server.createContext("/api/admin/appointments",
    AuthServer::adminAppointments);
    server.createContext("/api/admin/login-activity",
    AuthServer::adminLoginActivity);
        // PATIENT REGISTRATION
        server.createContext("/api/register",
            PatientRegistrationHandler::handle);

        // APPOINTMENT APIs
        server.createContext("/api/appointments/book",
            AuthServer::bookAppointment);

        server.createContext("/api/appointments/my",
            AuthServer::viewPatientAppointments);

        server.createContext("/api/doctor/appointments",
            AuthServer::viewDoctorAppointments);

        server.createContext("/api/appointments/cancel",
            AuthServer::cancelAppointment);

        server.createContext("/api/appointments/confirm",
            AuthServer::confirmAppointment);

        server.createContext("/api/appointments/reschedule",
            AuthServer::rescheduleAppointment);

        server.createContext("/api/appointments/waiting-time",
            AuthServer::updateWaitingTime);

        // LOGOUT API
        server.createContext("/api/logout", exchange -> {

            if (handleOptions(exchange)) return;

            if (!"POST".equals(exchange.getRequestMethod())) {
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

// CHECK ADMIN ACCESS
private static boolean requireAdmin(HttpExchange exchange)
        throws IOException {

    if (handleOptions(exchange)) return false;

    if (!"GET".equals(exchange.getRequestMethod())) {
        sendResponse(exchange, 405, "Method not allowed");
        return false;
    }

    if (!isAllowedOrigin(exchange)) {
        sendResponse(exchange, 403, "Origin not allowed");
        return false;
    }

    AuthenticatedUser user = getSessionUser(exchange);

    if (user == null || !"Admin".equals(user.getRole())) {
        sendResponse(exchange, 401, "Admin login required");
        return false;
    }

    return true;
}

// ADMIN: VIEW ALL PATIENTS
private static void adminPatients(HttpExchange exchange)
        throws IOException {

    if (!requireAdmin(exchange)) return;

String sql =
    "SELECT patient_id, patient_name, age, "
    + "gender, phone, email "
    + "FROM patients ORDER BY patient_id";
    try (Connection con = DBConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {

        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        while (rs.next()) {
            if (!first) json.append(",");

            json.append("{")
                .append("\"patientId\":").append(rs.getInt("patient_id")).append(",")
                .append("\"patientName\":").append(jsonValue(rs.getString("patient_name"))).append(",")
                .append("\"age\":").append(rs.getObject("age") == null ? "null" : rs.getInt("age")).append(",")
                .append("\"gender\":").append(jsonValue(rs.getString("gender"))).append(",")
                .append("\"phone\":").append(jsonValue(rs.getString("phone"))).append(",")
                .append("\"email\":").append(jsonValue(rs.getString("email")))
                .append("}");

            first = false;
        }

        json.append("]");
        sendJson(exchange, 200, json.toString());

    } catch (Exception e) {
        System.out.println("Admin patients error: " + e.getMessage());
        sendResponse(exchange, 500, "Unable to load patients.");
    }
}

// ADMIN: VIEW ALL DOCTORS
private static void adminDoctors(HttpExchange exchange)
        throws IOException {

    if (!requireAdmin(exchange)) return;

    String sql =
        "SELECT doctor_id, doctor_name, specialization, "
        + "department, consultation_fee "
        + "FROM doctors ORDER BY doctor_id";

    try (Connection con = DBConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {

        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        while (rs.next()) {
            if (!first) json.append(",");

            json.append("{")
                .append("\"doctorId\":").append(rs.getInt("doctor_id")).append(",")
                .append("\"doctorName\":").append(jsonValue(rs.getString("doctor_name"))).append(",")
                .append("\"specialization\":").append(jsonValue(rs.getString("specialization"))).append(",")
                .append("\"department\":").append(jsonValue(rs.getString("department"))).append(",")
                .append("\"consultationFee\":").append(rs.getBigDecimal("consultation_fee"))
                .append("}");

            first = false;
        }

        json.append("]");
        sendJson(exchange, 200, json.toString());

    } catch (Exception e) {
        System.out.println("Admin doctors error: " + e.getMessage());
        sendResponse(exchange, 500, "Unable to load doctors.");
    }
}

// ADMIN: VIEW ALL APPOINTMENTS
private static void adminAppointments(HttpExchange exchange)
        throws IOException {

    if (!requireAdmin(exchange)) return;

    String sql =
        "SELECT a.appointment_id, a.appointment_date, "
        + "a.appointment_time, a.appointment_status, "
        + "a.waiting_time, p.patient_name, d.doctor_name, "
        + "d.specialization "
        + "FROM appointments a "
        + "JOIN patients p ON a.patient_id=p.patient_id "
        + "JOIN doctors d ON a.doctor_id=d.doctor_id "
        + "ORDER BY a.appointment_date DESC, a.appointment_time DESC";

    try (Connection con = DBConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {

        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        while (rs.next()) {
            if (!first) json.append(",");

            json.append("{")
                .append("\"appointmentId\":").append(rs.getInt("appointment_id")).append(",")
                .append("\"date\":").append(jsonValue(rs.getString("appointment_date"))).append(",")
                .append("\"time\":").append(jsonValue(rs.getString("appointment_time"))).append(",")
                .append("\"status\":").append(jsonValue(rs.getString("appointment_status"))).append(",")
                .append("\"waitingTime\":").append(rs.getInt("waiting_time")).append(",")
                .append("\"patientName\":").append(jsonValue(rs.getString("patient_name"))).append(",")
                .append("\"doctorName\":").append(jsonValue(rs.getString("doctor_name"))).append(",")
                .append("\"specialization\":").append(jsonValue(rs.getString("specialization")))
                .append("}");

            first = false;
        }

        json.append("]");
        sendJson(exchange, 200, json.toString());

    } catch (Exception e) {
        System.out.println("Admin appointments error: " + e.getMessage());
        sendResponse(exchange, 500, "Unable to load appointments.");
    }
}

// ADMIN: VIEW LOGIN ACTIVITY
private static void adminLoginActivity(HttpExchange exchange)
        throws IOException {

    if (!requireAdmin(exchange)) {
        return;
    }

    String sql =
        "SELECT activity_id, username, user_role, "
        + "login_time, login_status "
        + "FROM login_activity "
        + "ORDER BY login_time DESC";

    try (Connection con = DBConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {

        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        while (rs.next()) {

            if (!first) {
                json.append(",");
            }

            json.append("{")
                .append("\"activityId\":")
                .append(rs.getInt("activity_id")).append(",")

                .append("\"username\":")
                .append(jsonValue(rs.getString("username"))).append(",")

                .append("\"role\":")
                .append(jsonValue(rs.getString("user_role"))).append(",")

                .append("\"loginTime\":")
                .append(jsonValue(rs.getString("login_time"))).append(",")

                .append("\"status\":")
                .append(jsonValue(rs.getString("login_status")))
                .append("}");

            first = false;
        }

        json.append("]");

        sendJson(exchange, 200, json.toString());

    } catch (Exception e) {

        System.out.println(
            "Login activity error: " + e.getMessage()
        );

        sendResponse(
            exchange,
            500,
            "Unable to load login activity."
        );
    }
}
    // BOOK APPOINTMENT
    private static void bookAppointment(HttpExchange exchange)
            throws IOException {

        if (handleOptions(exchange)) return;

        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        if (!isAllowedOrigin(exchange)) {
            sendResponse(exchange, 403, "Origin not allowed");
            return;
        }

        AuthenticatedUser user = getSessionUser(exchange);

        if (user == null || !"Patient".equals(user.getRole())
                || user.getPatientId() <= 0) {
            sendResponse(exchange, 401, "Patient login required");
            return;
        }

        try {
            Map<String, String> data = readFormData(exchange);

            int doctorId = Integer.parseInt(
                data.getOrDefault("doctorId", "")
            );

            LocalDate date = LocalDate.parse(
                data.getOrDefault("appointmentDate", "")
            );

            LocalTime time = LocalTime.parse(
                data.getOrDefault("appointmentTime", "")
            );

            if (doctorId <= 0 || date.isBefore(LocalDate.now())) {
                sendResponse(exchange, 400,
                    "Choose a valid doctor and appointment date.");
                return;
            }

            if (!AVAILABLE_SLOTS.contains(time)) {
                sendResponse(exchange, 400,
                    "Please choose an available time slot.");
                return;
            }

            String sql =
                "INSERT INTO appointments "
                + "(patient_id, doctor_id, appointment_date, "
                + "appointment_time, appointment_status, waiting_time) "
                + "VALUES (?, ?, ?, ?, 'Booked', 0)";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement doctorCheck = con.prepareStatement(
                    "SELECT doctor_id FROM doctors WHERE doctor_id=?")) {

                doctorCheck.setInt(1, doctorId);

                try (ResultSet rs = doctorCheck.executeQuery()) {
                    if (!rs.next()) {
                        sendResponse(exchange, 404,
                            "Selected doctor was not found.");
                        return;
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setInt(1, user.getPatientId());
                    ps.setInt(2, doctorId);
                    ps.setDate(3, java.sql.Date.valueOf(date));
                    ps.setTime(4, java.sql.Time.valueOf(time));
                    ps.executeUpdate();
                }
            }

            sendJson(exchange, 201,
                "{\"message\":\"Appointment booked successfully.\","
                + "\"status\":\"Booked\"}");

        } catch (DateTimeParseException | NumberFormatException e) {
            sendResponse(exchange, 400, "Invalid doctor, date or time.");

        } catch (java.sql.SQLException e) {
            if ("23000".equals(e.getSQLState())) {
                sendResponse(exchange, 409,
                    "This doctor's selected time slot is already booked.");
            } else {
                System.out.println("Book appointment error: " + e.getMessage());
                sendResponse(exchange, 500,
                    "Unable to book appointment.");
            }

        } catch (Exception e) {
            System.out.println("Book appointment error: " + e.getMessage());
            sendResponse(exchange, 500,
                "Unable to book appointment.");
        }
    }

    // PATIENT: VIEW OWN APPOINTMENTS
    private static void viewPatientAppointments(HttpExchange exchange)
            throws IOException {

        if (handleOptions(exchange)) return;

        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        if (!isAllowedOrigin(exchange)) {
            sendResponse(exchange, 403, "Origin not allowed");
            return;
        }

        AuthenticatedUser user = getSessionUser(exchange);

        if (user == null || !"Patient".equals(user.getRole())
                || user.getPatientId() <= 0) {
            sendResponse(exchange, 401, "Patient login required");
            return;
        }

        String sql =
            "SELECT a.appointment_id, a.appointment_date, "
            + "a.appointment_time, a.appointment_status, "
            + "a.waiting_time, d.doctor_name, d.specialization, "
            + "d.department, d.consultation_fee "
            + "FROM appointments a "
            + "JOIN doctors d ON a.doctor_id=d.doctor_id "
            + "WHERE a.patient_id=? "
            + "ORDER BY a.appointment_date DESC, a.appointment_time DESC";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setInt(1, user.getPatientId());

            try (ResultSet rs = ps.executeQuery()) {

                StringBuilder json = new StringBuilder("[");
                boolean first = true;

                while (rs.next()) {

                    if (!first) json.append(",");

                    json.append("{")
                        .append("\"appointmentId\":")
                        .append(rs.getInt("appointment_id")).append(",")

                        .append("\"date\":")
                        .append(jsonValue(rs.getString("appointment_date"))).append(",")

                        .append("\"time\":")
                        .append(jsonValue(rs.getString("appointment_time"))).append(",")

                        .append("\"status\":")
                        .append(jsonValue(rs.getString("appointment_status"))).append(",")

                        .append("\"waitingTime\":")
                        .append(rs.getInt("waiting_time")).append(",")

                        .append("\"doctorName\":")
                        .append(jsonValue(rs.getString("doctor_name"))).append(",")

                        .append("\"specialization\":")
                        .append(jsonValue(rs.getString("specialization"))).append(",")

                        .append("\"department\":")
                        .append(jsonValue(rs.getString("department"))).append(",")

                        .append("\"consultationFee\":")
                        .append(rs.getBigDecimal("consultation_fee"))

                        .append("}");

                    first = false;
                }

                json.append("]");
                sendJson(exchange, 200, json.toString());
            }

        } catch (Exception e) {
            System.out.println("Patient appointments error: " + e.getMessage());
            sendResponse(exchange, 500,
                "Unable to load appointments.");
        }
    }

    // DOCTOR: VIEW OWN APPOINTMENTS WITH PATIENT DETAILS
    private static void viewDoctorAppointments(HttpExchange exchange)
            throws IOException {

        if (handleOptions(exchange)) return;

        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        if (!isAllowedOrigin(exchange)) {
            sendResponse(exchange, 403, "Origin not allowed");
            return;
        }

        AuthenticatedUser user = getSessionUser(exchange);

        if (user == null || !"Doctor".equals(user.getRole())
                || user.getDoctorId() <= 0) {
            sendResponse(exchange, 401, "Doctor login required");
            return;
        }

        String sql =
            "SELECT a.appointment_id, a.appointment_date, "
            + "a.appointment_time, a.appointment_status, "
            + "a.waiting_time, p.patient_name, p.age, "
            + "p.gender, p.phone, p.email "
            + "FROM appointments a "
            + "JOIN patients p ON a.patient_id=p.patient_id "
            + "WHERE a.doctor_id=? "
            + "ORDER BY a.appointment_date, a.appointment_time";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setInt(1, user.getDoctorId());

            try (ResultSet rs = ps.executeQuery()) {

                StringBuilder json = new StringBuilder("[");
                boolean first = true;

                while (rs.next()) {

                    if (!first) json.append(",");

                    json.append("{")
                        .append("\"appointmentId\":")
                        .append(rs.getInt("appointment_id")).append(",")

                        .append("\"date\":")
                        .append(jsonValue(rs.getString("appointment_date"))).append(",")

                        .append("\"time\":")
                        .append(jsonValue(rs.getString("appointment_time"))).append(",")

                        .append("\"status\":")
                        .append(jsonValue(rs.getString("appointment_status"))).append(",")

                        .append("\"waitingTime\":")
                        .append(rs.getInt("waiting_time")).append(",")

                        .append("\"patientName\":")
                        .append(jsonValue(rs.getString("patient_name"))).append(",")

                        .append("\"age\":")
                        .append(rs.getObject("age") == null
                            ? "null" : rs.getInt("age")).append(",")

                        .append("\"gender\":")
                        .append(jsonValue(rs.getString("gender"))).append(",")

                        .append("\"phone\":")
                        .append(jsonValue(rs.getString("phone"))).append(",")

                        .append("\"email\":")
                        .append(jsonValue(rs.getString("email")))

                        .append("}");

                    first = false;
                }

                json.append("]");
                sendJson(exchange, 200, json.toString());
            }

        } catch (Exception e) {
            System.out.println("Doctor appointments error: " + e.getMessage());
            sendResponse(exchange, 500,
                "Unable to load doctor appointments.");
        }
    }

    // PATIENT OR DOCTOR: CANCEL APPOINTMENT
    private static void cancelAppointment(HttpExchange exchange)
            throws IOException {

        updateAppointmentStatus(exchange, false);
    }

    // DOCTOR: CONFIRM APPOINTMENT
    private static void confirmAppointment(HttpExchange exchange)
            throws IOException {

        updateAppointmentStatus(exchange, true);
    }

    // UPDATE APPOINTMENT STATUS
    private static void updateAppointmentStatus(
            HttpExchange exchange, boolean confirm) throws IOException {

        if (handleOptions(exchange)) return;

        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        if (!isAllowedOrigin(exchange)) {
            sendResponse(exchange, 403, "Origin not allowed");
            return;
        }

        AuthenticatedUser user = getSessionUser(exchange);

        if (user == null) {
            sendResponse(exchange, 401, "Please log in.");
            return;
        }

        if (confirm && !"Doctor".equals(user.getRole())) {
            sendResponse(exchange, 403,
                "Only the assigned doctor can confirm an appointment.");
            return;
        }

        if (!confirm
                && !("Patient".equals(user.getRole())
                || "Doctor".equals(user.getRole()))) {

            sendResponse(exchange, 403,
                "Only the patient or assigned doctor can cancel.");
            return;
        }

        try {
            Map<String, String> data = readFormData(exchange);

            int appointmentId = Integer.parseInt(
                data.getOrDefault("appointmentId", "")
            );

            if (appointmentId <= 0) {
                sendResponse(exchange, 400, "Invalid appointment ID.");
                return;
            }

            String ownerColumn =
                "Patient".equals(user.getRole())
                ? "patient_id" : "doctor_id";

            int ownerId =
                "Patient".equals(user.getRole())
                ? user.getPatientId() : user.getDoctorId();

            if (ownerId <= 0) {
                sendResponse(exchange, 403,
                    "Your account is not linked to a valid profile.");
                return;
            }

            String newStatus;

            if (confirm) {
                newStatus = "Confirmed";
            } else if ("Patient".equals(user.getRole())) {
                newStatus = "Cancelled by Patient";
            } else {
                newStatus = "Cancelled by Doctor";
            }

            String sql =
                "UPDATE appointments SET appointment_status=? "
                + "WHERE appointment_id=? AND " + ownerColumn + "=? "
                + "AND appointment_status IN ('Booked','Confirmed')";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setString(1, newStatus);
                ps.setInt(2, appointmentId);
                ps.setInt(3, ownerId);

                int changed = ps.executeUpdate();

                if (changed == 0) {
                    sendResponse(exchange, 404,
                        "Appointment not found, not assigned to your account, "
                        + "or already closed.");
                    return;
                }
            }

            sendJson(exchange, 200,
                "{\"message\":\"Appointment status updated.\","
                + "\"status\":\"" + newStatus + "\"}");

        } catch (NumberFormatException e) {
            sendResponse(exchange, 400, "Invalid appointment ID.");

        } catch (Exception e) {
            System.out.println(
                "Appointment status update error: " + e.getMessage()
            );

            sendResponse(exchange, 500,
                "Unable to update appointment status.");
        }
    }

    // PATIENT: RESCHEDULE OWN APPOINTMENT
    private static void rescheduleAppointment(HttpExchange exchange)
            throws IOException {

        if (handleOptions(exchange)) return;

        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        if (!isAllowedOrigin(exchange)) {
            sendResponse(exchange, 403, "Origin not allowed");
            return;
        }

        AuthenticatedUser user = getSessionUser(exchange);

        if (user == null || !"Patient".equals(user.getRole())
                || user.getPatientId() <= 0) {
            sendResponse(exchange, 401, "Patient login required");
            return;
        }

        try {
            Map<String, String> data = readFormData(exchange);

            int appointmentId = Integer.parseInt(
                data.getOrDefault("appointmentId", "")
            );

            LocalDate date = LocalDate.parse(
                data.getOrDefault("appointmentDate", "")
            );

            LocalTime time = LocalTime.parse(
                data.getOrDefault("appointmentTime", "")
            );

            if (appointmentId <= 0 || date.isBefore(LocalDate.now())) {
                sendResponse(exchange, 400,
                    "Choose a valid appointment and future date.");
                return;
            }

            if (!AVAILABLE_SLOTS.contains(time)) {
                sendResponse(exchange, 400,
                    "Please choose an available time slot.");
                return;
            }

            String sql =
                "UPDATE appointments SET appointment_date=?, "
                + "appointment_time=? "
                + "WHERE appointment_id=? AND patient_id=? "
                + "AND appointment_status IN ('Booked','Confirmed')";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setDate(1, java.sql.Date.valueOf(date));
                ps.setTime(2, java.sql.Time.valueOf(time));
                ps.setInt(3, appointmentId);
                ps.setInt(4, user.getPatientId());

                int changed = ps.executeUpdate();

                if (changed == 0) {
                    sendResponse(exchange, 404,
                        "Appointment not found, not yours, or already closed.");
                    return;
                }
            }

            sendJson(exchange, 200,
                "{\"message\":\"Appointment rescheduled successfully.\"}");

        } catch (DateTimeParseException | NumberFormatException e) {
            sendResponse(exchange, 400, "Invalid appointment details.");

        } catch (java.sql.SQLException e) {
            if ("23000".equals(e.getSQLState())) {
                sendResponse(exchange, 409,
                    "This doctor's selected time slot is already booked.");
            } else {
                System.out.println("Reschedule error: " + e.getMessage());
                sendResponse(exchange, 500,
                    "Unable to reschedule appointment.");
            }

        } catch (Exception e) {
            System.out.println("Reschedule error: " + e.getMessage());
            sendResponse(exchange, 500,
                "Unable to reschedule appointment.");
        }
    }

    // DOCTOR: UPDATE WAITING TIME
    private static void updateWaitingTime(HttpExchange exchange)
            throws IOException {

        if (handleOptions(exchange)) return;

        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        if (!isAllowedOrigin(exchange)) {
            sendResponse(exchange, 403, "Origin not allowed");
            return;
        }

        AuthenticatedUser user = getSessionUser(exchange);

        if (user == null || !"Doctor".equals(user.getRole())
                || user.getDoctorId() <= 0) {
            sendResponse(exchange, 401, "Doctor login required");
            return;
        }

        try {
            Map<String, String> data = readFormData(exchange);

            int appointmentId = Integer.parseInt(
                data.getOrDefault("appointmentId", "")
            );

            int waitingTime = Integer.parseInt(
                data.getOrDefault("waitingTime", "")
            );

            if (appointmentId <= 0 || waitingTime < 0
                    || waitingTime > 600) {
                sendResponse(exchange, 400,
                    "Enter a valid appointment ID and waiting time (0-600).");
                return;
            }

            String sql =
                "UPDATE appointments SET waiting_time=? "
                + "WHERE appointment_id=? AND doctor_id=? "
                + "AND appointment_status IN ('Booked','Confirmed')";

            try (Connection con = DBConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setInt(1, waitingTime);
                ps.setInt(2, appointmentId);
                ps.setInt(3, user.getDoctorId());

                int changed = ps.executeUpdate();

                if (changed == 0) {
                    sendResponse(exchange, 404,
                        "Appointment not found, not assigned to you, "
                        + "or already closed.");
                    return;
                }
            }

            sendJson(exchange, 200,
                "{\"message\":\"Waiting time updated successfully.\","
                + "\"waitingTime\":" + waitingTime + "}");

        } catch (NumberFormatException e) {
            sendResponse(exchange, 400,
                "Enter valid numeric values.");

        } catch (Exception e) {
            System.out.println("Waiting time update error: " + e.getMessage());
            sendResponse(exchange, 500,
                "Unable to update waiting time.");
        }
    }

    // JSON STRING VALUE
    private static String jsonValue(String value) {

        if (value == null) return "null";

        return "\"" + escapeJson(value) + "\"";
    }

    // CREATE SESSION ID
    private static String createSessionId() {

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes);
    }

    // GET SESSION ID
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

    // GET SESSION USER SAFELY
    private static AuthenticatedUser getSessionUser(
            HttpExchange exchange) {

        String sessionId = getSessionId(exchange);

        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        return sessions.get(sessionId);
    }

    // READ FORM DATA
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

    // CHECK ALLOWED ORIGIN
    private static boolean isAllowedOrigin(HttpExchange exchange) {

        String origin = exchange.getRequestHeaders()
            .getFirst("Origin");

        if (origin == null) return false;

        for (String allowed : ALLOWED_ORIGINS) {
            if (allowed.equals(origin)) return true;
        }

        return false;
    }

    // HANDLE OPTIONS REQUEST
    private static boolean handleOptions(HttpExchange exchange)
            throws IOException {

        if (!"OPTIONS".equals(exchange.getRequestMethod())) {
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

    // ESCAPE JSON VALUES
    private static String escapeJson(String value) {

        if (value == null) return "";

        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }

    // SEND TEXT RESPONSE
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

    // SEND JSON RESPONSE
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

            exchange.getResponseHeaders().set(
                "Vary", "Origin"
            );
        }

        exchange.getResponseHeaders().set(
            "Content-Type", "application/json; charset=UTF-8"
        );

        exchange.getResponseHeaders().set(
            "Cache-Control", "no-store"
        );

        byte[] response = json.getBytes(
            StandardCharsets.UTF_8
        );

        exchange.sendResponseHeaders(status, response.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
}