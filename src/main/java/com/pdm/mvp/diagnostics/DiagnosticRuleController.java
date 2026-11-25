package com.pdm.mvp.diagnostics;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/device-types/{typeId}/rules")
public class DiagnosticRuleController {
    private final DiagnosticRuleService rules;

    public DiagnosticRuleController(DiagnosticRuleService rules) { this.rules = rules; }

    @GetMapping
    public List<DiagnosticRuleService.DiagnosticRuleView> list(@PathVariable Long typeId) { return rules.list(typeId); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public DiagnosticRuleService.DiagnosticRuleView create(@PathVariable Long typeId,
            @Valid @RequestBody DiagnosticRuleRequest request) {
        return rules.create(typeId, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DiagnosticRuleService.DiagnosticRuleView update(@PathVariable Long typeId, @PathVariable Long id,
            @Valid @RequestBody DiagnosticRuleUpdateRequest request) {
        return rules.update(typeId, id, request);
    }
}
