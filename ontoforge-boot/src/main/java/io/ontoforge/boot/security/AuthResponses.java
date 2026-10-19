package io.ontoforge.boot.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

final class AuthResponses {
    private static final ObjectMapper JSON=new ObjectMapper();
    private AuthResponses() {}
    static void error(HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control","no-store");
        response.setContentType("application/json;charset=UTF-8");
        JSON.writeValue(response.getWriter(),Map.of("code",code,"message",message));
    }
}
