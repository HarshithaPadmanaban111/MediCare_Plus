
public class AuthenticatedUser {

    private final int userId;
    private final String fullName;
    private final String username;
    private final String role;
    private final Integer patientId;
    private final Integer doctorId;

    public AuthenticatedUser(
            int userId,
            String fullName,
            String username,
            String role,
            Integer patientId,
            Integer doctorId) {

        this.userId = userId;
        this.fullName = fullName;
        this.username = username;
        this.role = role;
        this.patientId = patientId;
        this.doctorId = doctorId;
    }

    public int getUserId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    public Integer getPatientId() {
        return patientId;
    }

    public Integer getDoctorId() {
        return doctorId;
    }
}
