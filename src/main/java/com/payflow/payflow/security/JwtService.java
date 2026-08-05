package com.payflow.payflow.security;   // or wherever you keep security bits

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    // Constructor injection of the two config values.
    // Do the base64-decode + wrap ONCE, here, and store the SecretKey.
    public JwtService(
            @Value("${payflow.jwt.secret}") String secret,
            @Value("${payflow.jwt.expiration-ms}") long expirationMs) {
        // TODO: base64-decode `secret` -> byte[]
        // TODO: Keys.hmacShaKeyFor(bytes) -> assign to this.signingKey
        // TODO: assign this.expirationMs
        this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        this.expirationMs = expirationMs;
    }

    public String generateToken(UUID userId) {
        // TODO: compute now and expiry (Date objects from System.currentTimeMillis())
        // TODO: Jwts.builder()
        //          .subject(userId.toString())   // sub claim
        //          .issuedAt(now)                 // iat
        //          .expiration(expiry)            // exp
        //          .signWith(signingKey)          // HMAC-SHA256
        //          .compact();                    // -> the aaa.bbb.ccc string
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(expirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();    }
}