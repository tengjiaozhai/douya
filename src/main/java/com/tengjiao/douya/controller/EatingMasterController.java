package com.tengjiao.douya.controller;

import com.tengjiao.douya.app.EatingMasterApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 吃饭大师接口
 *
 * @author tengjiao
 * @since 2025-12-05
 */
@RestController
@RequestMapping("/douya/eating")
@Tag(name = "吃饭大师接口")
@RequiredArgsConstructor
public class EatingMasterController {

    private final EatingMasterApp eatingMasterApp;

    @GetMapping("/ask")
    @Operation(summary = "询问美食问题")
    public String ask(@RequestParam String question) {
        return eatingMasterApp.ask(question);
    }
}