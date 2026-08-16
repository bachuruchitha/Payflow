package com.payflow.payflow.service;

import com.payflow.payflow.dto.LoginResponse;
import com.payflow.payflow.entity.User;
import com.payflow.payflow.exception.InvalidCredentialsException;
import com.payflow.payflow.repository.UserRepository;
import com.payflow.payflow.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;

        this.jwtService = jwtService;
    }


    public LoginResponse login(String email, String rawPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(user.getId());

        return new LoginResponse(token);

    }
}
