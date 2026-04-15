package sg.edu.nus.iss.identity.entity;

import java.util.Set;

public final class Roles {
    public static final String ASSESSOR = "assessor";
    public static final String ADMIN = "admin";
    public static final Set<String> ALL = Set.of(ASSESSOR, ADMIN);

    private Roles() {}
}
