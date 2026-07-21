package com.vault.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Log payload ของ request + response สำหรับ debug (dev)
 *
 * ปิดได้ด้วย app.request-logging.enabled=false (ควรปิดใน prod)
 * - body log เต็มรวม PIN — ช่วย debug booking flow
 * - MASK เฉพาะ header credential (x-auth-code, x-iot-secret) — เป็น API key ตัวเดิมซ้ำ ไม่มีประโยชน์ตอน debug แต่รั่วอันตราย
 * - ข้าม static resource + response body ที่ไม่ใช่ JSON (กัน HTML flood จาก polling 5s)
 * - HIGHEST_PRECEDENCE → wrap ก่อน Spring Security จึง log request ที่ auth fail (401) ด้วย
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.request-logging.enabled", havingValue = "true", matchIfMissing = true)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private static final int MAX_BODY = 4000;  // ตัด body ยาวเกินนี้ กัน log บวม
    private static final Set<String> SKIP_PREFIXES =
            Set.of("/css", "/js", "/webjars", "/images", "/favicon", "/actuator", "/swagger", "/v3/api-docs");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (shouldSkip(uri)) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = System.currentTimeMillis() - start;
            logExchange(req, res, ms);
            res.copyBodyToResponse();  // ต้องเรียก — ไม่งั้น client ไม่ได้รับ body
        }
    }

    private void logExchange(ContentCachingRequestWrapper req, ContentCachingResponseWrapper res, long ms) {
        String query = req.getQueryString() != null ? "?" + req.getQueryString() : "";
        String reqBody = bodyOf(req.getContentAsByteArray(), req.getContentType());
        String resBody = bodyOf(res.getContentAsByteArray(), res.getContentType());

        StringBuilder sb = new StringBuilder();
        sb.append("\n>>> ").append(req.getMethod()).append(' ').append(req.getRequestURI()).append(query);
        sb.append("\n    auth: ").append(authSummary(req));
        if (!reqBody.isEmpty()) sb.append("\n    req body: ").append(reqBody);
        sb.append("\n<<< ").append(res.getStatus()).append(" (").append(ms).append("ms)");
        if (!resBody.isEmpty()) sb.append("\n    res body: ").append(resBody);
        log.info("[HTTP]{}", sb);
    }

    /** log เฉพาะสถานะของ credential header (มี/ไม่มี) — ไม่โชว์ค่าจริง */
    private String authSummary(HttpServletRequest req) {
        String authCode = req.getHeader("x-auth-code") != null ? "x-auth-code=***" : "x-auth-code=-";
        String iotSecret = req.getHeader("x-iot-secret") != null ? " x-iot-secret=***" : "";
        return authCode + iotSecret;
    }

    /** คืน body เป็น string — เฉพาะ JSON/form/text; binary หรือ HTML ยาวๆ ข้าม */
    private String bodyOf(byte[] content, String contentType) {
        if (content.length == 0) return "";
        if (contentType == null) return "";
        boolean loggable = contentType.contains("json")
                || contentType.contains("x-www-form-urlencoded")
                || contentType.startsWith("text/plain");
        if (!loggable) return "";  // ข้าม text/html (page render), รูป ฯลฯ
        String s = new String(content, StandardCharsets.UTF_8).trim();
        return s.length() > MAX_BODY ? s.substring(0, MAX_BODY) + "…(truncated)" : s;
    }

    private boolean shouldSkip(String uri) {
        for (String p : SKIP_PREFIXES) {
            if (uri.startsWith(p)) return true;
        }
        return false;
    }
}
