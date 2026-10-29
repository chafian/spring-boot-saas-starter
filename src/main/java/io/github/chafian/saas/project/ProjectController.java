package io.github.chafian.saas.project;

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
@RequestMapping("/api/v1/tenants/{tenantId}/projects")
public class ProjectController {
  private final ProjectService projects;

  public ProjectController(ProjectService projects) {
    this.projects = projects;
  }

  public record CreateProject(
      @NotBlank @Size(max = 100) String name, @NotNull @Size(max = 2000) String description) {
    ProjectService.Draft draft() {
      return new ProjectService.Draft(name, description);
    }
  }

  public record UpdateProject(
      @NotBlank @Size(max = 100) String name,
      @NotNull @Size(max = 2000) String description,
      @NotNull @PositiveOrZero Long version) {
    ProjectService.Revision revision() {
      return new ProjectService.Revision(new ProjectService.Draft(name, description), version);
    }
  }

  @PostMapping
  ResponseEntity<ProjectService.Project> create(
      @PathVariable UUID tenantId,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CreateProject body) {
    var project = projects.create(tenantId, jwt.getSubject(), body.draft());
    return ResponseEntity.created(
            URI.create("/api/v1/tenants/" + tenantId + "/projects/" + project.id()))
        .body(project);
  }

  @GetMapping
  List<ProjectService.Project> list(
      @PathVariable UUID tenantId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return projects.list(tenantId, jwt.getSubject(), limit, offset);
  }

  @GetMapping("/{id}")
  ProjectService.Project get(
      @PathVariable UUID tenantId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return projects.get(tenantId, jwt.getSubject(), id);
  }

  @PutMapping("/{id}")
  ProjectService.Project update(
      @PathVariable UUID tenantId,
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody UpdateProject body) {
    return projects.update(tenantId, jwt.getSubject(), id, body.revision());
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(
      @PathVariable UUID tenantId,
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam @PositiveOrZero long version) {
    projects.delete(tenantId, jwt.getSubject(), id, version);
    return ResponseEntity.noContent().build();
  }
}
