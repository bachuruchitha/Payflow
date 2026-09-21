package com.payflow.payflow.controller;


import com.payflow.payflow.dto.RegisterRequest;
import com.payflow.payflow.dto.RegisterResponse;
import com.payflow.payflow.entity.User;
import com.payflow.payflow.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }


    @PostMapping("/api/users/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {

        User user = userService.register(request.email(), request.password());

        return new RegisterResponse(user.getId(), user.getEmail());
    }
}