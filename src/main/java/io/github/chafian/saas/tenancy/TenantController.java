package io.github.chafian.saas.tenancy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {
  private final TenantService tenants;

  public TenantController(TenantService tenants) {
    this.tenants = tenants;
  }

  public record CreateTenant(@NotBlank @Size(max = 100) String name) {}

  public record SetMember(@NotBlank @Size(max = 200) String subject, @NotNull Role role) {}

  @PostMapping
  ResponseEntity<TenantService.Tenant> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTenant body) {
    TenantService.Tenant tenant = tenants.create(body.name(), jwt.getSubject());
    return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenant.id())).body(tenant);
  }

  @GetMapping("/{tenantId}")
  TenantService.Tenant get(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
    return tenants.get(tenantId, jwt.getSubject());
  }

  @GetMapping
  List<TenantService.Tenant> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return tenants.list(jwt.getSubject(), limit, offset);
  }

  @GetMapping("/{tenantId}/members")
  List<TenantService.Membership> members(
      @PathVariable UUID tenantId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return tenants.members(tenantId, jwt.getSubject(), limit, offset);
  }

  @PutMapping("/{tenantId}/members")
  ResponseEntity<Void> setMember(
      @PathVariable UUID tenantId,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody SetMember body) {
    tenants.setMember(
        tenantId, jwt.getSubject(), new TenantService.Membership(body.subject(), body.role()));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{tenantId}/members")
  ResponseEntity<Void> removeMember(
      @PathVariable UUID tenantId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam @NotBlank @Size(max = 200) String subject) {
    tenants.removeMember(tenantId, jwt.getSubject(), subject);
    return ResponseEntity.noContent().build();
  }
}
