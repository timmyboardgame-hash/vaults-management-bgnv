package com.vault.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates x-auth-code header for /api/v1/** requests.
 * ถ้า header ตรง → set Authentication → proceed
 * ถ้าไม่ตรง → Spring Security จะ reject ด้วย 401 เอง
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String validAuthCode;

    public ApiKeyAuthFilter(String validAuthCode) {
        this.validAuthCode = validAuthCode;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authCode = request.getHeader("x-auth-code");
        if (validAuthCode != null && validAuthCode.equals(authCode)) {
            var auth = new UsernamePasswordAuthenticationToken(
                "api-client", null,
                List.of(new SimpleGrantedAuthority("ROLE_API"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
