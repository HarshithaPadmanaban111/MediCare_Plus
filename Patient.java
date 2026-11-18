import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Scanner;

public class Patient {

    public static void addPatient() {

        Scanner sc = new Scanner(System.in);

        try {
            System.out.println("\n--- ADD PATIENT ---");

            System.out.print("Enter Patient Name: ");
            String name = sc.nextLine();

            System.out.print("Enter Age: ");
            int age = Integer.parseInt(sc.nextLine());

            System.out.print("Enter Gender: ");
            String gender = sc.nextLine();

            System.out.print("Enter Phone Number: ");
            String phone = sc.nextLine();

            System.out.print("Enter Email: ");
            String email = sc.nextLine();

            Connection con = DBConnection.getConnection();

            String sql = "INSERT INTO patients " +
                "(patient_name, age, gender, phone, email) " +
                "VALUES (?, ?, ?, ?, ?)";

            PreparedStatement ps = con.prepareStatement(sql);

            ps.setString(1, name);
            ps.setInt(2, age);
            ps.setString(3, gender);
            ps.setString(4, phone);
            ps.setString(5, email);

            ps.executeUpdate();

            System.out.println("Patient Added Successfully!");

            ps.close();
            con.close();

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}