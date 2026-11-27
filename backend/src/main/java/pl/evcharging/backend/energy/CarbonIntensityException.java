package pl.evcharging.backend.energy;

import javax.net.ssl.SSLException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;

public final class CarbonIntensityException extends RuntimeException {

    public enum Category { TIMEOUT, DNS, TLS, TRANSPORT, HTTP_STATUS, INVALID_DATA }

    private final Category category;
    private final Integer httpStatus;

    private CarbonIntensityException(Category category, Integer httpStatus, Throwable cause) {
        super("Carbon Intensity API: " + category, cause);
        this.category = category;
        this.httpStatus = httpStatus;
    }

    public Category category() {
        return category;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    static CarbonIntensityException httpStatus(int status) {
        return new CarbonIntensityException(Category.HTTP_STATUS, status, null);
    }

    static CarbonIntensityException invalidData(Throwable cause) {
        return new CarbonIntensityException(Category.INVALID_DATA, null, cause);
    }

    static CarbonIntensityException transport(Throwable cause) {
        return new CarbonIntensityException(classifyTransport(cause), null, cause);
    }

    private static Category classifyTransport(Throwable error) {
        if (hasCause(error, SSLException.class)) return Category.TLS;
        if (hasCause(error, UnknownHostException.class) || hasCause(error, UnresolvedAddressException.class)) {
            return Category.DNS;
        }
        if (hasCause(error, InterruptedException.class)) return Category.TRANSPORT;
        if (hasCause(error, HttpTimeoutException.class) || hasCause(error, SocketTimeoutException.class)
                || hasCause(error, TimeoutException.class)) return Category.TIMEOUT;
        return Category.TRANSPORT;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }
}
