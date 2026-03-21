package sg.edu.nus.iss.identity.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ServiceException extends RuntimeException {

    private final HttpStatus status;

    public ServiceException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ServiceException badRequest(String message) {
        return new ServiceException(HttpStatus.BAD_REQUEST, message);
    }

    public static ServiceException unauthorized(String message) {
        return new ServiceException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ServiceException forbidden(String message) {
        return new ServiceException(HttpStatus.FORBIDDEN, message);
    }

    public static ServiceException notFound(String message) {
        return new ServiceException(HttpStatus.NOT_FOUND, message);
    }

    public static ServiceException conflict(String message) {
        return new ServiceException(HttpStatus.CONFLICT, message);
    }
}
