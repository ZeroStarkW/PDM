package com.pdm.mvp.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/login", "/dashboard", "/workbench", "/devices", "/devices/{id}", "/alerts", "/work-orders", "/maintenance", "/baseline", "/diagnostic-rules", "/points/{id}"})
    public String forwardToSpa() { return "forward:/index.html"; }
}
