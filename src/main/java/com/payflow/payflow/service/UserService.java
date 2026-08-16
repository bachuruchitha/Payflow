package com.payflow.payflow.service;

import com.payflow.payflow.entity.User;
import com.payflow.payflow.entity.Wallet;
import com.payflow.payflow.exception.DuplicateEmailException;
import com.payflow.payflow.repository.UserRepository;
import com.payflow.payflow.repository.WalletRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, WalletRepository walletRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(String email, String rawPassword) {
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        String hashedPassword = passwordEncoder.encode(rawPassword);

        UUID userId = UUID.randomUUID();
        User user = new User(userId, email, hashedPassword);
        Wallet wallet = new Wallet(UUID.randomUUID(), userId, "USD");
        userRepository.save(user);
        walletRepository.save(wallet);
        return user;

    }
}
