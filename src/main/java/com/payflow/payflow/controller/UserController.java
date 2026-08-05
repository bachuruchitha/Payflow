package com.payflow.payflow.controller;


// imports: RegisterRequest, RegisterResponse, UserService,
//          the three annotations, and whatever User type register() returns

import com.payflow.payflow.dto.RegisterRequest;
import com.payflow.payflow.dto.RegisterResponse;
import com.payflow.payflow.entity.User;
import com.payflow.payflow.service.UserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }


    // 1. The controller needs the UserService to do the real work.
    //    How does a Spring bean get another bean handed to it?
    //    (You did this in UserService for the repositories — same pattern.)

    @PostMapping("/api/users/register")
    public RegisterResponse register(@RequestBody RegisterRequest request) {
        // 2. Call the service. What does userService.register(...) need
        //    as arguments, and what does it return? Pull the pieces out
        //    of 'request' using its accessors.

        User user = userService.register(request.email(), request.password());
        // 3. The service gives you back a domain object (a User).
        //    But this method must return a RegisterResponse.
        //    Build one from the User's fields — this is the
        //    "translate kitchen-language back into customer-language" step.
        //    Remember: RegisterResponse deliberately carries only id + email.

        return new RegisterResponse(user.getId(), user.getEmail());
    }
}