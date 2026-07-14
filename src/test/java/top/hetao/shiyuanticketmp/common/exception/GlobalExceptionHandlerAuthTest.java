package top.hetao.shiyuanticketmp.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import top.hetao.shiyuanticketmp.auth.exception.InvalidAuthSessionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerAuthTest {

    @Test
    void invalidTenantSessionReturnsHttp401() {
        var response = new GlobalExceptionHandler().handleInvalidAuthSession(
                new InvalidAuthSessionException("invalid"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(401, response.getBody().get("code"));
    }
}
