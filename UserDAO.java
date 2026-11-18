
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class UserDAO {

    public static AuthenticatedUser authenticate(
            String identifier,
            String password,
            String role) {

        String sql = "SELECT user_id, full_name, username, "
                   + "password_hash, role, patient_id, doctor_id "
                   + "FROM users "
                   + "WHERE (username = ? OR email = ?) "
                   + "AND role = ? "
                   + "AND account_status = 'Active'";

        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, identifier);
            ps.setString(2, identifier);
            ps.setString(3, role);

            try (ResultSet rs = ps.executeQuery()) {

                if (!rs.next()) {
                    return null;
                }

                int userId = rs.getInt("user_id");
                String fullName = rs.getString("full_name");
                String username = rs.getString("username");
                String storedHash = rs.getString("password_hash");
                String storedRole = rs.getString("role");

                int patientValue = rs.getInt("patient_id");
                Integer patientId = rs.wasNull()
                        ? null : patientValue;

                int doctorValue = rs.getInt("doctor_id");
                Integer doctorId = rs.wasNull()
                        ? null : doctorValue;

                // Reject ambiguous identifier matches.
                if (rs.next()) {
                    return null;
                }

                // Verify the password.
                if (!PasswordUtil.verifyPassword(
                        password, storedHash)) {
                    return null;
                }

                // Validate role and linked profile.
                boolean validProfile;

                if ("Patient".equals(storedRole)) {
                    validProfile =
                        patientId != null && doctorId == null;

                } else if ("Doctor".equals(storedRole)) {
                    validProfile =
                        doctorId != null && patientId == null;

                } else if ("Admin".equals(storedRole)) {
                    validProfile =
                        patientId == null && doctorId == null;

                } else {
                    validProfile = false;
                }

                if (!validProfile) {
                    return null;
                }

                return new AuthenticatedUser(
                    userId,
                    fullName,
                    username,
                    storedRole,
                    patientId,
                    doctorId
                );
            }

        } catch (Exception e) {
            System.out.println(
                "Authentication error: " + e.getMessage()
            );
            return null;
        }
    }
}
