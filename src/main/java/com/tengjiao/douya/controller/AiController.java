package com.tengjiao.douya.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体
 *
 * @author tengjiao
 * @since 2025-12-04 14:56
 */
@RestController
@RequestMapping("/douya")
@Tag(name = "智能体")
public class AiController {

    @GetMapping("/hello")
    @Operation(summary = "健康监测")
    public String hello() {
        return "hello, ai";
    }
}
