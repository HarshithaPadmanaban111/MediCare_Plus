import java.util.Scanner;

public class TestLogin {

    public static void main(String[] args) {

        Scanner sc = new Scanner(System.in);

        System.out.print("Enter admin password: ");
        String password = sc.nextLine();

        AuthenticatedUser user =
            UserDAO.authenticate("david", password, "Admin");

        if (user != null) {
            System.out.println("Login verification: SUCCESS");
        } else {
            System.out.println("Login verification: FAILED");
        }
    }
}