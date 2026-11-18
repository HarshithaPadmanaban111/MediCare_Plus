import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Scanner;

public class Doctor {

    public static void addDoctor() {

        Scanner sc = new Scanner(System.in);

        try {
            System.out.println("\n--- ADD DOCTOR ---");

            System.out.print("Enter Doctor Name: ");
            String name = sc.nextLine();

            System.out.print("Enter Specialization: ");
            String specialization = sc.nextLine();

            System.out.print("Enter Department: ");
            String department = sc.nextLine();

            System.out.print("Enter Phone Number: ");
            String phone = sc.nextLine();

            System.out.print("Enter Consultation Fee: ");
            double fee = Double.parseDouble(sc.nextLine());

            Connection con = DBConnection.getConnection();

            String sql = "INSERT INTO doctors " +
                "(doctor_name, specialization, department, phone, consultation_fee) " +
                "VALUES (?, ?, ?, ?, ?)";

            PreparedStatement ps = con.prepareStatement(sql);

            ps.setString(1, name);
            ps.setString(2, specialization);
            ps.setString(3, department);
            ps.setString(4, phone);
            ps.setDouble(5, fee);

            ps.executeUpdate();

            System.out.println("Doctor Added Successfully!");

            ps.close();
            con.close();

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }
}
