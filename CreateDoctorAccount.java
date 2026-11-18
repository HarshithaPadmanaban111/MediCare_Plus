
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Scanner;

public class CreateDoctorAccount {

    public static void main(String[] args) {

        Scanner sc = new Scanner(System.in);

        try {
            System.out.print("Enter Doctor ID: ");
            int doctorId = Integer.parseInt(sc.nextLine());

            System.out.print("Enter Username: ");
            String username = sc.nextLine().trim();

            System.out.print("Enter Password: ");
            String password = sc.nextLine();

            System.out.print("Enter Email: ");
            String email = sc.nextLine().trim();

            if (password.length() < 8) {
                System.out.println("Password must contain at least 8 characters.");
                return;
            }

            try (Connection con = DBConnection.getConnection()) {

                String doctorSql =
                    "SELECT doctor_name FROM doctors WHERE doctor_id = ?";

                String doctorName;

                try (PreparedStatement ps =
                         con.prepareStatement(doctorSql)) {

                    ps.setInt(1, doctorId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            System.out.println("Doctor ID not found.");
                            return;
                        }

                        doctorName = rs.getString("doctor_name");
                    }
                }

                String hash = PasswordUtil.hashPassword(password);

                String sql = """
                    INSERT INTO users
                    (full_name, username, email, password_hash, role,
                     account_status, doctor_id)
                    VALUES (?, ?, ?, ?, 'Doctor', 'Active', ?)
                    """;

                try (PreparedStatement ps =
                         con.prepareStatement(sql)) {

                    ps.setString(1, doctorName);
                    ps.setString(2, username);
                    ps.setString(3, email);
                    ps.setString(4, hash);
                    ps.setInt(5, doctorId);

                    ps.executeUpdate();

                    System.out.println("Doctor account created successfully!");
                    System.out.println("Doctor Name: " + doctorName);
                    System.out.println("Username: " + username);
                }
            }

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        } finally {
            sc.close();
        }
    }
}
