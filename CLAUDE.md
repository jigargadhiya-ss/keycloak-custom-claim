# CLAUDE.md — Keycloak App Role Protocol Mapper

## Project purpose

Custom Keycloak SPI that injects an `app_role` JWT claim into OIDC tokens.
Value is read from the user's Keycloak profile attribute `app_role`; falls back
to `"user"` when the attribute is absent or blank. A Spring Boot backend consumes
this claim via `@PreAuthorize`.

**Target:** Keycloak 22+ (Quarkus-based distribution only — not WildFly).

---

## Project structure

```
keycloak-app-role-mapper/
├── pom.xml                                                    # Maven build
├── CLAUDE.md                                                  # this file
├── README.md                                                  # deployment guide
├── .gitignore
└── src/main/
    ├── java/com/example/keycloak/mapper/
    │   └── AppRoleProtocolMapper.java                         # the mapper
    └── resources/META-INF/services/
        └── org.keycloak.protocol.ProtocolMapper               # SPI registration
```

---

## Build

```bash
mvn clean package
# produces: target/keycloak-app-role-mapper-1.0.0.jar
```

Java 17 required. No tests exist yet.

---

## Key file roles

### `AppRoleProtocolMapper.java`

The only source file. Everything relevant:

- **Package:** `com.example.keycloak.mapper`
- **PROVIDER_ID:** `"oidc-app-role-mapper"` — must be unique across all Keycloak providers
- **USER_ATTRIBUTE_NAME:** `"app_role"` — Keycloak user profile attribute that is read
- **FALLBACK_VALUE:** `"user"` — emitted when attribute is null or blank
- **Extends:** `AbstractOIDCProtocolMapper` (from `keycloak-services`)
- **Implements:** `OIDCAccessTokenMapper`, `OIDCIDTokenMapper`, `UserInfoTokenMapper`
  — implementing an interface automatically adds that token type's toggle to the
  Admin Console form via `OIDCAttributeMapperHelper.addIncludeInTokensConfig()`
- **Core method:** `setClaim(IDToken, ProtocolMapperModel, UserSessionModel, KeycloakSession, ClientSessionContext)`
  — 5-argument form (non-deprecated since Keycloak 23+); parent class calls this
  for each token type after checking admin-configured toggles
- **Config properties** (rendered in Admin Console mapper form):
  - Token Claim Name — text field, admin sets to `"app_role"`
  - Per-token toggles — auto-generated from implemented interfaces

### `org.keycloak.protocol.ProtocolMapper` (ServiceLoader file)

Single line: `com.example.keycloak.mapper.AppRoleProtocolMapper`

Keycloak's `kc.sh build` (Quarkus augmentation) reads this via Java ServiceLoader
to discover the provider. Missing or wrong content here = mapper invisible in
Admin Console regardless of JAR being present.

### `pom.xml`

- `keycloak.version` property (default `26.0.6`) — **must match the running Docker image tag exactly**
- All four Keycloak deps use `scope=provided` — they must NEVER be bundled
- Plain JAR only — no fat-jar, no shade plugin, no spring-boot plugin

---

## Hard constraints — never violate these

1. **All Keycloak deps must be `provided` scope.**
   Bundling them causes `ClassCastException` because two classloaders hold the same type.

2. **`keycloak.version` in `pom.xml` must match the running container's image tag exactly.**
   A minor-version mismatch causes `NoSuchMethodError` at runtime because Keycloak
   does not maintain binary API compatibility between minor releases.

3. **`kc.sh build` must be run inside the container after every JAR update.**
   Quarkus bakes providers into a fast-start image. Without the build step the old
   image is used and the new provider does not appear.

4. **The `app_role` attribute must be declared in Realm Settings → User Profile.**
   Keycloak 22+ only exposes declared attributes through `UserModel.getFirstAttribute()`.
   If it is not declared, the fallback `"user"` is always returned even when a value
   is stored in the database.

5. **Use the 5-argument `setClaim()` override, not the deprecated 3-argument form.**
   The 3-argument form was deprecated in Keycloak 23 and may be removed.

---

## How to add a new mapper to this project

1. Create a new class in `com.example.keycloak.mapper` that extends
   `AbstractOIDCProtocolMapper` and implements the desired token interfaces.
2. Assign a unique `PROVIDER_ID` string (prefix with `oidc-`).
3. Add the fully qualified class name as a new line in
   `src/main/resources/META-INF/services/org.keycloak.protocol.ProtocolMapper`.
4. Rebuild and redeploy (`mvn clean package` → copy JAR → `kc.sh build` → restart).

Do not create a separate JAR/Maven module per mapper — all mappers share one JAR.

---

## How to read additional user data in `setClaim()`

```java
// Any user attribute declared in Realm → User Profile
String value = userSession.getUser().getFirstAttribute("attribute_name");

// User email / username
String email    = userSession.getUser().getEmail();
String username = userSession.getUser().getUsername();

// Realm roles
Set<RoleModel> realmRoles = userSession.getUser().getRealmRoleMappings();

// Client roles
Set<RoleModel> clientRoles = userSession.getUser()
        .getClientRoleMappings(keycloakSession.clients()
            .getClientByClientId(userSession.getRealm(), "your-client-id"));
```

---

## Deployment quick reference

```bash
# Live container
docker cp target/keycloak-app-role-mapper-1.0.0.jar <CONTAINER>:/opt/keycloak/providers/
docker exec -it <CONTAINER> /opt/keycloak/bin/kc.sh build
docker restart <CONTAINER>

# Dockerfile (multi-stage, production)
FROM quay.io/keycloak/keycloak:26.0.6 AS builder
COPY target/keycloak-app-role-mapper-1.0.0.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build
FROM quay.io/keycloak/keycloak:26.0.6
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
```

Full step-by-step instructions (including Admin Console setup and curl verification)
are in `README.md`.

---

## Spring Boot integration summary

```java
// SecurityConfig.java
JwtGrantedAuthoritiesConverter conv = new JwtGrantedAuthoritiesConverter();
conv.setAuthoritiesClaimName("app_role");   // claim name in the JWT
conv.setAuthorityPrefix("ROLE_");           // Spring Security prefix
```

```java
// Controller
@PreAuthorize("hasRole('admin')")   // matches claim value "admin" + prefix "ROLE_"
```

---

## GitHub repository

- Remote: `git@github.com:jigargadhiya-ss/keycloak-custom-claim.git`
- Default branch: `develop`
