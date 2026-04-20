package sg.edu.nus.iss.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleSsoRequest {

    @NotBlank(message = "credential is required")
    private String credential;
}
