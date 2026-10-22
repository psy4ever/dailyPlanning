package com.dayoudu.cloud;

import static com.dayoudu.cloud.ApiError.fail;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class Authentication implements ApplicationRunner {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final Map<String, long[]> attempts = new ConcurrentHashMap<>();
    @Value("${daily.username}") String username;
    @Value("${daily.password}") String password;
    @Value("${daily.registration-enabled:true}") boolean registrationEnabled;
    Authentication(JdbcTemplate db, PlatformTransactionManager transactionManager) {
        this.db = db; this.transactions = new TransactionTemplate(transactionManager);
    }
    @Override public void run(ApplicationArguments ignored) {
        if (db.queryForObject("SELECT COUNT(*) FROM cloud_accounts", Long.class) == 0) {
            if (username.isBlank() || username.length() > 80 || password.length() < 8 || password.length() > 16 ||
                !StandardCharsets.US_ASCII.newEncoder().canEncode(password) ||
                password.chars().anyMatch(c -> c < 33 || c > 126 || "'\"$#\\".indexOf(c) >= 0))
                throw new IllegalStateException("首次启动需设置 DAILY_USERNAME 和 8–16 位 ASCII DAILY_PASSWORD（不能包含空白、引号、$、#、反斜杠）");
            transactions.execute(ignoredStatus -> {
                db.update("INSERT INTO cloud_accounts(id,uid,legacy_uid,account_name,password_hash,change_seq) VALUES(?,?,?,?,?,0)",
                    UUID.randomUUID().toString(), allocateUid(), username, username, encoder.encode(password));
                return null;
            });
        }
    }
    private String allocateUid() {
        Integer next = db.queryForObject("SELECT next_value FROM cloud_uid_sequence WHERE id=1 FOR UPDATE", Integer.class);
        if (next == null || next > 99_999_999) throw fail(409, "UID 已发放完");
        db.update("UPDATE cloud_uid_sequence SET next_value=? WHERE id=1", next + 1);
        return String.format(Locale.ROOT, "%08d", next);
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/health", "/error", "/api/v1/auth/login", "/api/v1/auth/register").permitAll().anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> res.sendError(401)))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
                    if ("POST".equals(req.getMethod()) && (req.getContentLengthLong() < 0 || req.getContentLengthLong() > 2_000_000)) {
                        res.sendError(413); return;
                    }
                    String bearer = req.getHeader("Authorization");
                    if (bearer != null && bearer.startsWith("Bearer ") && bearer.length() <= 256) {
                        var owners = db.queryForList("SELECT owner_id FROM cloud_sessions WHERE token_hash=? AND expires_at>?",
                            String.class, hash(bearer.substring(7)), System.currentTimeMillis());
                        if (!owners.isEmpty()) SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(owners.get(0), null, List.of()));
                    }
                    chain.doFilter(req, res);
                }
            }, UsernamePasswordAuthenticationFilter.class).build();
    }
    synchronized Map<String, Object> login(String identifier, String pass, String address) {
        limit(address, 10);
        if (identifier == null || pass == null || identifier.isBlank() || identifier.length() > 80 ||
            pass.getBytes(StandardCharsets.UTF_8).length > 72) throw fail(401, "昵称或密码错误");
        String name = identifier.strip();
        var direct = db.queryForList("SELECT id,uid,account_name,password_hash FROM cloud_accounts WHERE uid=? OR legacy_uid=? ORDER BY CASE WHEN uid=? THEN 0 ELSE 1 END LIMIT 1", name, name, name);
        if (!direct.isEmpty() && encoder.matches(pass, (String) direct.get(0).get("password_hash")))
            return session(direct.get(0));
        // Nicknames may repeat. Match the password within a bounded set; if it
        // identifies more than one account, the user must use the unique UID.
        var candidates = db.queryForList("SELECT id,uid,account_name,password_hash FROM cloud_accounts WHERE account_name=? ORDER BY id LIMIT 21", name);
        if (candidates.size() > 20) throw fail(409, "同名账户过多，请使用 UID 登录");
        var matches = new ArrayList<Map<String, Object>>();
        for (var candidate : candidates) if (encoder.matches(pass, (String) candidate.get("password_hash"))) matches.add(candidate);
        if (matches.isEmpty()) {
            // Unknown nicknames still perform a password check.
            if (candidates.isEmpty()) encoder.matches(pass, "$2a$12$XVsHcFMdUNP4AvkL5IWP5ObfdkVRI4D9o3M/I5H5gJtIE.LwRSG3O");
            throw fail(401, "昵称或密码错误");
        }
        if (matches.size() > 1) throw fail(409, "同名账户使用了相同密码，请使用 UID 登录");
        return session(matches.get(0));
    }
    synchronized Map<String, Object> register(String name, String pass, String address) {
        if (!registrationEnabled) throw fail(403, "此个人云服务未开放新账户注册，请使用已有账户登录");
        limit("register:" + address, 5);
        String cleanName = name == null ? "" : name.strip();
        if (cleanName.isEmpty() || cleanName.length() > 40 || cleanName.chars().anyMatch(Character::isISOControl))
            throw fail(422, "昵称需为 1–40 个可显示字符");
        if (pass == null || pass.length() < 8 || pass.length() > 16 ||
            !StandardCharsets.US_ASCII.newEncoder().canEncode(pass) ||
            pass.chars().anyMatch(c -> c < 33 || c > 126 || "'\"$#\\".indexOf(c) >= 0))
            throw fail(422, "密码需为 8–16 位 ASCII 字符，不能包含空白、引号、$、#、反斜杠");
        return transactions.execute(ignoredStatus -> {
            String owner = UUID.randomUUID().toString();
            String uid = allocateUid();
            db.update("INSERT INTO cloud_accounts(id,uid,account_name,password_hash,change_seq) VALUES(?,?,?,?,0)",
                owner, uid, cleanName, encoder.encode(pass));
            return session(Map.of("id", owner, "uid", uid, "account_name", cleanName));
        });
    }
    private void limit(String address, int max) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
        if (attempts.size() > 10_000) throw fail(429, "请稍后重试");
        long[] attempt = attempts.computeIfAbsent(address, key -> new long[]{now, 0});
        if (++attempt[1] > max) throw fail(429, "尝试过多，请一分钟后重试");
    }
    private Map<String, Object> session(Map<String, Object> account) {
        long now = System.currentTimeMillis();
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String owner = (String) account.get("id");
        long expiry = now + 30L * 24 * 3600 * 1000;
        db.update("DELETE FROM cloud_sessions WHERE expires_at<=?", now);
        db.update("INSERT INTO cloud_sessions(token_hash,owner_id,expires_at) VALUES(?,?,?)", hash(token), owner, expiry);
        return Map.of("token", token, "ownerId", owner, "uid", account.get("uid"),
            "accountName", account.get("account_name"), "expiresAtMillis", expiry);
    }
    @RestController
    static class AuthController {
        final Authentication auth;
        AuthController(Authentication auth) { this.auth = auth; }
        // Accept the old field only while already installed clients update.
        record Login(String accountName, String uid, String username, String password) {}
        @PostMapping("/api/v1/auth/login") Map<String, Object> login(@RequestBody Login request, HttpServletRequest servlet) {
            return auth.login(request.accountName != null ? request.accountName : request.uid != null ? request.uid : request.username,
                request.password, servlet.getRemoteAddr());
        }
        record Register(String accountName, String password) {}
        @PostMapping("/api/v1/auth/register") Map<String, Object> register(@RequestBody Register request, HttpServletRequest servlet) {
            return auth.register(request.accountName, request.password, servlet.getRemoteAddr());
        }
        @GetMapping("/api/v1/account/me") Map<String, Object> me() {
            String owner=(String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            var row=auth.db.queryForMap("SELECT uid,account_name FROM cloud_accounts WHERE id=?",owner);
            return Map.of("uid",row.get("uid"),"accountName",row.get("account_name"));
        }
        record Rename(String accountName) {}
        @PostMapping("/api/v1/account/name") Map<String, Object> rename(@RequestBody Rename request) {
            String name=request.accountName == null ? "" : request.accountName.strip();
            if(name.isEmpty() || name.length()>40 || name.chars().anyMatch(c -> Character.isISOControl(c)))
                throw fail(422,"昵称需为 1–40 个可显示字符");
            String owner=(String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            auth.db.update("UPDATE cloud_accounts SET account_name=? WHERE id=?",name,owner);
            return Map.of("accountName",name);
        }
        @PostMapping("/api/v1/auth/logout") Map<String, Object> logout(HttpServletRequest servlet) {
            auth.db.update("DELETE FROM cloud_sessions WHERE token_hash=?", hash(servlet.getHeader("Authorization").substring(7)));
            return Map.of("ok", true);
        }
    }
}
