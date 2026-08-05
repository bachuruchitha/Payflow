package com.payflow.payflow.health;   // pick a sensible sub-package, e.g. "health" or "web"

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {


    @GetMapping("/health")
    public String health(){
        return "OK";
    }
}